package com.elect.riderange.scooter.ble

import com.elect.riderange.core.currentTimeMillis
import com.elect.riderange.core.toNSData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBPeripheral

/**
 * The iPhone version of ScooterLink: Nordic UART (or the Ninebot variant) over CoreBluetooth, with the same
 * [WriteQueue] (20-byte chunks, one write in flight), the same service choice ([Uart.choose]) and the same
 * reconnect window (retry for a minute after an unexpected drop). Everything runs on the main queue.
 */
class IosUartLink(private val listener: UartLink.Listener) : UartLink, CbClient.Events {
    private val cb = CbClient(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var link = LinkSnapshot()
    private var peripheral: CBPeripheral? = null
    private var rx: CBCharacteristic? = null
    private var tx: CBCharacteristic? = null
    private var withResponse = true
    private val queue = WriteQueue(20)
    private var userDisconnect = false
    private val reconnect = ReconnectPolicy()
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var scanning = false
    private var pendingService: String? = null

    override val bluetoothAvailable: Boolean get() = !cb.unsupported

    private fun setLink(l: LinkSnapshot) {
        link = l
        listener.onLink(l)
    }

    // ---- scanning ----

    override fun startScan(seconds: Double) {
        if (cb.unsupported) { listener.onError("This device has no Bluetooth"); listener.onScanDone(); return }
        cb.whenOn {
            if (scanning) cb.stopScan()
            cb.scan()
            scanning = true
            listener.onInfo("Scanning for ${seconds.toInt()} s")
            scanJob?.cancel()
            scanJob = scope.launch {
                delay((seconds * 1000).toLong())
                stopScanInternal(true)
            }
        }
        scope.launch {
            delay(3000)
            if (!cb.poweredOn && !scanning) {
                listener.onError("Bluetooth is off. Turn it on in Control Center or Settings.")
                listener.onScanDone()
            }
        }
    }

    override fun stopScan() = stopScanInternal(true)

    private fun stopScanInternal(report: Boolean) {
        scanJob?.cancel()
        if (!scanning) return
        scanning = false
        cb.stopScan()
        if (report) listener.onScanDone()
    }

    override fun onDiscovered(p: CBPeripheral, name: String?, serviceUuids: List<String>, rssi: Int) {
        val address = cb.address(p)
        listener.onScanResult(name, address, rssi, ScanFilter.isNinebot(name, serviceUuids))
        if (address == link.address) setLink(link.copy(rssi = rssi))
    }

    // ---- connection ----

    override fun connect(address: String) {
        userDisconnect = false
        reconnect.stop()
        reconnectJob?.cancel()
        cb.whenOn { connectInternal(address.uppercase()) }
        if (!cb.poweredOn) setLink(LinkSnapshot(LinkStateKind.CONNECTING, null, address.uppercase()))
    }

    private fun connectInternal(address: String) {
        stopScanInternal(true)
        closePeripheral()
        val p = cb.peripheral(address)
        if (p == null) {
            val reason = "iPhone can't find this vehicle by its saved address. Scan, then pick it from the list."
            setLink(LinkSnapshot(LinkStateKind.DISCONNECTED, null, address, reason = reason))
            listener.onError(reason)
            return
        }
        peripheral = p
        setLink(LinkSnapshot(LinkStateKind.CONNECTING, p.name, address))
        cb.connect(p)
    }

    override fun disconnect() {
        userDisconnect = true
        reconnect.stop()
        reconnectJob?.cancel()
        closePeripheral()
        setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null, reason = null))
    }

    override fun shutdown() {
        disconnect()
        stopScanInternal(false)
    }

    private fun closePeripheral() {
        val n = queue.clear()
        if (n > 0) listener.onError("Dropped $n pending write(s): link closed")
        peripheral?.let { cb.cancel(it) }
        peripheral = null
        rx = null; tx = null
    }

    override fun onConnected(p: CBPeripheral) {
        if (p != peripheral) return
        listener.onInfo("Connected, discovering services")
        cb.discover(p)
    }

    override fun onServices(p: CBPeripheral, services: Map<String, List<CBCharacteristic>>) {
        if (p != peripheral) return
        val byUuid = services.mapValues { (_, cs) -> cs.associateBy { it.UUID.full() } }
        val choice = Uart.choose(byUuid.mapValues { it.value.keys })
        if (choice == null) {
            userDisconnect = true
            closePeripheral()
            val reason = "No Bluetooth UART service on this device (is it the right vehicle type?)"
            setLink(link.copy(state = LinkStateKind.DISCONNECTED, reason = reason))
            listener.onError(reason)
            return
        }
        val rxC = byUuid[choice.service]?.get(choice.rx)
        val txC = byUuid[choice.service]?.get(choice.tx)
        if (rxC == null || txC == null) return
        rx = rxC; tx = txC
        withResponse = (rxC.properties and CBCharacteristicPropertyWrite) != 0UL
        pendingService = choice.service
        p.setNotifyValue(true, forCharacteristic = txC)
    }

    override fun onNotifying(p: CBPeripheral, c: CBCharacteristic, error: String?) {
        if (p != peripheral) return
        if (error != null) listener.onError("Enabling notifications failed ($error)")
        reconnect.stop()
        setLink(link.copy(state = LinkStateKind.CONNECTED, service = pendingService ?: Uart.NUS_SERVICE, reason = null, mtu = 23))
    }

    override fun onValue(p: CBPeripheral, c: CBCharacteristic, value: ByteArray?, error: String?) {
        if (p != peripheral || value == null || error != null) return
        listener.onNotify(value)
    }

    override fun onConnectFailed(p: CBPeripheral, error: String?) = onLinkLost(p, error, wasConnected = false)

    override fun onDisconnected(p: CBPeripheral, error: String?) = onLinkLost(p, error, wasConnected = link.state == LinkStateKind.CONNECTED)

    private fun onLinkLost(p: CBPeripheral, error: String?, wasConnected: Boolean) {
        if (p != peripheral) return
        val addr = link.address
        queue.clear()
        rx = null; tx = null
        if (userDisconnect || addr == null) {
            setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null))
            return
        }
        val now = currentTimeMillis()
        if (wasConnected || reconnect.active) {
            if (!reconnect.active) reconnect.start(now)
            val wait = reconnect.nextDelay(now)
            if (wait != null) {
                setLink(link.copy(state = LinkStateKind.CONNECTING, service = null, reason = null))
                reconnectJob = scope.launch {
                    delay(wait)
                    if (reconnect.active) { listener.onInfo("Reconnecting"); cb.connect(p) }
                }
                return
            }
            reconnect.stop()
            val reason = "Link lost and did not come back within 1 minute"
            setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null, reason = reason))
            listener.onError(reason)
            return
        }
        val reason = "Could not connect (${error ?: "no answer"}). Close the maker's app: the vehicle only " +
            "accepts one Bluetooth connection at a time."
        setLink(link.copy(state = LinkStateKind.DISCONNECTED, service = null, reason = reason))
        listener.onError(reason)
    }

    // ---- writes ----

    override fun write(data: ByteArray) {
        if (link.state != LinkStateKind.CONNECTED || peripheral == null || rx == null) {
            listener.onError("Not connected to a vehicle")
            return
        }
        queue.enqueue(data)
        pump()
    }

    private fun pump() {
        queue.takeFinishedEmpty()?.let { listener.onWriteDone(0) }
        val p = peripheral ?: return
        val c = rx ?: return
        val chunk = queue.next() ?: return
        listener.onWriteChunk(chunk)
        p.writeValue(chunk.toNSData(), forCharacteristic = c,
            type = if (withResponse) CBCharacteristicWriteWithResponse else CBCharacteristicWriteWithoutResponse)
        if (!withResponse) {
            // No acknowledgement comes for write-without-response.
            scope.launch { written(true) }
        }
    }

    override fun onWrote(p: CBPeripheral, c: CBCharacteristic, error: String?) {
        if (p != peripheral) return
        written(error == null)
    }

    private fun written(ok: Boolean) {
        when (val r = queue.onWritten(ok)) {
            is WriteQueue.Result.Done -> listener.onWriteDone(r.job.total)
            is WriteQueue.Result.Failed -> listener.onError("Write to vehicle failed")
            WriteQueue.Result.More -> {}
        }
        pump()
    }
}
