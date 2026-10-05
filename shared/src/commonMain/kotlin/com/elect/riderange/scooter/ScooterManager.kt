package com.elect.riderange.scooter

import com.elect.riderange.core.Log
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.scooter.ble.LinkSnapshot
import com.elect.riderange.scooter.ble.LinkStateKind
import com.elect.riderange.scooter.ble.ScanFilter
import com.elect.riderange.scooter.ble.UartLink
import com.elect.riderange.scooter.protocol.HandshakeException
import com.elect.riderange.scooter.protocol.NbSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class FoundScooter(val address: String, val name: String?, val rssi: Int, val ninebot: Boolean)

enum class ScooterPhase { DISCONNECTED, SCANNING, CONNECTING, HANDSHAKE, PRESS_BUTTON, CONNECTED, ERROR }

data class ScooterState(
    val phase: ScooterPhase = ScooterPhase.DISCONNECTED,
    val name: String? = null,
    val address: String? = null,
    val serial: String? = null,
    val message: String? = null,
    val telemetry: Telemetry? = null,
    /** No live data from this vehicle (no link, or its firmware refuses third-party apps): GPS speed + battery slider. */
    val manualMode: Boolean = false,
    /** Board firmware revision, when the link reads one (Onewheel / VESC). */
    val firmware: String? = null,
    /** VESC only: everything read so far (controllers incl. CAN, setup values, BMS, Float package). */
    val vesc: com.elect.riderange.vehicle.vesc.VescSnapshot? = null,
    /** Where [Telemetry.batteryPct] came from, when the link knows ("VESC BMS", "controller", "pack voltage"). */
    val batterySource: String? = null,
)

/**
 * Read-only connection to the Max G2: BLE link (copied from Ninebot Bridge), NbSession opened with
 * readOnly = true (only INIT/PING/PAIR/READ can be sent), then [TelemetryPoller]. Pairing keys are saved per
 * serial; a key pasted from the Ninebot Bridge app is tried first so no re-pairing is needed.
 */
class ScooterManager(private val ble: com.elect.riderange.vehicle.link.BlePlatform, private val settings: SettingsStore, private val scope: CoroutineScope) :
    UartLink.Listener, com.elect.riderange.vehicle.link.VehicleLink {
    private val _state = MutableStateFlow(ScooterState())
    override val state: StateFlow<ScooterState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<FoundScooter>>(emptyList())
    override val found: StateFlow<List<FoundScooter>> = _found.asStateFlow()
    private val linkState = MutableStateFlow(LinkSnapshot())

    private var link: UartLink? = null
    @kotlin.concurrent.Volatile private var session: NbSession? = null
    private var job: Job? = null

    fun bluetoothPermitted(): Boolean = ble.permitted()

    private fun linkOrNull(): UartLink? {
        if (!bluetoothPermitted()) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = ble.permissionMessage("scooter")) }
            return null
        }
        val l = link ?: ble.uartLink(this).also { link = it }
        if (!l.bluetoothAvailable) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "This device has no Bluetooth (emulators and simulators don't).") }
            return null
        }
        return l
    }

    override fun scan() {
        val l = linkOrNull() ?: return
        _found.value = emptyList()
        _state.update { it.copy(phase = ScooterPhase.SCANNING, message = "Looking for scooters…") }
        l.startScan(8.0)
    }

    override fun connect(address: String, name: String?) {
        val l = linkOrNull() ?: return
        job?.cancel()
        job = scope.launch {
            _state.value = ScooterState(ScooterPhase.CONNECTING, name, address, message = "Connecting…")
            l.connect(address)
            val snap = withTimeoutOrNull(25_000) {
                linkState.first { it.state == LinkStateKind.CONNECTED && it.address == address.uppercase() }
            }
            if (snap == null) {
                fail(linkState.value.reason ?: "Couldn't connect. Turn the scooter on and close the Segway app or Ninebot Bridge: the scooter accepts only one Bluetooth connection at a time.")
                l.disconnect()
                return@launch
            }
            val advName = snap.name ?: name ?: run { fail("The scooter's name is unknown; scan again."); return@launch }
            settings.update { it.copy(lastScooterAddress = snap.address, lastScooterName = advName) }
            val s = NbSession({ raw -> l.write(raw) }, readOnly = true, log = { Log.d("RideRange", it) })
            session = s
            s.start(advName)
            _state.update { it.copy(phase = ScooterPhase.HANDSHAKE, name = advName, message = "Authenticating…") }
            val pasted = settings.pastedKey()
            val known = settings.knownSerials().associateWith { settings.key(it) }
            val result = try {
                s.handshake(
                    knownKey = pasted,
                    keyForSerial = { serial -> known[serial] },
                    onPressButton = {
                        _state.update { it.copy(phase = ScooterPhase.PRESS_BUTTON, message = "Press the scooter's power button once (short press) to pair.") }
                    },
                )
            } catch (e: HandshakeException) {
                fail(e.message ?: "Pairing failed")
                l.disconnect()
                return@launch
            } catch (e: Exception) {
                fail("Connection error: ${e.message ?: e::class.simpleName ?: "error"}")
                l.disconnect()
                return@launch
            }
            settings.putKey(result.serial, result.appKey)
            if (pasted != null) settings.setPastedKey(null)
            _state.update { it.copy(phase = ScooterPhase.CONNECTED, serial = result.serial, message = null) }
            val poller = TelemetryPoller(s, { t -> _state.update { st -> st.copy(telemetry = t) } })
            try {
                poller.run()
            } catch (_: Exception) {
            }
            if (_state.value.phase == ScooterPhase.CONNECTED) {
                fail("Lost the scooter connection.")
                l.disconnect()
            }
        }
    }

    override fun disconnect() {
        job?.cancel()
        session = null
        link?.disconnect()
        _state.value = ScooterState(ScooterPhase.DISCONNECTED, _state.value.name, _state.value.address)
    }

    private fun fail(msg: String) {
        session = null
        _state.update { it.copy(phase = ScooterPhase.ERROR, message = msg, telemetry = null) }
    }

    // ---- UartLink.Listener ----
    override fun onLink(link: LinkSnapshot) {
        linkState.value = link
        if (link.state == LinkStateKind.DISCONNECTED && _state.value.phase == ScooterPhase.CONNECTED) {
            fail(link.reason ?: "Scooter disconnected.")
        }
    }

    override fun onNotify(data: ByteArray) { session?.onNotification(data) }

    override fun onScanResult(name: String?, address: String, rssi: Int, ninebot: Boolean) = _found.update { list ->
        val i = list.indexOfFirst { it.address == address }
        val d = FoundScooter(address, name ?: list.getOrNull(i)?.name, rssi, ninebot || ScanFilter.isNinebot(name, emptyList()))
        (if (i >= 0) list.toMutableList().also { it[i] = d } else list + d)
            .sortedWith(compareByDescending<FoundScooter> { it.ninebot }.thenByDescending { it.rssi })
    }

    override fun onScanDone() {
        if (_state.value.phase == ScooterPhase.SCANNING) {
            _state.update { it.copy(phase = ScooterPhase.DISCONNECTED, message = if (_found.value.none { f -> f.ninebot }) "No scooter found. Turn it on and keep it close." else null) }
        }
    }

    override fun onWriteDone(n: Int) {}
    override fun onWriteChunk(data: ByteArray) {}
    override fun onError(message: String) {
        if (_state.value.phase in setOf(ScooterPhase.SCANNING, ScooterPhase.CONNECTING)) _state.update { it.copy(message = message) }
    }
    override fun onInfo(message: String) { Log.i("RideRange", message) }
}
