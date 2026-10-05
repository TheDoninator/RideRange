package com.elect.riderange.vehicle.link

import com.elect.riderange.data.SettingsStore
import com.elect.riderange.scooter.ble.UartLink
import com.elect.riderange.vehicle.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The platform's Bluetooth: permission state and the link implementations the vehicle managers use. */
interface BlePlatform {
    /** Whether the app may scan and connect (Android runtime permissions; iOS Bluetooth authorization). */
    fun permitted(): Boolean

    /** What to tell the rider when [permitted] is false; [what] is "scooter", "vehicle" or "board". */
    fun permissionMessage(what: String): String

    /** A Nordic-UART style link (Ninebot and VESC). */
    fun uartLink(listener: UartLink.Listener): UartLink

    /** The Future Motion Onewheel link (read-only GATT reads and subscriptions). */
    fun onewheel(settings: SettingsStore, scope: CoroutineScope, vehicle: StateFlow<Vehicle?>): VehicleLink
}
