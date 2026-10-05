package com.elect.riderange.range

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.elect.riderange.core.format

/** Physical constants of rider + Max G2. All editable later; defaults are typical for an adult rider. */
data class RideParams(
    /** Scooter (24.5 kg) + rider (~165 lb / 75 kg, an average adult) + small bag. */
    val massKg: Double = 101.0,
    val crr: Double = 0.015,
    val cdA: Double = 0.55,
    val airDensity: Double = 1.15,
    /** Battery -> wheel efficiency (motor, controller, wiring). */
    val drivetrainEff: Double = 0.80,
    /** Share of the potential energy that regen braking puts back in the battery on descents. */
    val regenRecovery: Double = 0.30,
    /** Typical cruise speed, m/s (8.0 = 17.9 mph). */
    val cruiseMps: Double = 8.0,
)

/** Consumption in Wh per mile at a steady speed on a constant grade (grade as a fraction, 0.05 = 5 %). */
interface ConsumptionModel {
    val label: String
    fun whPerMi(speedMps: Double, grade: Double, tempC: Double? = null): Double
}

/**
 * Simple physics: rolling resistance + aero + climbing, divided by drivetrain efficiency; on net-downhill
 * segments only [RideParams.regenRecovery] of the surplus comes back. The whole curve is scaled so that the
 * flat-ground value at cruise speed equals [flatWhPerMi] (the user's setting or their measured average),
 * so the physics only supplies the *shape* (how speed and hills change things).
 */
class PhysicsModel(
    val params: RideParams = RideParams(),
    val flatWhPerMi: Double = 16.0,
    override val label: String = "Default model",
) : ConsumptionModel {
    private val g = 9.81

    /** Uncalibrated Wh per mile. */
    fun raw(speedMps: Double, grade: Double): Double {
        val v = max(0.5, speedMps)
        val gr = grade.coerceIn(-0.3, 0.3)
        val forceN = params.massKg * g * (params.crr + gr) + 0.5 * params.airDensity * params.cdA * v * v
        val wheelWh = forceN * Geo.M_PER_MI / 3600.0
        return if (wheelWh >= 0) wheelWh / params.drivetrainEff else wheelWh * params.regenRecovery
    }

    private val scale: Double = flatWhPerMi / raw(params.cruiseMps, 0.0)

    override fun whPerMi(speedMps: Double, grade: Double, tempC: Double?): Double =
        scale * raw(speedMps, grade) * coldFactor(tempC)

    /** Lithium packs lose roughly 1 % usable energy per °C below 15 °C. */
    private fun coldFactor(tempC: Double?): Double = if (tempC == null || tempC >= 15) 1.0 else 1.0 + 0.01 * (15 - tempC)

    /** Prior coefficients for [LearnedModel] equivalent to this physics model (used as the regulariser). */
    fun priorCoefficients(): DoubleArray {
        val a = scale * params.massKg * g * params.crr * Geo.M_PER_MI / 3600.0 / params.drivetrainEff
        val b = scale * 0.5 * params.airDensity * params.cdA * Geo.M_PER_MI / 3600.0 / params.drivetrainEff
        val c = scale * params.massKg * g * 0.01 * Geo.M_PER_MI / 3600.0 / params.drivetrainEff
        val d = scale * params.massKg * g * 0.01 * Geo.M_PER_MI / 3600.0 * params.regenRecovery
        return doubleArrayOf(a, b, c, d, 0.01 * flatWhPerMi)
    }
}

/**
 * Learned consumption: Wh/mi = a + b·v² + c·grade⁺(%) − d·grade⁻(%) + e·max(0, 15 °C − T).
 * v in m/s. Fitted by [ModelFitter].
 */
class LearnedModel(
    val coef: DoubleArray,
    /** Miles of riding the fit is based on. */
    val miles: Double,
    /** Weighted RMS error of the fit, Wh/mi. */
    val rmse: Double,
    override val label: String = "Learned from %.0f mi".format(miles),
) : ConsumptionModel {
    init { require(coef.size == 5) }

    override fun whPerMi(speedMps: Double, grade: Double, tempC: Double?): Double {
        val x = ModelFitter.features(speedMps, grade, tempC)
        var y = 0.0
        for (i in coef.indices) y += coef[i] * x[i]
        // Never let a descent produce more than the physics allows (regen can't beat ~1/3 of the drop).
        return max(y, -40.0)
    }
}

/** One stretch of route/track with roughly constant speed and grade. */
data class Segment(val lengthM: Double, val grade: Double, val speedMps: Double, val tempC: Double? = null)

object EnergyModel {
    /** Extra Wh per full stop/turn (re-accelerating to cruise speed, minus what regen returns). */
    fun stopWh(params: RideParams, speedMps: Double = params.cruiseMps): Double {
        val ke = 0.5 * params.massKg * speedMps * speedMps / 3600.0
        return ke / params.drivetrainEff - ke * params.regenRecovery
    }

    fun segmentsWh(segments: List<Segment>, model: ConsumptionModel): Double =
        segments.sumOf { model.whPerMi(it.speedMps, it.grade, it.tempC) * it.lengthM / Geo.M_PER_MI }

    /**
     * Splits a polyline with elevations into ~[stepM] segments. Elevation is smoothed by measuring the grade
     * over whole segments (not point to point), which removes most DEM/GPS noise. Grade is clamped to ±25 %.
     */
    fun segmentsOf(points: List<LatLon>, elevations: List<Double?>, speedMps: Double, stepM: Double = 100.0, tempC: Double? = null): List<Segment> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Segment>()
        var acc = 0.0
        var startEle = elevations.getOrNull(0)
        var lastEle = startEle
        for (i in 1 until points.size) {
            acc += Geo.distance(points[i - 1], points[i])
            elevations.getOrNull(i)?.let { lastEle = it }
            if (startEle == null) startEle = lastEle
            if (acc >= stepM - 0.5 || i == points.size - 1) {
                if (acc > 0.5) {
                    val dh = if (startEle != null && lastEle != null) lastEle!! - startEle!! else 0.0
                    out += Segment(acc, (dh / acc).coerceIn(-0.25, 0.25), speedMps, tempC)
                }
                acc = 0.0
                startEle = lastEle
            }
        }
        return out
    }

    /** Route energy: segments + a stop penalty per real turn. */
    fun routeWh(points: List<LatLon>, elevations: List<Double?>, turns: Int, model: ConsumptionModel, params: RideParams): Double {
        val segs = segmentsOf(points, elevations, params.cruiseMps)
        return segmentsWh(segs, model) + turns * stopWh(params)
    }

    /** Sum of positive / negative elevation changes after a light smoothing (ignores < 2 m wiggles). */
    fun climbDescent(elevations: List<Double>, threshold: Double = 2.0): Pair<Double, Double> {
        if (elevations.isEmpty()) return 0.0 to 0.0
        var up = 0.0; var down = 0.0
        var ref = elevations[0]
        for (e in elevations) {
            val d = e - ref
            if (abs(d) >= threshold) {
                if (d > 0) up += d else down -= d
                ref = e
            }
        }
        return up to down
    }

    /** Average Wh/mi over rolling terrain with typical |grade| [g]: half the time up, half down. */
    fun rollingTerrainWhPerMi(model: ConsumptionModel, speedMps: Double, g: Double, tempC: Double? = null): Double =
        if (g <= 0.0) model.whPerMi(speedMps, 0.0, tempC)
        else (model.whPerMi(speedMps, g, tempC) + model.whPerMi(speedMps, -g, tempC)) / 2

    fun clampPct(v: Double) = min(100.0, max(0.0, v))
}
