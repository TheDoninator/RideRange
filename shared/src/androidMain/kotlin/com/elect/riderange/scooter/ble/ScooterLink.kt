package com.elect.riderange.scooter.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.os.SystemClock
import java.util.UUID

/**
 * Owns the BLE scan and the GATT connection to the scooter. All work runs on one handler thread so GATT
 * operations are strictly serialized. Permissions are checked by the caller (the service) before use.
 */
@SuppressLint("MissingPermission")
class ScooterLink(private val context: Context, private val listener: UartLink.Listener) : UartLink {


    private val thread = HandlerThread("ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private val queue = WriteQueue(20)
    private var link = LinkSnapshot()
    private val names = HashMap<String, String>()   // address -> advertised name, from scans
    private var userDisconnect = false
    private val reconnect = ReconnectPolicy()
    var autoReconnect = true
    private var scanning = false

    val snapshot: LinkSnapshot get() = link

    override val bluetoothAvailable: Boolean get() = adapter != null
    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    override fun shutdown() {
        handler.post {
            stopScanInternal(report = false)
            reconnect.stop()
            closeGatt()
            setLink(LinkSnapshot())
            thread.quitSafely()
        }
    }

    // ---- scanning --------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: try { dev.name } catch (_: SecurityException) { null }
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
            val address = dev.address
            handler.post {
                if (name != null) names[address] = name
                listener.onScanResult(name ?: names[address], address, result.rssi, ScanFilter.isNinebot(name, uuids))
                if (address == link.address) setLink(link.copy(rssi = result.rssi))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                listener.onError("Bluetooth scan failed (code $errorCode)")
                listener.onScanDone()
            }
        }
    }

    private val scanTimeout = Runnable { stopScanInternal(report = true) }

    override fun startScan(seconds: Double) { handler.post {
        val a = adapter
        if (a == null) {
            listener.onError("This device has no Bluetooth")
            listener.onScanDone()
            return@post
        }
        if (!a.isEnabled) {
            listener.onError("Bluetooth is off. Turn it on in the app or in quick settings.")
            listener.onScanDone()
            return@post
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("Bluetooth scanner unavailable")
            listener.onScanDone()
            return@post
        }
        if (scanning) stopScanInternal(report = false)
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        } catch (e: SecurityException) {
            listener.onError("Missing Bluetooth scan permission")
            listener.onScanDone()
            return@post
        }
        scanning = true
        listener.onInfo("Scanning for ${seconds.toInt()} s")
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, (seconds * 1000).toLong())
    } }

    override fun stopScan() { handler.post { stopScanInternal(report = true) } }

    private fun stopScanInternal(report: Boolean) {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (report) listener.onScanDone()
    }

    // ---- connection ------------------------------------------------------------------------------

    override fun connect(address: String) { handler.post {
        userDisconnect = false
        reconnect.stop()
        handler.removeCallbacks(reconnectRunnable)
        connectInternal(address.uppercase())
    } }

    override fun disconnect() { handler.post {
        userDisconnect = true
        reconnect.stop()
        handler.removeCallbacks(reconnectRunnable)
        closeGatt()
        setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null, reason = null))
    } }

    private fun connectInternal(address: String) {
        val a = adapter
        if (a == null || !a.isEnabled) {
            setLink(LinkSnapshot(LinkStateKind.DISCONNECTED, names[address], address, reason = "Bluetooth is off"))
            listener.onError("Bluetooth is off")
            return
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            listener.onError("Bad Bluetooth address: $address")
            return
        }
        stopScanInternal(report = true)
        closeGatt()
        val device = a.getRemoteDevice(address)
        val name = names[address] ?: try { device.name } catch (_: SecurityException) { null }
        setLink(LinkSnapshot(LinkStateKind.CONNECTING, name, address))
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private fun closeGatt() {
        val n = queue.clear()
        if (n > 0) listener.onError("Dropped $n pending write(s): link closed")
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        rx = null
    }

    private val reconnectRunnable = Runnable {
        val addr = link.address ?: return@Runnable
        if (reconnect.active) {
            listener.onInfo("Reconnecting to $addr")
            connectInternal(addr)
        }
    }

    private fun onLinkLost(status: Int, wasConnected: Boolean) {
        val addr = link.address
        closeGatt()
        if (userDisconnect || addr == null) {
            setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (autoReconnect && (wasConnected || reconnect.active)) {
            if (!reconnect.active) reconnect.start(now)
            val delay = reconnect.nextDelay(now)
            if (delay != null) {
                setLink(link.copy(state = LinkStateKind.CONNECTING, service = null, reason = null))
                handler.postDelayed(reconnectRunnable, delay)
                return
            }
            reconnect.stop()
            val reason = "Link lost and did not come back within 1 minute"
            setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null, reason = reason))
            listener.onError(reason)
            return
        }
        val reason = "Could not connect (GATT status $status). Close the maker's app: the vehicle only " +
            "accepts one Bluetooth connection at a time."
        setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null, reason = reason))
        listener.onError(reason)
    }

    private fun setLink(l: LinkSnapshot) {
        link = l
        listener.onLink(l)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) {
                    try { g.close() } catch (_: Exception) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onInfo("GATT connected, discovering services")
                    if (!g.discoverServices()) onLinkLost(-1, false)
                } else {
                    onLinkLost(status, link.state == LinkStateKind.CONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val map = g.services.associate { s -> s.uuid.toString() to s.characteristics.map { it.uuid.toString() }.toSet() }
                val choice = Uart.choose(map)
                if (status != BluetoothGatt.GATT_SUCCESS || choice == null) {
                    userDisconnect = true
                    closeGatt()
                    val reason = "No Bluetooth UART service on this device (is it the right vehicle type?)"
                    setLink(link.copy(state = LinkStateKind.DISCONNECTED, reason = reason))
                    listener.onError(reason)
                    return@post
                }
                val svc = g.getService(UUID.fromString(choice.service))
                val rxChar = svc.getCharacteristic(UUID.fromString(choice.rx))
                val txChar = svc.getCharacteristic(UUID.fromString(choice.tx))
                rx = rxChar
                writeType = if (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(UUID.fromString(Uart.CCCD))
                if (cccd == null) {
                    finishConnected(g, choice.service.toString())
                    return@post
                }
                pendingService = choice.service.toString()
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!ok) listener.onError("Could not enable notifications")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) listener.onError("Enabling notifications failed ($status)")
                finishConnected(g, pendingService ?: Uart.NUS_SERVICE)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                when (val r = queue.onWritten(status == BluetoothGatt.GATT_SUCCESS)) {
                    is WriteQueue.Result.Done -> listener.onWriteDone(r.job.total)
                    is WriteQueue.Result.Failed -> listener.onError("Write to scooter failed (GATT status $status)")
                    WriteQueue.Result.More -> {}
                }
                pump()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value?.copyOf() ?: return
            if (Build.VERSION.SDK_INT < 33) listener.onNotify(v)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            // Called directly on the binder thread, in order: forward immediately to keep boundaries + order.
            listener.onNotify(value.copyOf())
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { setLink(link.copy(mtu = mtu)) }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            handler.post { if (status == BluetoothGatt.GATT_SUCCESS) setLink(link.copy(rssi = rssi)) }
        }
    }

    private var pendingService: String? = null

    private fun finishConnected(g: BluetoothGatt, service: String) {
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        reconnect.stop()
        setLink(link.copy(state = LinkStateKind.CONNECTED, service = service, reason = null, mtu = 23))
        handler.postDelayed(rssiPoll, 2000)
    }

    private val rssiPoll = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            if (link.state != LinkStateKind.CONNECTED) return
            try { g.readRemoteRssi() } catch (_: Exception) {}
            handler.postDelayed(this, 5000)
        }
    }

    // ---- writes ----------------------------------------------------------------------------------

    override fun write(data: ByteArray) { handler.post {
        if (link.state != LinkStateKind.CONNECTED || gatt == null || rx == null) {
            listener.onError("Not connected to a scooter")
            return@post
        }
        queue.enqueue(data)
        pump()
    } }

    private fun pump() {
        queue.takeFinishedEmpty()?.let { listener.onWriteDone(0) }
        val g = gatt ?: return
        val c = rx ?: return
        val chunk = queue.next() ?: return
        listener.onWriteChunk(chunk)
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = writeType
            @Suppress("DEPRECATION")
            c.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) {
            queue.onWritten(false)
            listener.onError("Write to scooter was refused by Android")
            pump()
        }
    }
}
