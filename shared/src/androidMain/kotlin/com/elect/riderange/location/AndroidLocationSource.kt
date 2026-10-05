package com.elect.riderange.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform LocationManager (no Google Play services needed): GPS at 1 Hz plus network as a fallback,
 * and the barometer for smooth elevation when the phone has one.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val _fix = MutableStateFlow<Fix?>(null)
    override val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private val _baroAlt = MutableStateFlow<Double?>(null)
    /** Pressure altitude (relative accuracy is good, absolute is not). */
    override val baroAlt: StateFlow<Double?> = _baroAlt.asStateFlow()
    override val hasBarometer: Boolean get() = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    private var running = false
    private var last: Location? = null

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val listener = LocationListener { loc -> onLocation(loc) }

    private fun onLocation(loc: Location) {
        val prev = last
        // Prefer GPS: ignore a coarse network fix if a recent GPS fix is better.
        if (prev != null && loc.provider != LocationManager.GPS_PROVIDER && prev.provider == LocationManager.GPS_PROVIDER &&
            loc.time - prev.time < 10_000 && loc.accuracy > prev.accuracy) return
        val derived = prev?.let {
            val dt = (loc.time - it.time) / 1000.0
            if (dt in 0.5..10.0) Geo.distance(LatLon(it.latitude, it.longitude), LatLon(loc.latitude, loc.longitude)) / dt else null
        }
        // Some sources (mock locations, the emulator) report speed 0 while clearly moving: trust the movement then.
        val speed = if (loc.hasSpeed() && !(loc.speed == 0f && (derived ?: 0.0) > 2.0)) loc.speed.toDouble() else derived
        val bearing = if (loc.hasBearing() && loc.bearing != 0f) loc.bearing.toDouble() else prev?.let {
            if (Geo.distance(LatLon(it.latitude, it.longitude), LatLon(loc.latitude, loc.longitude)) > 3)
                Geo.bearing(LatLon(it.latitude, it.longitude), LatLon(loc.latitude, loc.longitude)) else null
        }
        val vAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null
        last = loc
        _fix.value = Fix(
            LatLon(loc.latitude, loc.longitude), speed, bearing, loc.accuracy.toDouble(),
            if (loc.hasAltitude()) loc.altitude else null, vAcc, com.elect.riderange.core.currentTimeMillis(),
        )
    }

    private val baroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble()
            _baroAlt.value = _baroAlt.value?.let { it + (alt - it) * 0.2 } ?: alt
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running || !hasPermission()) return
        running = true
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
            val known = listOfNotNull(
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            ).maxByOrNull { it.time }
            if (known != null && _fix.value == null) onLocation(known)
        } catch (_: SecurityException) {
            running = false
        } catch (_: IllegalArgumentException) {
        }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(baroListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
        sm.unregisterListener(baroListener)
    }
}
