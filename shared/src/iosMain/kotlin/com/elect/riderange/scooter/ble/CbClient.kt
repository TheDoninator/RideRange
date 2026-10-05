package com.elect.riderange.scooter.ble

import com.elect.riderange.core.toByteArray
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/** CoreBluetooth UUID as RideRange's lower-case 128-bit string ("2902" -> "00002902-0000-1000-8000-00805f9b34fb"). */
fun CBUUID.full(): String {
    val s = UUIDString.lowercase()
    return when (s.length) {
        4 -> "0000$s-0000-1000-8000-00805f9b34fb"
        8 -> "$s-0000-1000-8000-00805f9b34fb"
        else -> s
    }
}

fun bluetoothDenied(): Boolean {
    val a = CBManager.authorization
    return a == CBManagerAuthorizationDenied || a == CBManagerAuthorizationRestricted
}

/**
 * One CBCentralManager (main queue) with its delegates, turned into plain callbacks. Devices are addressed by their
 * CoreBluetooth identifier (iPhones don't expose MAC addresses), upper-case like Android addresses.
 */
class CbClient(private val events: Events) {
    interface Events {
        fun onPoweredOn() {}
        fun onDiscovered(p: CBPeripheral, name: String?, serviceUuids: List<String>, rssi: Int) {}
        fun onConnected(p: CBPeripheral) {}
        fun onConnectFailed(p: CBPeripheral, error: String?) {}
        fun onDisconnected(p: CBPeripheral, error: String?) {}
        /** All services and characteristics discovered: service UUID -> its characteristics. */
        fun onServices(p: CBPeripheral, services: Map<String, List<CBCharacteristic>>) {}
        fun onValue(p: CBPeripheral, c: CBCharacteristic, value: ByteArray?, error: String?) {}
        fun onWrote(p: CBPeripheral, c: CBCharacteristic, error: String?) {}
        fun onNotifying(p: CBPeripheral, c: CBCharacteristic, error: String?) {}
    }

    private val pendingWhenOn = ArrayList<() -> Unit>()
    private val known = HashMap<String, CBPeripheral>()
    private var servicesLeft = 0
    private val found = LinkedHashMap<String, List<CBCharacteristic>>()

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                val todo = pendingWhenOn.toList(); pendingWhenOn.clear()
                todo.forEach { it() }
                events.onPoweredOn()
            }
        }

        override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
            val p = didDiscoverPeripheral
            known[address(p)] = p
            val name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?: p.name
            val uuids = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)?.mapNotNull { (it as? CBUUID)?.full() } ?: emptyList()
            events.onDiscovered(p, name, uuids, RSSI.intValue)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.delegate = peripheralDelegate
            events.onConnected(didConnectPeripheral)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            events.onConnectFailed(didFailToConnectPeripheral, error?.localizedDescription)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            events.onDisconnected(didDisconnectPeripheral, error?.localizedDescription)
        }
    }

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val services = peripheral.services?.mapNotNull { it as? CBService } ?: emptyList()
            found.clear()
            servicesLeft = services.size
            if (services.isEmpty()) { events.onServices(peripheral, emptyMap()); return }
            services.forEach { peripheral.discoverCharacteristics(null, forService = it) }
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            found[didDiscoverCharacteristicsForService.UUID.full()] =
                didDiscoverCharacteristicsForService.characteristics?.mapNotNull { it as? CBCharacteristic } ?: emptyList()
            servicesLeft--
            if (servicesLeft <= 0) events.onServices(peripheral, found.toMap())
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            events.onValue(peripheral, didUpdateValueForCharacteristic, didUpdateValueForCharacteristic.value?.toByteArray(), error?.localizedDescription)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            events.onWrote(peripheral, didWriteValueForCharacteristic, error?.localizedDescription)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            events.onNotifying(peripheral, didUpdateNotificationStateForCharacteristic, error?.localizedDescription)
        }
    }

    /** Created on first use: that's when iOS asks for Bluetooth permission. */
    val central: CBCentralManager by lazy { CBCentralManager(delegate = centralDelegate, queue = null) }

    val unsupported: Boolean get() = central.state == CBManagerStateUnsupported
    val poweredOn: Boolean get() = central.state == CBManagerStatePoweredOn

    /** Runs [action] now if Bluetooth is on, else as soon as it is (the first state update after creation). */
    fun whenOn(action: () -> Unit) {
        if (poweredOn) action() else pendingWhenOn += action
    }

    fun address(p: CBPeripheral): String = p.identifier.UUIDString.uppercase()

    fun scan() = central.scanForPeripheralsWithServices(null, options = mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to false))

    fun stopScan() { if (poweredOn) central.stopScan() }

    /** A peripheral seen in a scan, or one iOS remembers by identifier. */
    fun peripheral(address: String): CBPeripheral? {
        known[address.uppercase()]?.let { return it }
        // Android-style MAC addresses (a vehicle set up on another phone) can't be looked up on iOS.
        if (!Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$").matches(address)) return null
        val uuid = NSUUID(uUIDString = address)
        val p = central.retrievePeripheralsWithIdentifiers(listOf(uuid)).firstOrNull() as? CBPeripheral ?: return null
        known[address.uppercase()] = p
        return p
    }

    fun connect(p: CBPeripheral) {
        p.delegate = peripheralDelegate
        central.connectPeripheral(p, options = null)
    }

    fun cancel(p: CBPeripheral) = central.cancelPeripheralConnection(p)

    fun discover(p: CBPeripheral) = p.discoverServices(null)
}
