package com.elect.riderange.location

import com.elect.riderange.core.LatLon
import kotlinx.coroutines.flow.StateFlow

data class Fix(
    val pos: LatLon,
    val speedMps: Double?,
    val bearing: Double?,
    val accuracy: Double,
    val altitude: Double?,
    val vAccuracy: Double?,
    val timeMs: Long,
)

/** GPS (1 Hz while riding) plus a barometric altitude when the phone has a barometer. */
interface LocationSource {
    val fix: StateFlow<Fix?>
    /** Pressure altitude (relative accuracy is good, absolute is not). */
    val baroAlt: StateFlow<Double?>
    val hasBarometer: Boolean
    fun hasPermission(): Boolean
    fun start()
    fun stop()
}
