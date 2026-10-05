package com.elect.riderange.range

import com.elect.riderange.core.Geo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import com.elect.riderange.core.format

/** One measured stretch of a real ride: how far, how much energy, at what speed and grade. */
data class MeasuredSegment(
    val lengthM: Double,
    val wh: Double,
    val speedMps: Double,
    val grade: Double,
    val tempC: Double? = null,
) {
    val miles: Double get() = lengthM / Geo.M_PER_MI
    val whPerMi: Double get() = wh / miles
}

/**
 * Fits [LearnedModel] by ridge regression *towards the physics prior*: minimise
 *   Σ wᵢ (yᵢ − xᵢ·β)² + λ Σ (βⱼ − β⁰ⱼ)²
 * with weights wᵢ = miles of segment i and y = Wh/mi. With little data the answer stays close to the prior;
 * with lots of data the data wins. Obvious outliers (GPS jumps, stops) are dropped, then segments with a
 * residual over 3σ are dropped and the fit is repeated once.
 */
object ModelFitter {
    const val MIN_MILES = 20.0
    const val N = 5

    fun features(speedMps: Double, grade: Double, tempC: Double?): DoubleArray {
        val gp = grade * 100
        return doubleArrayOf(
            1.0,
            speedMps * speedMps,
            max(gp, 0.0),
            -max(-gp, 0.0),
            if (tempC == null) 0.0 else max(0.0, 15.0 - tempC),
        )
    }

    fun usable(s: MeasuredSegment): Boolean =
        s.lengthM >= 30 && s.speedMps in 1.5..16.0 && abs(s.grade) <= 0.25 && s.whPerMi in -60.0..150.0

    class Fit(val model: LearnedModel?, val miles: Double, val used: Int, val dropped: Int)

    /**
     * Returns a model only when there are at least [minMiles] of usable data; otherwise [Fit.model] is null
     * and the caller should keep using the default/simple-average model.
     */
    fun fit(
        segments: List<MeasuredSegment>,
        prior: DoubleArray,
        lambda: Double = 2.0,
        minMiles: Double = MIN_MILES,
    ): Fit {
        val good = segments.filter(::usable)
        val miles = good.sumOf { it.miles }
        if (miles < minMiles || good.size < 10) return Fit(null, miles, good.size, segments.size - good.size)
        var beta = solve(good, prior, lambda)
        val sigma = rmse(good, beta)
        val kept = good.filter { abs(it.whPerMi - predict(beta, it)) <= 3 * sigma + 1.0 }
        if (kept.size in 10 until good.size) beta = solve(kept, prior, lambda)
        val keptMiles = kept.sumOf { it.miles }
        return Fit(LearnedModel(beta, keptMiles, rmse(kept, beta)), keptMiles, kept.size, segments.size - kept.size)
    }

    fun predict(beta: DoubleArray, s: MeasuredSegment): Double {
        val x = features(s.speedMps, s.grade, s.tempC)
        return (0 until N).sumOf { beta[it] * x[it] }
    }

    fun rmse(segs: List<MeasuredSegment>, beta: DoubleArray): Double {
        val w = segs.sumOf { it.miles }
        if (w <= 0) return 0.0
        return sqrt(segs.sumOf { it.miles * (it.whPerMi - predict(beta, it)).let { r -> r * r } } / w)
    }

    private fun solve(segs: List<MeasuredSegment>, prior: DoubleArray, lambda: Double): DoubleArray {
        val a = Array(N) { DoubleArray(N) }
        val b = DoubleArray(N)
        for (s in segs) {
            val x = features(s.speedMps, s.grade, s.tempC)
            val w = s.miles
            val y = s.whPerMi
            for (i in 0 until N) {
                b[i] += w * x[i] * y
                for (j in 0 until N) a[i][j] += w * x[i] * x[j]
            }
        }
        // Scale-aware ridge: penalise each coefficient relative to its feature's spread so v² (~60) and
        // grade (~5) are regularised comparably.
        for (i in 0 until N) {
            val l = lambda * max(a[i][i] / max(segs.sumOf { it.miles }, 1e-9), 1e-3)
            a[i][i] += l
            b[i] += l * prior[i]
        }
        return gauss(a, b)
    }

    /** Gaussian elimination with partial pivoting. */
    fun gauss(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        val n = v.size
        val a = Array(n) { m[it].copyOf() }
        val b = v.copyOf()
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (p != c) { val t = a[p]; a[p] = a[c]; a[c] = t; val tb = b[p]; b[p] = b[c]; b[c] = tb }
            val d = a[c][c]
            if (abs(d) < 1e-12) continue
            for (r in c + 1 until n) {
                val f = a[r][c] / d
                if (f == 0.0) continue
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = if (abs(a[r][r]) < 1e-12) 0.0 else s / a[r][r]
        }
        return x
    }
}

/** Predicted vs actual Wh per trip, newest last. */
object Accuracy {
    /** Signed error, % of actual. */
    fun errorPct(predictedWh: Double, actualWh: Double): Double? =
        if (actualWh <= 1.0) null else (predictedWh - actualWh) / actualWh * 100

    /** Mean absolute error over the last [n] trips that have both numbers. */
    fun meanAbsPct(errors: List<Double>, n: Int = 10): Double? {
        val last = errors.takeLast(n)
        return if (last.isEmpty()) null else last.sumOf { abs(it) } / last.size
    }

    fun summary(errors: List<Double>, n: Int = 10): String {
        val m = meanAbsPct(errors, n) ?: return "No measured trips yet: connect the scooter on a ride to learn."
        val k = minOf(n, errors.size)
        return "Range estimates are within ±%.0f%% over your last %d trip%s".format(m, k, if (k == 1) "" else "s")
    }
}
