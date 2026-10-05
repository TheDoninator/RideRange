package com.elect.riderange.range

import com.elect.riderange.core.Geo
import com.elect.riderange.trips.Sample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Mass found from one trip. [identifiable] = enough climbs/accelerations to tell mass apart from rolling losses. */
data class TripMass(
    val massKg: Double,
    /** 1-sigma statistical uncertainty, kg (samples treated as correlated over ~5 s). */
    val seKg: Double,
    val samples: Int,
    /** Spread (std) of the "mass-sensitive" term g·grade + a, m/s². */
    val spread: Double,
    val identifiable: Boolean,
    /** Effective rolling-resistance coefficient implied by the intercept (sanity check, ~0.01–0.03). */
    val crrImplied: Double,
)

/** All trips combined. */
data class MassEstimate(
    val massKg: Double,
    val seKg: Double,
    val trips: Int,
    val confident: Boolean,
    /** Mass implied by the learned model's uphill coefficient, for a cross-check (null without a learned model). */
    val fromGradeCoefKg: Double? = null,
) {
    val riderPlusCargoKg: Double get() = massKg - MassEstimator.SCOOTER_KG
}

/**
 * Estimates the whole rolling mass (rider + scooter + cargo) from 1 Hz rides with scooter telemetry.
 *
 * Power balance at the wheel for samples where the motor is pulling:
 *   P·eff/v − ½ρ·CdA·v²  =  m·(g·grade + a)  +  m·g·crr
 * fitted as y = m·z + c (z = g·grade + a), with Huber-weighted least squares. The intercept c soaks up rolling
 * resistance (and any constant loss), so on flat steady cruising z barely varies and m is *not* identifiable;
 * hills and accelerations make it so. Drivetrain-efficiency error scales m directly (10 % eff error ≈ 10 % mass).
 */
object MassEstimator {
    /** Max G2 weight; other vehicles pass their own weight. */
    const val SCOOTER_KG = 24.5
    const val LB_PER_KG = 2.20462
    /** z must vary at least this much (≈ ±2 % grade or ±0.2 m/s² of acceleration). */
    const val MIN_SPREAD = 0.18
    const val MIN_SAMPLES = 60
    /** Consecutive 1 Hz samples are strongly correlated; count ~1 independent sample per this many. */
    const val CORRELATION = 5.0

    class Point(val z: Double, val y: Double)

    /** Turns raw samples into fit points (all filtering lives here). */
    fun points(samples: List<Sample>, p: RideParams = RideParams()): List<Point> {
        val n = samples.size
        if (n < 15) return emptyList()
        val v = DoubleArray(n) { samples[it].speed ?: Double.NaN }
        // Smoothed speed: 5-sample centred moving average.
        val vs = DoubleArray(n) { i ->
            var s = 0.0; var k = 0
            for (j in max(0, i - 2)..min(n - 1, i + 2)) if (!v[j].isNaN()) { s += v[j]; k++ }
            if (k >= 3) s / k else Double.NaN
        }
        // Cumulative distance for the grade window.
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distance(samples[i - 1].pos, samples[i].pos).let { d ->
            val dt = (samples[i].t - samples[i - 1].t) / 1000.0
            if (dt > 0 && d / dt < 40) d else 0.0
        }
        val out = ArrayList<Point>()
        val w = 5
        for (i in w until n - w) {
            val s = samples[i]
            val pw = s.powerW ?: continue
            // Steady telemetry: neighbours present, 1 s spacing.
            val dtA = (s.t - samples[i - 1].t) / 1000.0
            val dtB = (samples[i + 1].t - s.t) / 1000.0
            if (dtA !in 0.5..2.0 || dtB !in 0.5..2.0) continue
            if (samples[i - 1].powerW == null || samples[i + 1].powerW == null) continue
            val vv = vs[i]
            if (vv.isNaN() || vv < 3.0 || vs[i - 1].isNaN() || vs[i + 1].isNaN()) continue
            if (pw < 30) continue                                  // motor must be pulling (no coasting/regen)
            // Acceleration from the raw speeds (central difference): smoothing would shrink it and bias m upward.
            if (v[i - 1].isNaN() || v[i + 1].isNaN()) continue
            val a = (v[i + 1] - v[i - 1]) / ((samples[i + 1].t - samples[i - 1].t) / 1000.0)
            if (a < -0.3 || abs(a) > 2.5) continue                 // braking, or glitch
            val e0 = samples[i - w].ele ?: samples[i - w].gpsAlt
            val e1 = samples[i + w].ele ?: samples[i + w].gpsAlt
            val dist = cum[i + w] - cum[i - w]
            if (e0 == null || e1 == null || dist < 15) continue
            val grade = (e1 - e0) / dist
            if (abs(grade) > 0.2) continue
            val y = pw * p.drivetrainEff / vv - 0.5 * p.airDensity * p.cdA * vv * vv
            out += Point(9.81 * grade + a, y)
        }
        return out
    }

