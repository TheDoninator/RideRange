package com.elect.riderange.trips

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.range.EnergyModel
import com.elect.riderange.range.MeasuredSegment
import com.elect.riderange.range.Segment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One 1 Hz sample of a trip. Scooter fields are null when the scooter isn't connected. */
data class Sample(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val gpsAlt: Double?,
    /** Smoothed/corrected elevation (barometer or filtered GPS; replaced by DEM after the trip when online). */
    val ele: Double?,
    val gpsSpeed: Double?,
    val scooterSpeed: Double?,
    val voltage: Double? = null,
    /** Battery current, A; positive = discharging (confirmed on the real Max G2: +0.11 A at idle). */
    val current: Double? = null,
    val batteryPct: Double? = null,
    val tempC: Double? = null,
    val odometerM: Double? = null,
    val accuracy: Double? = null,
) {
    val powerW: Double? get() = if (voltage != null && current != null) voltage * current else null
    val speed: Double? get() = scooterSpeed ?: gpsSpeed
    val pos: LatLon get() = LatLon(lat, lon)
}

data class TripStats(
    val distanceM: Double,
    val durationS: Double,
    val movingS: Double,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val climbM: Double,
    val descentM: Double,
    /** Net energy from the battery (consumed − regen), Wh; null without scooter data. */
    val whUsed: Double?,
    val regenWh: Double?,
    /** How [whUsed] was measured: "power" (V×I) or "battery" (% drop × pack). */
    val energySource: String?,
    val batteryStart: Double?,
    val batteryEnd: Double?,
    val avgTempC: Double?,
) {
    val miles: Double get() = distanceM / Geo.M_PER_MI
    val whPerMi: Double? get() = whUsed?.let { if (miles > 0.05) it / miles else null }
}

object TripMath {
    /** Fixes further apart than this in one second are GPS jumps (90 mph). */
    private const val MAX_STEP_MPS = 40.0

    fun stepDistance(a: Sample, b: Sample): Double {
        // Odometer is the best distance source when both have it (whole metres).
        if (a.odometerM != null && b.odometerM != null) {
            val d = b.odometerM - a.odometerM
            if (d in 0.0..(MAX_STEP_MPS * max(1.0, (b.t - a.t) / 1000.0))) return d
        }
        val d = Geo.distance(a.pos, b.pos)
        val dt = max(0.5, (b.t - a.t) / 1000.0)
        return if (d / dt > MAX_STEP_MPS) 0.0 else d
    }

