package com.elect.riderange.vehicle.link

import android.content.Context
import android.util.Log
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.scooter.FoundScooter
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.scooter.ScooterState
import com.elect.riderange.scooter.ble.LinkSnapshot
import com.elect.riderange.scooter.ble.LinkStateKind
import com.elect.riderange.scooter.ble.ScooterLink
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.onewheel.FmParse
import com.elect.riderange.vehicle.vesc.VescProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VESC boards over BLE (Nordic UART, the same GATT pipe as the Ninebot link). Sends only COMM_FW_VERSION and
 * COMM_GET_VALUES (4×/s); [VescProtocol.request] refuses anything else, so nothing on the board can be changed.
 */
class VescManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val vehicle: StateFlow<Vehicle?>,
) : ScooterLink.Listener, VehicleLink {
    private val _state = MutableStateFlow(ScooterState())
    override val state: StateFlow<ScooterState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<FoundScooter>>(emptyList())
    override val found: StateFlow<List<FoundScooter>> = _found.asStateFlow()
    private val linkState = MutableStateFlow(LinkSnapshot())
    private var link: ScooterLink? = null
    private var job: Job? = null
    private val decoder = VescProtocol.Decoder()
    @Volatile private var lastValuesMs = 0L

    private fun linkOrNull(): ScooterLink? {
        if (!bluetoothPermitted(context)) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "Allow \"Nearby devices\" so the app can reach the board.") }
            return null
        }
        val l = link ?: ScooterLink(context, this).also { link = it }
        if (!l.bluetoothAvailable) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "This device has no Bluetooth (the emulator doesn't).") }
            return null
        }
        return l
    }

    override fun scan() {
        val l = linkOrNull() ?: return
        _found.value = emptyList()
        _state.update { it.copy(phase = ScooterPhase.SCANNING, message = "Looking for VESC Bluetooth modules…") }
        l.startScan(8.0)
    }

    override fun connect(address: String, name: String?) {
        val l = linkOrNull() ?: return
        job?.cancel()
        job = scope.launch {
            _state.value = ScooterState(ScooterPhase.CONNECTING, name, address, message = "Connecting…")
            l.connect(address)
            val snap = withTimeoutOrNull(25_000) { linkState.first { it.state == LinkStateKind.CONNECTED && it.address == address.uppercase() } }
            if (snap == null) {
                fail(linkState.value.reason ?: "Couldn't connect. Turn the board on and close VESC Tool / the Float app: one connection at a time.")
                l.disconnect()
                return@launch
            }
            settings.update { it.copy(lastScooterAddress = snap.address, lastScooterName = snap.name ?: name) }
            _state.update { it.copy(phase = ScooterPhase.HANDSHAKE, name = snap.name ?: name, message = "Asking the board for its values…") }
            l.write(VescProtocol.request(VescProtocol.COMM_FW_VERSION))
            lastValuesMs = 0
            val start = System.currentTimeMillis()
            while (isActive) {
                l.write(VescProtocol.request(VescProtocol.COMM_GET_VALUES))
                delay(250)
                val now = System.currentTimeMillis()
                val silentFor = now - (if (lastValuesMs == 0L) start else lastValuesMs)
                if (silentFor > 8000) {
                    fail(if (lastValuesMs == 0L) "Connected, but the board didn't answer VESC requests. Is this a VESC with BLE (Nordic UART)?"
                    else "The board stopped answering.")
                    l.disconnect()
                    return@launch
                }
            }
        }
    }

    override fun disconnect() {
        job?.cancel()
        link?.disconnect()
        _state.value = ScooterState(ScooterPhase.DISCONNECTED, _state.value.name, _state.value.address)
    }

    private fun fail(msg: String) {
        _state.update { it.copy(phase = ScooterPhase.ERROR, message = msg, telemetry = null) }
    }

    // ---- ScooterLink.Listener ----
    override fun onLink(link: LinkSnapshot) {
        linkState.value = link
        if (link.state == LinkStateKind.DISCONNECTED && _state.value.phase == ScooterPhase.CONNECTED) fail(link.reason ?: "Board disconnected.")
    }

    override fun onNotify(data: ByteArray) {
        for (payload in decoder.feed(data)) {
            when (payload[0].toInt() and 0xFF) {
                VescProtocol.COMM_GET_VALUES -> VescProtocol.parseValues(payload)?.let { v ->
                    val veh = vehicle.value
                    val t = VescProtocol.toTelemetry(v, veh?.motorPolePairs ?: 15, veh?.wheelDiameterMm ?: FmParse.DEFAULT_WHEEL_MM,
                        veh?.cellsSeries, System.currentTimeMillis())
                    lastValuesMs = System.currentTimeMillis()
                    _state.update { it.copy(phase = ScooterPhase.CONNECTED, telemetry = t, message = null) }
                }
                VescProtocol.COMM_FW_VERSION -> VescProtocol.parseFwVersion(payload)?.let { fw ->
                    _state.update { it.copy(firmware = "${fw.major}.${fw.minor}" + (fw.hardware?.let { h -> " ($h)" } ?: "")) }
                }
            }
        }
    }

    override fun onScanResult(name: String?, address: String, rssi: Int, ninebot: Boolean) = _found.update { list ->
        val i = list.indexOfFirst { it.address == address }
        val d = FoundScooter(address, name ?: list.getOrNull(i)?.name, rssi, ninebot)
        (if (i >= 0) list.toMutableList().also { it[i] = d } else list + d)
            .sortedWith(compareByDescending<FoundScooter> { it.ninebot }.thenByDescending { it.rssi })
    }

    override fun onScanDone() {
        if (_state.value.phase == ScooterPhase.SCANNING) _state.update { it.copy(phase = ScooterPhase.DISCONNECTED,
            message = if (_found.value.none { f -> f.ninebot }) "Nothing with a Bluetooth UART found. Turn the board on and keep it close." else null) }
    }

    override fun onWriteDone(n: Int) {}
    override fun onWriteChunk(data: ByteArray) {}
    override fun onError(message: String) { Log.i("RideRange", "vesc: $message") }
    override fun onInfo(message: String) { Log.i("RideRange", message) }
}
