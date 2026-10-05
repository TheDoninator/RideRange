package com.elect.riderange.scooter.ble

/**
 * A BLE "UART" pipe (Nordic UART or the Ninebot variant) to one vehicle: scan, connect, write raw bytes, and
 * notifications back through [Listener]. Android: ScooterLink (GATT on a handler thread); iOS: CoreBluetooth.
 * Addresses are what the platform identifies a device by: a MAC address on Android, a CoreBluetooth identifier
 * (UUID string) on iOS.
 */
interface UartLink {
    interface Listener {
        fun onLink(link: LinkSnapshot)
        fun onNotify(data: ByteArray)
        fun onScanResult(name: String?, address: String, rssi: Int, ninebot: Boolean)
        fun onScanDone()
        fun onWriteDone(n: Int)
        fun onWriteChunk(data: ByteArray)
        fun onError(message: String)
        fun onInfo(message: String)
    }

    val bluetoothAvailable: Boolean
    fun startScan(seconds: Double)
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun write(data: ByteArray)
    fun shutdown()
}
