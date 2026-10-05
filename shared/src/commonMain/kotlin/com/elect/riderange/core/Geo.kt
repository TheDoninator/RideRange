package com.elect.riderange.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_R = 6_371_000.0
    const val M_PER_MI = 1609.344
    const val M_PER_FT = 0.3048
    const val MPS_PER_MPH = 0.44704

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_R * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing a -> b, degrees 0..360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (deg(atan2(y, x)) + 360) % 360
    }

    /** Point at [distanceM] from [from] in direction [bearingDeg]. */
    fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_R
        val br = rad(bearingDeg)
        val lat1 = rad(from.lat)
        val lon1 = rad(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(deg(lat2), (deg(lon2) + 540) % 360 - 180)
    }

    /** Closed ring approximating a circle (for map fill layers). */
    fun circle(center: LatLon, radiusM: Double, points: Int = 72): List<LatLon> =
        (0..points).map { destination(center, 360.0 * (it % points) / points, radiusM) }

    class Projection(val point: LatLon, val t: Double, val distanceM: Double)

    /** Projects [p] onto segment a-b with a local equirectangular approximation (fine for < a few km). */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Projection {
        val kx = cos(rad(p.lat)) * EARTH_R * PI / 180
        val ky = EARTH_R * PI / 180
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
        val px = ax + t * dx; val py = ay + t * dy
        val pt = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return Projection(pt, t, sqrt(px * px + py * py))
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = ((b - a) % 360 + 540) % 360 - 180
        return d
    }
}

/** Display units. Internally everything is metres, m/s, Wh. */
data class Units(val metric: Boolean = false) {
    fun speed(mps: Double): String = if (metric) "%.0f".format(mps * 3.6) else "%.0f".format(mps / Geo.MPS_PER_MPH)
    val speedUnit: String get() = if (metric) "km/h" else "mph"

    /** A rated top speed given in mph. */
    fun speedOf(mph: Double): String = if (metric) "%.0f km/h".format(mph * 1.609344) else "%.0f mph".format(mph)

    fun weight(kg: Double): String = if (metric) "%.1f kg".format(kg) else "%.0f lb".format(kg * 2.20462)

    fun distance(m: Double): String = if (metric) {
        if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(m / 1000)
    } else {
        val ft = m / Geo.M_PER_FT
        if (ft < 1000) "${(ft / 10).toInt() * 10} ft" else "%.1f mi".format(m / Geo.M_PER_MI)
    }

    /** Long distances (ranges) always in mi/km. */
    fun range(m: Double): String = if (metric) "%.1f km".format(m / 1000) else "%.1f mi".format(m / Geo.M_PER_MI)

    fun height(m: Double): String = if (metric) "${m.toInt()} m" else "${(m / Geo.M_PER_FT).toInt()} ft"

    fun consumption(whPerMi: Double): String = if (metric) "%.1f Wh/km".format(whPerMi / 1.609344) else "%.1f Wh/mi".format(whPerMi)

    /** Spoken distance for TTS. */
    fun spoken(m: Double): String = if (metric) {
        if (m < 1000) "${((m / 50).toInt().coerceAtLeast(1)) * 50} meters" else "%.1f kilometers".format(m / 1000)
    } else {
        val ft = m / Geo.M_PER_FT
        if (ft < 1000) "${((ft / 50).toInt().coerceAtLeast(1)) * 50} feet" else "%.1f miles".format(m / Geo.M_PER_MI)
    }
}
