package com.elect.riderange.range

import com.elect.riderange.core.Geo
import kotlin.math.max
import com.elect.riderange.core.format

/** Range settings (all editable on the Scooter tab). */
data class RangeConfig(
    /** Max G2 pack: 15.3 Ah × 36 V ≈ 551 Wh. */
    val packWh: Double = 551.0,
    val usableFraction: Double = 0.95,
    /** Battery % kept in reserve, not planned with. */
    val reservePct: Double = 10.0,
    /** Default flat-ground consumption before anything is learned. */
    val defaultWhPerMi: Double = 16.0,
    /** Roads wind: straight-line radius = road range / detour factor. */
    val detourFactor: Double = 1.25,
)

data class RangeResult(
    val usableWh: Double,
    val whPerMi: Double,
    /** Road distance the battery covers, metres. */
    val roadRangeM: Double,
    /** Straight-line radius for a one-way trip, metres. */
    val oneWayRadiusM: Double,
    /** Straight-line radius for out-and-back, metres. */
    val roundTripRadiusM: Double,
)

/**
 * Pure range maths.
 *  usable energy = max(0, battery% − reserve%) × pack Wh × usable fraction
 *  road range    = usable energy / Wh per mile
 *  one-way radius = road range / detour factor;  round-trip radius = one-way radius / 2
 * Wh per mile comes from a [ConsumptionModel] at the rider's typical speed over the local terrain's
 * typical grade (half up, half down), so the learned model drives the circles once there is one.
 */
class RangeEstimator(val config: RangeConfig = RangeConfig()) {

    fun usableWh(batteryPct: Double): Double =
        max(0.0, EnergyModel.clampPct(batteryPct) - config.reservePct) / 100.0 * config.packWh * config.usableFraction

    fun estimate(batteryPct: Double, whPerMi: Double): RangeResult {
        val wh = usableWh(batteryPct)
        val c = max(1.0, whPerMi)
        val road = wh / c * Geo.M_PER_MI
        val oneWay = road / max(1.0, config.detourFactor)
        return RangeResult(wh, c, road, oneWay, oneWay / 2)
    }

    fun estimate(batteryPct: Double, model: ConsumptionModel, speedMps: Double, typicalGrade: Double, tempC: Double? = null): RangeResult =
        estimate(batteryPct, EnergyModel.rollingTerrainWhPerMi(model, speedMps, typicalGrade, tempC))

    /** Battery % used by [wh] of energy (relative to the whole pack, as the scooter displays it). */
    fun pctFor(wh: Double): Double = wh / (config.packWh * config.usableFraction) * 100.0

    /** Battery % left after spending [wh] from [batteryPct]. */
    fun pctAfter(batteryPct: Double, wh: Double): Double = batteryPct - pctFor(wh)

    /** Can a trip needing [wh] be done without dipping into the reserve? */
    fun reachable(batteryPct: Double, wh: Double): Boolean = wh <= usableWh(batteryPct)
}

/**
 * Picks the consumption model: the learned one once there are ~20 mi of measured riding, otherwise the
 * physics default calibrated to the measured average (when there is any measured riding) or the setting.
 */
object ModelChooser {
    fun choose(
        config: RangeConfig,
        params: RideParams,
        learned: LearnedModel?,
        measuredAvgWhPerMi: Double?,
        measuredMiles: Double,
    ): ConsumptionModel = when {
        learned != null && learned.miles >= ModelFitter.MIN_MILES -> learned
        measuredAvgWhPerMi != null && measuredMiles >= 3.0 ->
            PhysicsModel(params, measuredAvgWhPerMi.coerceIn(6.0, 60.0), "Average of %.0f mi".format(measuredMiles))
        else -> PhysicsModel(params, config.defaultWhPerMi)
    }
}