    fun stats(samples: List<Sample>, packWh: Double = 551.0): TripStats {
        if (samples.size < 2) return TripStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, null, null, null, null)
        var dist = 0.0; var moving = 0.0; var maxV = 0.0
        var whOut = 0.0; var whIn = 0.0; var powerSecs = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            val dt = (b.t - a.t) / 1000.0
            if (dt <= 0 || dt > 30) continue
            val d = stepDistance(a, b)
            dist += d
            val v = b.speed ?: d / dt
            if (v > 0.7) moving += dt
            maxV = max(maxV, v)
            val p1 = a.powerW; val p2 = b.powerW
            if (p1 != null && p2 != null) {
                val p = (p1 + p2) / 2
                if (p >= 0) whOut += p * dt / 3600 else whIn += -p * dt / 3600
                powerSecs += dt
            }
        }
        val duration = (samples.last().t - samples.first().t) / 1000.0
        val eles = samples.mapNotNull { it.ele ?: it.gpsAlt }
        val (up, down) = EnergyModel.climbDescent(eles)
        val bStart = samples.firstNotNullOfOrNull { it.batteryPct }
        val bEnd = samples.lastOrNull { it.batteryPct != null }?.batteryPct
        // Use V×I when it covers most of the ride, else fall back to the battery % drop.
        val (wh, regen, src) = when {
            powerSecs >= 0.6 * max(1.0, moving) && powerSecs > 30 -> Triple(whOut - whIn, whIn, "power")
            bStart != null && bEnd != null && bStart > bEnd -> Triple((bStart - bEnd) / 100 * packWh * 0.95, null, "battery")
            else -> Triple(null, null, null)
        }
        val temps = samples.mapNotNull { it.tempC }
        return TripStats(
            distanceM = dist, durationS = duration, movingS = moving,
            avgSpeed = if (moving > 0) dist / moving else 0.0, maxSpeed = maxV,
            climbM = up, descentM = down,
            whUsed = wh, regenWh = regen, energySource = src,
            batteryStart = bStart, batteryEnd = bEnd,
            avgTempC = if (temps.isEmpty()) null else temps.average(),
        )
    }

    /**
     * Cuts a trip into ~[stepM] stretches with measured energy (needs V×I samples), for the model fit.
     * Stretches with stops (avg speed < 1.5 m/s) are skipped by the fitter.
     */
    fun measuredSegments(samples: List<Sample>, stepM: Double = 200.0, tempC: Double? = null): List<MeasuredSegment> {
        val out = ArrayList<MeasuredSegment>()
        var d = 0.0; var t = 0.0; var wh = 0.0; var startEle: Double? = null; var ok = true
        var lastEle: Double? = null
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            val dt = (b.t - a.t) / 1000.0
            if (dt <= 0) continue
            if (startEle == null) startEle = a.ele ?: a.gpsAlt
            val p1 = a.powerW; val p2 = b.powerW
            if (p1 == null || p2 == null || dt > 5) ok = false else wh += (p1 + p2) / 2 * dt / 3600
            d += stepDistance(a, b)
            t += dt
            lastEle = b.ele ?: b.gpsAlt ?: lastEle
            if (d >= stepM - 0.5) {
                if (ok && t > 0) {
                    val grade = if (startEle != null && lastEle != null) (lastEle - startEle) / d else 0.0
                    out += MeasuredSegment(d, wh, d / t, grade, tempC ?: b.tempC)
                }
                d = 0.0; t = 0.0; wh = 0.0; ok = true; startEle = lastEle
            }
        }
        return out
    }

    /** The trip as model segments (for "what did the model predict for this ride"). */
    fun modelSegments(samples: List<Sample>, stepM: Double = 200.0, tempC: Double? = null): List<Segment> {
        val out = ArrayList<Segment>()
        var d = 0.0; var t = 0.0; var startEle: Double? = null; var lastEle: Double? = null
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            val dt = (b.t - a.t) / 1000.0
            if (dt <= 0 || dt > 30) continue
            if (startEle == null) startEle = a.ele ?: a.gpsAlt
            d += stepDistance(a, b); t += dt
            lastEle = b.ele ?: b.gpsAlt ?: lastEle
            if (d >= stepM - 0.5 || i == samples.size - 1) {
                if (d > 1 && t > 0) {
                    val grade = if (startEle != null && lastEle != null) ((lastEle - startEle) / d).coerceIn(-0.25, 0.25) else 0.0
                    out += Segment(d, grade, max(1.0, d / t), tempC)
                }
                d = 0.0; t = 0.0; startEle = lastEle
            }
        }
        return out
    }

    /** Typical |grade| of ridden terrain (for the range circles), from recent trips' segments. */
    fun typicalGrade(segments: List<Segment>): Double? {
        val total = segments.sumOf { it.lengthM }
        if (total < 1000) return null
        return segments.sumOf { abs(it.grade) * it.lengthM } / total
    }

    /** Distance-weighted typical riding speed. */
    fun typicalSpeed(segments: List<Segment>): Double? {
        val moving = segments.filter { it.speedMps > 2 }
        val total = moving.sumOf { it.lengthM }
        if (total < 1000) return null
        return moving.sumOf { it.speedMps * it.lengthM } / total
    }
}

/**
 * Auto start/stop: a trip starts after moving faster than 3 mph for 30 s, and ends after 3 minutes stopped
 * (below ~1 mph). Manual start/stop overrides it.
 */
