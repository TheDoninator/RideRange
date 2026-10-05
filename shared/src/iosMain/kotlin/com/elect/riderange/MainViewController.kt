package com.elect.riderange

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.elect.riderange.core.LatLon
import com.elect.riderange.location.IosLocationSource
import com.elect.riderange.map.IosMapBridge
import com.elect.riderange.map.NativeMapFactory
import com.elect.riderange.search.Place
import com.elect.riderange.ui.MainViewModel
import com.elect.riderange.ui.RideRangeRoot
import com.elect.riderange.ui.Tab
import com.elect.riderange.ui.theme.AppTheme
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.VehicleType
import kotlinx.coroutines.delay
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/** The iPhone app's only screen (called from iosApp/iosApp/RideRangeApp.swift). */
fun MainViewController(mapFactory: NativeMapFactory): UIViewController {
    IosMapBridge.factory = mapFactory
    val services = Services.init { IosPlatform() }
    val location = services.location as IosLocationSource
    var askedLocation = false
    return ComposeUIViewController {
        AppTheme {
            val vm = remember { MainViewModel() }
            LaunchedEffect(Unit) { ScreenshotDemo.apply(services, vm) }
            RideRangeRoot(
                vm,
                requestBluetooth = { services.scooter.scan() },
                requestLocation = { askedLocation = true; location.requestPermission() },
                requestNotifications = {},
                requestLocationOnce = { if (!askedLocation) { askedLocation = true; location.requestPermission() } },
            )
        }
    }
}

/**
 * Launch arguments used by CI to take simulator screenshots of each tab (they do nothing unless passed):
 * `-rrDemo` finishes the intro with a Max G2, `-rrTab RIDE|ROUTE|PARKING|RULES|SCOOTER` opens a tab and
 * `-rrDest lat,lon` plans a route there.
 */
object ScreenshotDemo {
    suspend fun apply(s: Services, vm: MainViewModel) {
        val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
        fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        if ("-rrDemo" !in args) return
        if (!s.settingsState.value.onboarded) {
            s.settings.saveVehicle(Vehicle.create(VehicleType.NINEBOT_MAX_G2, "demo-max-g2", nowMs = 0), makeActive = true)
            s.settings.update { it.copy(riderLb = 165.0, onboarded = true, manualBattery = 80.0) }
        }
        arg("-rrTab")?.let { t -> Tab.entries.firstOrNull { it.name == t }?.let { vm.setTab(it) } }
        arg("-rrDest")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { (lat, lon) ->
            // Wait for the first GPS fix so the route starts at the rider.
            repeat(30) { if (s.location.fix.value == null) delay(500) }
            s.nav.setDestination(Place("Demo destination", "Screenshot", LatLon(lat, lon)))
            vm.setTab(Tab.ROUTE)
        }
    }
}
