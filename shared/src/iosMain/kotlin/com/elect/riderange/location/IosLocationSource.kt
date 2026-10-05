package com.elect.riderange.location

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.currentTimeMillis
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.CoreMotion.CMAltimeter
import platform.Foundation.NSError
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObject
import kotlin.math.pow

/**
 * CoreLocation at full GPS accuracy (about 1 Hz) plus the barometer (CMAltimeter pressure, converted with the same
 * standard-atmosphere formula Android's SensorManager.getAltitude uses). [setBackground] keeps updates (and so the
 * app) running with the screen off while a ride is recorded, navigation is on or a vehicle is connected; iOS shows
 * the blue location indicator then.
 */
class IosLocationSource : LocationSource {
    private val manager = CLLocationManager()
    private val altimeter by lazy { CMAltimeter() }
    private val _fix = MutableStateFlow<Fix?>(null)
    override val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private val _baroAlt = MutableStateFlow<Double?>(null)
    override val baroAlt: StateFlow<Double?> = _baroAlt.asStateFlow()
    override val hasBarometer: Boolean get() = CMAltimeter.isRelativeAltitudeAvailable()
    private var running = false
    private var baroRunning = false
    private var last: Fix? = null
    private var wantStart = false

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            (didUpdateLocations.lastOrNull() as? CLLocation)?.let { onLocation(it) }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (hasPermission() && (wantStart || !running)) start()
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {}
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = platform.CoreLocation.CLActivityType.CLActivityTypeOtherNavigation
    }

    override fun hasPermission(): Boolean {
        val s = manager.authorizationStatus
        return s == kCLAuthorizationStatusAuthorizedWhenInUse || s == kCLAuthorizationStatusAuthorizedAlways
    }

    /** Shows the "Allow While Using App" prompt (once; later changes happen in Settings). */
    fun requestPermission() {
        wantStart = true
        if (hasPermission()) start() else manager.requestWhenInUseAuthorization()
    }

    private fun onLocation(loc: CLLocation) {
        if (loc.horizontalAccuracy < 0) return
        val pos = loc.coordinate.useContents { LatLon(latitude, longitude) }
        val prev = last
        val now = currentTimeMillis()
        val derived = prev?.let {
            val dt = (now - it.timeMs) / 1000.0
            if (dt in 0.5..10.0) Geo.distance(it.pos, pos) / dt else null
        }
        val speed = if (loc.speed >= 0 && !(loc.speed == 0.0 && (derived ?: 0.0) > 2.0)) loc.speed else derived
        val bearing = if (loc.course > 0) loc.course else prev?.let {
            if (Geo.distance(it.pos, pos) > 3) Geo.bearing(it.pos, pos) else null
        }
        val f = Fix(pos, speed, bearing, loc.horizontalAccuracy, if (loc.verticalAccuracy >= 0) loc.altitude else null,
            loc.verticalAccuracy.takeIf { it > 0 }, now)
        last = f
        _fix.value = f
    }

    override fun start() {
        if (!hasPermission()) return
        if (!running) {
            running = true
            manager.startUpdatingLocation()
            manager.location?.let { if (_fix.value == null) onLocation(it) }
        }
        if (!baroRunning && CMAltimeter.isRelativeAltitudeAvailable()) {
            baroRunning = true
            altimeter.startRelativeAltitudeUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
                val kPa = data?.pressure?.doubleValue ?: return@startRelativeAltitudeUpdatesToQueue
                val alt = 44330.0 * (1.0 - (kPa * 10.0 / 1013.25).pow(1.0 / 5.255))
                _baroAlt.value = _baroAlt.value?.let { it + (alt - it) * 0.2 } ?: alt
            }
        }
    }

    override fun stop() {
        if (running) manager.stopUpdatingLocation()
        running = false
        if (baroRunning) altimeter.stopRelativeAltitudeUpdates()
        baroRunning = false
    }

    /** Keep updating with the screen off (needs UIBackgroundModes "location", set in Info.plist). */
    fun setBackground(on: Boolean) {
        if (on && !hasPermission()) return
        manager.allowsBackgroundLocationUpdates = on
        manager.pausesLocationUpdatesAutomatically = !on
        manager.showsBackgroundLocationIndicator = on
        if (on) start()
    }
}