class TripDetector(
    private val startMps: Double = 3 * Geo.MPS_PER_MPH,
    private val startHoldMs: Long = 30_000,
    private val stopMps: Double = 1 * Geo.MPS_PER_MPH,
    private val stopHoldMs: Long = 180_000,
) {
    enum class Event { NONE, START, STOP }

    var recording = false
        private set
    var manual = false
        private set
    private var fastSince: Long? = null
    private var slowSince: Long? = null

    fun manualStart() { recording = true; manual = true; slowSince = null }
    fun manualStop() { recording = false; manual = false; fastSince = null }

    fun onSpeed(speedMps: Double, nowMs: Long): Event {
        if (!recording) {
            if (speedMps > startMps) {
                val since = fastSince ?: nowMs.also { fastSince = it }
                if (nowMs - since >= startHoldMs) {
                    recording = true; fastSince = null; slowSince = null
                    return Event.START
                }
            } else fastSince = null
            return Event.NONE
        }
        if (speedMps < stopMps) {
            val since = slowSince ?: nowMs.also { slowSince = it }
            if (!manual && nowMs - since >= stopHoldMs) {
                recording = false; slowSince = null
                return Event.STOP
            }
        } else slowSince = null
        return Event.NONE
    }

    /** Samples recorded during the start hold, so the first 30 s aren't lost. */
    val startHoldMsValue: Long get() = startHoldMs
}

/**
 * Elevation smoothing during the ride: with a barometer, pressure altitude gives clean relative changes and
 * is slowly pulled toward GPS altitude (complementary filter, ~2 min); without one, GPS altitude is
 * low-pass filtered. After the trip a DEM can replace it ([TripMath] uses whatever is in [Sample.ele]).
 */
class ElevationFilter(private val tauS: Double = 120.0, private val gpsAlpha: Double = 0.15) {
    private var offset: Double? = null
    private var gpsEma: Double? = null
    private var lastT: Long? = null

    fun update(tMs: Long, gpsAlt: Double?, gpsAccuracyV: Double?, baroAlt: Double?): Double? {
        val dt = lastT?.let { (tMs - it) / 1000.0 } ?: 1.0
        lastT = tMs
        if (baroAlt != null) {
            if (gpsAlt != null && (gpsAccuracyV == null || gpsAccuracyV < 30)) {
                val target = gpsAlt - baroAlt
                offset = offset?.let { it + (target - it) * min(1.0, dt / tauS) } ?: target
            }
            return offset?.let { baroAlt + it } ?: baroAlt
        }
        if (gpsAlt == null) return gpsEma
        gpsEma = gpsEma?.let { it + (gpsAlt - it) * gpsAlpha } ?: gpsAlt
        return gpsEma
    }
}

/** Replace elevations with DEM values (Open-Meteo, ~90 m Copernicus DEM) by index, smoothed. */
object DemCorrection {
    /** Indices to sample: at most [max] evenly spread points. */
    fun pickIndices(n: Int, max: Int = 100): List<Int> {
        if (n <= max) return (0 until n).toList()
        return (0 until max).map { (it.toLong() * (n - 1) / (max - 1)).toInt() }.distinct()
    }

    /** Linear interpolation of DEM values at [idx] to every sample, then a 5-point moving average. */
    fun apply(samples: List<Sample>, idx: List<Int>, dem: List<Double>): List<Sample> {
        if (idx.size != dem.size || idx.size < 2) return samples
        val vals = DoubleArray(samples.size)
        var k = 0
        for (i in samples.indices) {
            while (k < idx.size - 2 && idx[k + 1] < i) k++
            val i0 = idx[k]; val i1 = idx[k + 1]
            val f = if (i1 == i0) 0.0 else ((i - i0).toDouble() / (i1 - i0)).coerceIn(0.0, 1.0)
            vals[i] = dem[k] + (dem[k + 1] - dem[k]) * f
        }
        return samples.mapIndexed { i, s ->
            val lo = max(0, i - 2); val hi = min(samples.size - 1, i + 2)
            var sum = 0.0
            for (j in lo..hi) sum += vals[j]
            s.copy(ele = sum / (hi - lo + 1))
        }
    }
}
