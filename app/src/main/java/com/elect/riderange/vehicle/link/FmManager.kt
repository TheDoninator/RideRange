package com.elect.riderange.vehicle.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.scooter.FoundScooter
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.scooter.ScooterState
import com.elect.riderange.scooter.Telemetry
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.VehicleType
import com.elect.riderange.vehicle.onewheel.FmAccess
import com.elect.riderange.vehicle.onewheel.FmParse
import com.elect.riderange.vehicle.onewheel.FmUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Future Motion Onewheel boards. Connects, reads the firmware/hardware revision (readable without authentication)
 * and decides with [FmAccess]: only boards whose firmware shares data with third-party apps (pre-Gemini, < 4034)
 * are subscribed to. Locked boards are disconnected right away and the vehicle runs in manual mode.
 *
 * Strictly read-only: GATT reads and notification subscriptions only. It never writes a characteristic, and in
 * particular does not attempt Future Motion's unlock handshake.
 */
@SuppressLint("MissingPermission")
class FmManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val vehicle: StateFlow<Vehicle?>,
) : VehicleLink {
    private val _state = MutableStateFlow(ScooterState())
    override val state: StateFlow<ScooterState> = _state.asStateFlow()
    private val _found = MutableStateFlow<List<FoundScooter>>(emptyList())
    override val found: StateFlow<List<FoundScooter>> = _found.asStateFlow()

    private val thread by lazy { HandlerThread("onewheel-ble").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val adapter by lazy { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    private var gatt: BluetoothGatt? = null
    /** Serialized GATT operations (Android allows one in flight). */
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false
    private var firmware: Int? = null
    private var hardware: Int? = null
    private var telemetry = Telemetry()
    private var scanning = false

    private fun ready(): Boolean {
        if (!bluetoothPermitted(context)) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "Allow \"Nearby devices\" so the app can reach the board.") }
            return false
        }
        if (adapter == null) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, manualMode = true, message = "This device has no Bluetooth (the emulator doesn't). Manual mode: GPS speed and the battery slider.") }
            return false
        }
        if (adapter?.isEnabled != true) {
            _state.update { it.copy(phase = ScooterPhase.ERROR, message = "Bluetooth is off.") }
            return false
        }
        return true
    }

    // ---- scanning: Onewheels advertise as "ow" + the last digits of the serial ----
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: try { result.device.name } catch (_: SecurityException) { null }
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val ow = name?.lowercase()?.startsWith("ow") == true || FmUuids.SERVICE in uuids
            val d = FoundScooter(result.device.address, name, result.rssi, ow)
            _found.update { list ->
                val i = list.indexOfFirst { it.address == d.address }
                (if (i >= 0) list.toMutableList().also { it[i] = d } else list + d).sortedWith(compareByDescending<FoundScooter> { it.ninebot }.thenByDescending { it.rssi })
            }
        }
    }

    override fun scan() {
        if (!ready()) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        _found.value = emptyList()
        _state.update { it.copy(phase = ScooterPhase.SCANNING, message = "Looking for Onewheels…") }
        handler.post {
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
                scanning = true
            } catch (e: Exception) {
                _state.update { it.copy(phase = ScooterPhase.ERROR, message = "Bluetooth scan failed") }
            }
            handler.postDelayed({ stopScan() }, 8000)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        if (_state.value.phase == ScooterPhase.SCANNING) _state.update {
            it.copy(phase = ScooterPhase.DISCONNECTED, message = if (_found.value.none { f -> f.ninebot }) "No Onewheel found. Turn it on and keep it close." else null)
        }
    }

    override fun connect(address: String, name: String?) {
        if (!ready()) return
        handler.post {
            stopScan()
            close()
            firmware = null; hardware = null; telemetry = Telemetry()
            _state.value = ScooterState(ScooterPhase.CONNECTING, name, address, message = "Connecting…")
            val dev = adapter!!.getRemoteDevice(address.uppercase())
            gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(context, false, cb)
            scope.launch { settings.update { it.copy(lastScooterAddress = address.uppercase(), lastScooterName = name ?: it.lastScooterName) } }
        }
    }

    override fun disconnect() {
        handler.post { close() }
        _state.update { ScooterState(ScooterPhase.DISCONNECTED, it.name, it.address, firmware = it.firmware, manualMode = it.manualMode, message = it.message.takeIf { _ -> it.manualMode }) }
    }

    private fun close() {
        ops.clear(); busy = false
        gatt?.let { try { it.disconnect() } catch (_: Exception) {}; try { it.close() } catch (_: Exception) {} }
        gatt = null
    }

    private fun enqueue(op: (BluetoothGatt) -> Boolean) { ops.addLast(op); pump() }

    private fun pump() {
        if (busy) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        if (!op(g)) { busy = false; pump() }
    }

    private fun opDone() { busy = false; pump() }

    private fun read(svc: android.bluetooth.BluetoothGattService, uuid: UUID) = enqueue { g ->
        svc.getCharacteristic(uuid)?.let { g.readCharacteristic(it) } ?: false
    }

    private fun subscribe(svc: android.bluetooth.BluetoothGattService, uuid: UUID) = enqueue { g ->
        val c = svc.getCharacteristic(uuid) ?: return@enqueue false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(FmUuids.CCCD) ?: return@enqueue false
        // Enabling notifications writes the standard CCCD descriptor; no board characteristic is ever written.
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        else {
            @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
    }

    private fun onValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            FmUuids.FIRMWARE -> firmware = FmParse.firmware(value)
            FmUuids.HARDWARE -> {
                hardware = FmParse.u16(value)
                decide()
            }
            else -> {
                val v = vehicle.value
                telemetry = FmParse.apply(telemetry, uuid, value, v?.wheelDiameterMm ?: FmParse.DEFAULT_WHEEL_MM,
                    plusHardware = v?.type == VehicleType.ONEWHEEL_PLUS, nowMs = System.currentTimeMillis())
                _state.update { it.copy(telemetry = telemetry) }
            }
        }
    }

    /** After firmware + hardware are known: subscribe (open firmware) or fall back to manual mode. */
    private fun decide() {
        val fw = firmware
        val access = FmAccess.decide(fw, hardware)
        _state.update { it.copy(firmware = fw?.toString()) }
        val svc = gatt?.getService(FmUuids.SERVICE)
        if (access != FmAccess.Access.OPEN || svc == null) {
            Log.i("RideRange", "onewheel fw=$fw hw=$hardware access=$access: manual mode")
            close()
            _state.update { it.copy(phase = ScooterPhase.DISCONNECTED, manualMode = true, telemetry = null, message = FmAccess.message(access, fw)) }
            return
        }
        _state.update { it.copy(phase = ScooterPhase.CONNECTED, manualMode = false, message = FmAccess.message(access, fw)) }
        read(svc, FmUuids.BATTERY_PCT)
        FmUuids.NOTIFY.forEach { subscribe(svc, it) }
        // A board that says "open" but sends only zeros is locked after all.
        handler.postDelayed({
            if (gatt != null && FmParse.looksLocked(telemetry)) {
                close()
                _state.update { it.copy(phase = ScooterPhase.DISCONNECTED, manualMode = true, telemetry = null,
                    message = FmAccess.message(FmAccess.Access.LOCKED_GEMINI, fw)) }
            }
        }, 8000)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    _state.update { it.copy(phase = ScooterPhase.HANDSHAKE, message = "Reading the board's firmware version…") }
                    g.discoverServices()
                } else {
                    val wasManual = _state.value.manualMode
                    close()
                    if (!wasManual) _state.update { it.copy(phase = ScooterPhase.ERROR, telemetry = null,
                        message = "Couldn't connect (status $status). Close the Onewheel app: the board accepts one connection at a time.") }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val svc = g.getService(FmUuids.SERVICE)
                if (svc == null) {
                    close()
                    _state.update { it.copy(phase = ScooterPhase.ERROR, message = "This device has no Onewheel service.") }
                    return@post
                }
                read(svc, FmUuids.FIRMWARE)
                read(svc, FmUuids.HARDWARE)
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: ByteArray(0)
            handler.post { if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, v) else if (c.uuid == FmUuids.HARDWARE) decide(); opDone() }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val v = value.copyOf()
            handler.post { if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, v) else if (c.uuid == FmUuids.HARDWARE) decide(); opDone() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { handler.post { opDone() } }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            handler.post { onValue(c.uuid, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            handler.post { onValue(c.uuid, v) }
        }
    }
}
