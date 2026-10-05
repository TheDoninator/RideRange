package com.elect.riderange.vehicle.link

import com.elect.riderange.core.Log
import com.elect.riderange.core.currentTimeMillis
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.scooter.FoundScooter
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.scooter.ScooterState
import com.elect.riderange.scooter.Telemetry
import com.elect.riderange.scooter.ble.CbClient
import com.elect.riderange.scooter.ble.bluetoothDenied
import com.elect.riderange.scooter.ble.full
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.VehicleType
import com.elect.riderange.vehicle.onewheel.FmAccess
import com.elect.riderange.vehicle.onewheel.FmParse
import com.elect.riderange.vehicle.onewheel.FmUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBPeripheral

/**
 * Future Motion Onewheel boards on iPhone, the same decisions as the Android FmManager: read the firmware and hardware
 * revision (readable without authentication), subscribe only on firmware that shares data with third-party apps
 * ([FmAccess]), otherwise manual mode. Strictly read-only: reads and notification subscriptions, never a write.
 */
class IosFmManager(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val vehicle: StateFlow<Vehicle?>,
) : VehicleLink, CbClient.Events {
    private val _state = MutableStateFlow(ScooterState())
    override val state: StateFlow<ScooterState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<FoundScooter>>(emptyList())
    override val found: StateFlow<List<FoundScooter>> = _found.asStateFlow()

    private val cb = CbClient(this)
    private var peripheral: CBPeripheral? = null
    private var chars: Map<String, CBCharacteristic> = emptyMap()
    private var firmware: Int? = null
    private var hardware: Int? = null
    private var telemetry = Telemetry()
    private var scanning = false
    private var scanJob: Job? = null

    private fun ready(): Boolean {
        if (bluetoothDenied()) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "Allow Bluetooth for RideRange (Settings > RideRange) so the app can reach the board.") }
            return false
        }
        if (cb.unsupported) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, manualMode = true, message = "This device has no Bluetooth. Manual mode: GPS speed and the battery slider.") }
            return false
        }
        return true
    }

    override fun scan() {
        if (!ready()) return
        _found.value = emptyList()
        _state.update { it.copy(phase = ScooterPhase.SCANNING, message = "Looking for Onewheels…") }
        cb.whenOn {
            cb.scan()
            scanning = true
            scanJob?.cancel()
            scanJob = scope.launch { delay(8000); stopScan() }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        cb.stopScan()
        if (_state.value.phase == ScooterPhase.SCANNING) _state.update {
            it.copy(phase = ScooterPhase.DISCONNECTED, message = if (_found.value.none { f -> f.ninebot }) "No Onewheel found. Turn it on and keep it close." else null)
        }
    }

    override fun onDiscovered(p: CBPeripheral, name: String?, serviceUuids: List<String>, rssi: Int) {
        val ow = name?.lowercase()?.startsWith("ow") == true || FmUuids.SERVICE in serviceUuids
        val d = FoundScooter(cb.address(p), name, rssi, ow)
        _found.update { list ->
            val i = list.indexOfFirst { it.address == d.address }
            (if (i >= 0) list.toMutableList().also { it[i] = d } else list + d).sortedWith(compareByDescending<FoundScooter> { it.ninebot }.thenByDescending { it.rssi })
        }
    }

    override fun connect(address: String, name: String?) {
        if (!ready()) return
        cb.whenOn {
            stopScan()
            close()
            firmware = null; hardware = null; telemetry = Telemetry()
            _state.value = ScooterState(ScooterPhase.CONNECTING, name, address, message = "Connecting…")
            val p = cb.peripheral(address)
            if (p == null) {
                _state.update { it.copy(phase = ScooterPhase.ERROR, message = "iPhone can't find this board by its saved address. Scan, then pick it from the list.") }
                return@whenOn
            }
            peripheral = p
            cb.connect(p)
            scope.launch { settings.update { it.copy(lastScooterAddress = address.uppercase(), lastScooterName = name ?: it.lastScooterName) } }
        }
    }

    override fun disconnect() {
        close()
        _state.update { ScooterState(ScooterPhase.DISCONNECTED, it.name, it.address, firmware = it.firmware, manualMode = it.manualMode, message = it.message.takeIf { _ -> it.manualMode }) }
    }

    private fun close() {
        peripheral?.let { cb.cancel(it) }
        peripheral = null
        chars = emptyMap()
    }

    override fun onConnected(p: CBPeripheral) {
        if (p != peripheral) return
        _state.update { it.copy(phase = ScooterPhase.HANDSHAKE, message = "Reading the board's firmware version…") }
        cb.discover(p)
    }

    override fun onConnectFailed(p: CBPeripheral, error: String?) = lost(p, error)
    override fun onDisconnected(p: CBPeripheral, error: String?) = lost(p, error)

    private fun lost(p: CBPeripheral, error: String?) {
        if (p != peripheral) return
        val wasManual = _state.value.manualMode
        close()
        if (!wasManual) _state.update { it.copy(phase = ScooterPhase.ERROR, telemetry = null,
            message = "Couldn't connect (${error ?: "no answer"}). Close the Onewheel app: the board accepts one connection at a time.") }
    }

    override fun onServices(p: CBPeripheral, services: Map<String, List<CBCharacteristic>>) {
        if (p != peripheral) return
        val list = services[FmUuids.SERVICE]
        if (list == null) {
            close()
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "This device has no Onewheel service.") }
            return
        }
        chars = list.associateBy { it.UUID.full() }
        read(FmUuids.FIRMWARE)
        read(FmUuids.HARDWARE)
    }

    private fun read(uuid: String) {
        val p = peripheral ?: return
        chars[uuid]?.let { p.readValueForCharacteristic(it) }
    }

    private fun subscribe(uuid: String) {
        val p = peripheral ?: return
        // Subscribing writes the standard CCCD descriptor (done by iOS); no board characteristic is ever written.
        chars[uuid]?.let { p.setNotifyValue(true, forCharacteristic = it) }
    }

    override fun onValue(p: CBPeripheral, c: CBCharacteristic, value: ByteArray?, error: String?) {
        if (p != peripheral) return
        val uuid = c.UUID.full()
        if (value == null || error != null) {
            if (uuid == FmUuids.HARDWARE) decide()
            return
        }
        when (uuid) {
            FmUuids.FIRMWARE -> firmware = FmParse.firmware(value)
            FmUuids.HARDWARE -> {
                hardware = FmParse.u16(value)
                decide()
            }
            else -> {
                val v = vehicle.value
                telemetry = FmParse.apply(telemetry, uuid, value, v?.wheelDiameterMm ?: FmParse.DEFAULT_WHEEL_MM,
                    plusHardware = v?.type == VehicleType.ONEWHEEL_PLUS, nowMs = currentTimeMillis())
                _state.update { it.copy(telemetry = telemetry) }
            }
        }
    }

    private fun decide() {
        val fw = firmware
        val access = FmAccess.decide(fw, hardware)
        _state.update { it.copy(firmware = fw?.toString()) }
        if (access != FmAccess.Access.OPEN || chars.isEmpty()) {
            Log.i("RideRange", "onewheel fw=$fw hw=$hardware access=$access: manual mode")
            close()
            _state.update { it.copy(phase = ScooterPhase.DISCONNECTED, manualMode = true, telemetry = null, message = FmAccess.message(access, fw)) }
            return
        }
        _state.update { it.copy(phase = ScooterPhase.CONNECTED, manualMode = false, message = FmAccess.message(access, fw)) }
        read(FmUuids.BATTERY_PCT)
        FmUuids.NOTIFY.forEach { subscribe(it) }
        // A board that says "open" but sends only zeros is locked after all.
        scope.launch {
            delay(8000)
            if (peripheral != null && FmParse.looksLocked(telemetry)) {
                close()
                _state.update { it.copy(phase = ScooterPhase.DISCONNECTED, manualMode = true, telemetry = null,
                    message = FmAccess.message(FmAccess.Access.LOCKED_GEMINI, fw)) }
            }
        }
    }
}
