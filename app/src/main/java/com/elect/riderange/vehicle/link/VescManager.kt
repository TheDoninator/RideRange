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
import com.elect.riderange.vehicle.vesc.SimulatedVescPort
import com.elect.riderange.vehicle.vesc.VescProtocol
import com.elect.riderange.vehicle.vesc.VescSession
import com.elect.riderange.vehicle.vesc.VescSimulator
import com.elect.riderange.vehicle.vesc.VescVehicleParams
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
 * VESC vehicles over BLE (Nordic UART, the same GATT pipe as the Ninebot link): the local controller, other controllers
 * on its CAN bus (COMM_FORWARD_CAN), its setup values, a VESC BMS and the Float package. [VescSession] decides what to
 * ask for; every request goes through [VescProtocol.ReadOnlyWriter], which refuses anything that isn't a "get", so
 * nothing on the vehicle can be changed. Debug builds can also connect to [VescSimulator] (no Bluetooth needed).
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
    @Volatile private var sim: SimulatedVescPort? = null
    private var job: Job? = null
    private var decoder = VescProtocol.Decoder()
    private var session = VescSession()
    private val lock = Any()

    private fun linkOrNull(): ScooterLink? {
        if (!bluetoothPermitted(context)) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "Allow \"Nearby devices\" so the app can reach the vehicle.") }
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

    private fun params(): VescVehicleParams {
        val v = vehicle.value
        return VescVehicleParams(v?.motorPolePairs ?: 15, v?.wheelDiameterMm ?: FmParse.DEFAULT_WHEEL_MM, v?.gearRatio ?: 1.0, v?.cellsSeries)
    }

    override fun connect(address: String, name: String?) {
        job?.cancel()
        closeSim()
        synchronized(lock) { decoder = VescProtocol.Decoder(); session = VescSession() }
        val simulated = VescSimulator.isSimAddress(address)
        val raw: (ByteArray) -> Unit
        if (simulated) {
            val port = VescSimulator.open(address) { bytes -> onNotify(bytes) }
            if (port == null) { fail("The simulated VESC only exists in debug builds."); return }
            sim = port
            raw = { port.write(it) }
        } else {
            val l = linkOrNull() ?: return
            raw = { l.write(it) }
        }
        val writer = VescProtocol.ReadOnlyWriter(raw)
        job = scope.launch {
            _state.value = ScooterState(ScooterPhase.CONNECTING, name, address, message = "Connecting…")
            if (!simulated) {
                val l = link!!
                l.connect(address)
                val snap = withTimeoutOrNull(25_000) { linkState.first { it.state == LinkStateKind.CONNECTED && it.address == address.uppercase() } }
                if (snap == null) {
                    fail(linkState.value.reason ?: "Couldn't connect. Turn the vehicle on and close VESC Tool / Float Control: one connection at a time.")
                    l.disconnect()
                    return@launch
                }
                settings.update { it.copy(lastScooterAddress = snap.address, lastScooterName = snap.name ?: name) }
                _state.update { it.copy(name = snap.name ?: name) }
            } else {
                settings.update { it.copy(lastScooterAddress = address, lastScooterName = name) }
            }
            _state.update { it.copy(phase = ScooterPhase.HANDSHAKE, message = "Asking the controller for its values…") }
            synchronized(lock) { session.openingRequests() }.forEach { writer.send(it) }
            val start = System.currentTimeMillis()
            while (isActive) {
                delay(250)
                synchronized(lock) { session.nextRequests() }.forEach { writer.send(it) }
                val now = System.currentTimeMillis()
                val last = synchronized(lock) { session.lastReplyMs }
                val silentFor = now - (if (last == 0L) start else last)
                if (silentFor > 8000) {
                    fail(if (last == 0L) "Connected, but nothing answered VESC requests. Is this a VESC with BLE (Nordic UART)?"
                    else "The vehicle stopped answering.")
                    if (sim == null) link?.disconnect()
                    closeSim()
                    return@launch
                }
            }
        }
    }

    private fun closeSim() {
        sim?.close()
        sim = null
    }

    override fun disconnect() {
        job?.cancel()
        if (sim == null) link?.disconnect()
        closeSim()
        _state.value = ScooterState(ScooterPhase.DISCONNECTED, _state.value.name, _state.value.address)
    }

    private fun fail(msg: String) {
        _state.update { it.copy(phase = ScooterPhase.ERROR, message = msg, telemetry = null, vesc = null) }
    }

    // ---- ScooterLink.Listener ----
    override fun onLink(link: LinkSnapshot) {
        linkState.value = link
        if (link.state == LinkStateKind.DISCONNECTED && _state.value.phase == ScooterPhase.CONNECTED && sim == null) fail(link.reason ?: "Vehicle disconnected.")
    }

    /** BLE notifications (or simulator bytes) -> frames -> session -> state. Called from the BLE / simulator thread. */
    override fun onNotify(data: ByteArray) {
        val now = System.currentTimeMillis()
        val snap = synchronized(lock) {
            var changed = false
            for (payload in decoder.feed(data)) if (session.onPayload(payload, now)) changed = true
            if (!changed) return
            session.snapshot
        }
        if (_state.value.phase !in setOf(ScooterPhase.HANDSHAKE, ScooterPhase.CONNECTED)) return
        val p = params()
        val t = snap.toTelemetry(now, p)
        _state.update {
            it.copy(
                phase = if (t != null) ScooterPhase.CONNECTED else it.phase,
                telemetry = t ?: it.telemetry,
                message = if (t != null) null else it.message,
                firmware = snap.fw?.label ?: it.firmware,
                vesc = snap,
                batterySource = snap.battery(now, p.cellsSeries)?.second?.label,
            )
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
            message = if (_found.value.none { f -> f.ninebot }) "Nothing with a Bluetooth UART found. Turn the vehicle on and keep it close." else null) }
    }

    override fun onWriteDone(n: Int) {}
    override fun onWriteChunk(data: ByteArray) {}
    override fun onError(message: String) { Log.i("RideRange", "vesc: $message") }
    override fun onInfo(message: String) { Log.i("RideRange", message) }
}