    /** Huber-weighted fit of y = m·z + c. Returns null with too few points. */
    fun fitTrip(points: List<Point>): TripMass? {
        if (points.size < 20) return null
        val mz = points.sumOf { it.z } / points.size
        val spread = sqrt(points.sumOf { (it.z - mz) * (it.z - mz) } / points.size)
        var m = 100.0; var c = 15.0
        val w = DoubleArray(points.size) { 1.0 }
        var sigma = 1.0
        repeat(8) {
            var sw = 0.0; var sz = 0.0; var sy = 0.0; var szz = 0.0; var szy = 0.0
            points.forEachIndexed { i, pt -> sw += w[i]; sz += w[i] * pt.z; sy += w[i] * pt.y; szz += w[i] * pt.z * pt.z; szy += w[i] * pt.z * pt.y }
            val det = sw * szz - sz * sz
            if (abs(det) < 1e-9) return null
            m = (sw * szy - sz * sy) / det
            c = (sy - m * sz) / sw
            val res = points.map { it.y - (m * it.z + c) }
            val mad = res.map { abs(it) }.sorted()[res.size / 2]
            sigma = max(1e-6, 1.4826 * mad)
            val k = 1.345 * sigma
            res.forEachIndexed { i, r -> w[i] = if (abs(r) <= k) 1.0 else k / abs(r) }
        }
        val sw = w.sum()
        val zw = points.indices.sumOf { w[it] * points[it].z } / sw
        val sxx = points.indices.sumOf { w[it] * (points[it].z - zw) * (points[it].z - zw) }
        val nEff = max(1.0, points.size / CORRELATION)
        val se = if (sxx <= 0) Double.POSITIVE_INFINITY else sigma / sqrt(sxx) * sqrt(points.size / nEff)
        val identifiable = spread >= MIN_SPREAD && points.size >= MIN_SAMPLES && m > 30 && m < 300 && se / m < 0.15
        return TripMass(m, se, points.size, spread, identifiable, if (m > 0) c / (m * 9.81) else 0.0)
    }

    fun estimateTrip(samples: List<Sample>, p: RideParams = RideParams()): TripMass? = fitTrip(points(samples, p))

    /**
     * Inverse-variance combination of identifiable trips. The uncertainty is the larger of the statistical one and
     * the trip-to-trip scatter (different cargo, wind, tyre pressure). Confident with ≥ 2 trips and ± ≤ 8 %.
     */
    fun combine(trips: List<TripMass>, learned: LearnedModel? = null, p: RideParams = RideParams()): MassEstimate? {
        val ok = trips.filter { it.identifiable && it.seKg.isFinite() && it.seKg > 0 }
        if (ok.isEmpty()) return null
        val wts = ok.map { 1.0 / (it.seKg * it.seKg) }
        val sw = wts.sum()
        val mean = ok.indices.sumOf { wts[it] * ok[it].massKg } / sw
        val stat = 1.0 / sqrt(sw)
        // Trip-to-trip scatter is real variation (cargo, wind, tyres), so it isn't divided down by the trip count.
        val scatter = if (ok.size >= 2) sqrt(ok.indices.sumOf { wts[it] * (ok[it].massKg - mean).let { d -> d * d } } / sw) else 0.0
        val se = max(stat, scatter)
        return MassEstimate(mean, se, ok.size, ok.size >= 2 && se / mean <= 0.08, learned?.let { massFromGradeCoef(it, p) })
    }

    /** Learned uphill cost c (Wh/mi per % grade) = m·g·0.01·1609.344 / 3600 / eff  →  m. */
    fun massFromGradeCoef(learned: LearnedModel, p: RideParams = RideParams()): Double? {
        val c = learned.coef[2]
        if (c <= 0) return null
        return c * 3600.0 * p.drivetrainEff / (9.81 * 0.01 * Geo.M_PER_MI)
    }

    /** Mass the physics model should use. */
    fun effectiveMass(riderLb: Double, cargoKg: Double, estimate: MassEstimate?, useEstimated: Boolean, vehicleKg: Double = SCOOTER_KG): Double =
        if (useEstimated && estimate != null && estimate.confident) estimate.massKg
        else riderLb / LB_PER_KG + vehicleKg + cargoKg
}
