package com.elect.riderange.vehicle

import com.elect.riderange.range.RangeConfig
import com.elect.riderange.range.RideParams

/** What kind of device this is, for the law (Rules tab), routing and the physics model. */
enum class VehicleClass(val label: String) {
    KICK_SCOOTER("E-scooter"),
    ONEWHEEL("One-wheel board"),
    E_SKATEBOARD("Electric skateboard"),
    E_BIKE("E-bike"),
    OTHER("Other / manual"),
}

/** How RideRange talks to it. Every link is read-only. */
enum class LinkKind { NINEBOT, FUTURE_MOTION, VESC, NONE }

/**
 * Factory numbers for a model. Everything is copied into the [Vehicle] and stays editable.
 * [crr]/[cdA]/[cruiseMps]/[regenRecovery] are physics-model defaults for the vehicle class.
 */
data class VehiclePreset(
    val packWh: Double,
    val usableFraction: Double = 0.95,
    val weightKg: Double,
    val topSpeedMph: Double,
    val ratedRangeMi: Double,
    /** Flat-ground starting consumption before anything is learned. */
    val defaultWhPerMi: Double,
    val crr: Double = 0.015,
    val cdA: Double = 0.55,
    val cruiseMps: Double = 8.0,
    val regenRecovery: Double = 0.30,
    /** Tyre outside diameter, mm (Onewheel / VESC speed from wheel RPM). */
    val wheelDiameterMm: Double? = null,
    /** VESC only: motor pole pairs (ERPM -> wheel RPM) and battery cells in series (voltage -> %). */
    val motorPolePairs: Int? = null,
    val cellsSeries: Int? = null,
    /** VESC only: motor turns per wheel turn (belt drive / geared hub); 1 for a direct-drive hub motor. */
    val gearRatio: Double? = null,
    /** Motor controllers a typical build has (dual-motor boards: 2, the second one read over CAN). */
    val motors: Int = 1,
)

// Onewheel physics defaults: the 11" pneumatic tyre at low pressure on mixed surfaces rolls much harder than a
// scooter's 10" tyres (crr ~0.025), the rider stands sideways and upright (CdA ~0.6 m²), and the boards are ridden
// slower (cruise ~12 mph = 5.4 m/s). The hub motor regenerates strongly on every descent and brake (~35 %).
private const val OW_CRR = 0.025
private const val OW_CDA = 0.60
private const val OW_CRUISE = 5.4
private const val OW_REGEN = 0.35
/** Onewheel tyres are ~11" across; pOnewheel (MIT) converts RPM with a 35" circumference (= 283 mm diameter). */
private const val OW_WHEEL_MM = 283.0

/**
 * Supported models. Battery capacities: Future Motion rarely prints Wh, so the figures are the ones retailers and
 * the airline-travel guides quote (sources per line). Ratings are the manufacturer's; all values are editable.
 */
enum class VehicleType(val label: String, val vehicleClass: VehicleClass, val link: LinkKind, val preset: VehiclePreset) {
    // Segway-Ninebot Max G2: 15.3 Ah × 36 V = 551 Wh, 24.5 kg, 22 mph (US 20), 43 mi rated (segway.com Max G2 spec page).
    NINEBOT_MAX_G2("Segway-Ninebot Max G2", VehicleClass.KICK_SCOOTER, LinkKind.NINEBOT,
        VehiclePreset(packWh = 551.0, weightKg = 24.5, topSpeedMph = 22.0, ratedRangeMi = 43.0, defaultWhPerMi = 16.0)),
    // Any other Ninebot/Segway with the same BLE protocol: G30 Max-like defaults (551 Wh, 19 kg), edit to match.
    NINEBOT_OTHER("Other Segway-Ninebot scooter", VehicleClass.KICK_SCOOTER, LinkKind.NINEBOT,
        VehiclePreset(packWh = 551.0, weightKg = 19.1, topSpeedMph = 18.6, ratedRangeMi = 40.0, defaultWhPerMi = 16.0)),
    // Onewheel+ / V1 (2016-17): 130 Wh, 11.3 kg, 19 mph, 5-7 mi (Future Motion spec sheet as quoted by fallman.tech).
    ONEWHEEL_PLUS("Onewheel+ / V1", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 130.0, usableFraction = 0.9, weightKg = 11.3, topSpeedMph = 19.0, ratedRangeMi = 6.0, defaultWhPerMi = 19.5,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // Pint: 148 Wh (onewheelutah.substack.com "How to fly with a Onewheel"), 10.4 kg, 16 mph, 6-8 mi (onewheel.com).
    ONEWHEEL_PINT("Onewheel Pint", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 148.0, usableFraction = 0.9, weightKg = 10.4, topSpeedMph = 16.0, ratedRangeMi = 7.0, defaultWhPerMi = 19.0,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // Pint X: 324 Wh (onewheelutah.substack.com), 12.2 kg, 18 mph, 12-18 mi (trailwheel.com Pint X specifications).
    ONEWHEEL_PINT_X("Onewheel Pint X", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 324.0, usableFraction = 0.9, weightKg = 12.2, topSpeedMph = 18.0, ratedRangeMi = 15.0, defaultWhPerMi = 19.5,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // Pint S (2025): retailers disagree (180 / 324 / 550 Wh). 324 Wh, 12.2 kg, 20 mph, 12-18 mi per radmotousa.com;
    // check your board's label and edit.
    ONEWHEEL_PINT_S("Onewheel Pint S", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 324.0, usableFraction = 0.9, weightKg = 12.2, topSpeedMph = 20.0, ratedRangeMi = 15.0, defaultWhPerMi = 19.5,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // XR / XR+: 324 Wh 63 V (fallman.tech XR specs), 12.5 kg, 19 mph, 12-18 mi.
    ONEWHEEL_XR("Onewheel XR", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 324.0, usableFraction = 0.9, weightKg = 12.5, topSpeedMph = 19.0, ratedRangeMi = 15.0, defaultWhPerMi = 19.5,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // XR Classic (2023 re-release): 324 Wh, 13.6 kg, 20 mph, 17-24 mi (ev.motorwatt.com XR Classic).
    ONEWHEEL_XR_CLASSIC("Onewheel XR Classic", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 324.0, usableFraction = 0.9, weightKg = 13.6, topSpeedMph = 20.0, ratedRangeMi = 20.0, defaultWhPerMi = 16.0,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // GT: 525 Wh (onewheelutah.substack.com), 15.9 kg, 20 mph, 20-32 mi (trailwheel.com GT vs GT S-Series).
    ONEWHEEL_GT("Onewheel GT", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 525.0, usableFraction = 0.9, weightKg = 15.9, topSpeedMph = 20.0, ratedRangeMi = 26.0, defaultWhPerMi = 18.0,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // GT S-Series: no official Wh; e-craft.at lists 4.6 Ah at 113 V max (~97 V nominal ≈ 450 Wh). 15 kg, 25 mph,
    // 16-25 mi (trailwheel.com).
    ONEWHEEL_GT_S("Onewheel GT-S", VehicleClass.ONEWHEEL, LinkKind.FUTURE_MOTION,
        VehiclePreset(packWh = 450.0, usableFraction = 0.9, weightKg = 15.0, topSpeedMph = 25.0, ratedRangeMi = 20.0, defaultWhPerMi = 20.0,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM)),
    // VESC board (Floatwheel-type or converted Onewheel): defaults for a 20s2p 21700 pack (~72 V nominal, 8 Ah = 576 Wh),
    // 15 pole-pair hub motor, 11" tyre. Edit cells / pack to match the build.
    VESC_BOARD("VESC board (Float package)", VehicleClass.ONEWHEEL, LinkKind.VESC,
        VehiclePreset(packWh = 576.0, usableFraction = 0.9, weightKg = 16.0, topSpeedMph = 22.0, ratedRangeMi = 28.0, defaultWhPerMi = 19.0,
            crr = OW_CRR, cdA = OW_CDA, cruiseMps = OW_CRUISE, regenRecovery = OW_REGEN, wheelDiameterMm = OW_WHEEL_MM,
            motorPolePairs = 15, cellsSeries = 20, gearRatio = 1.0)),
    // ---- Other VESC builds (1.2.0). No single product is behind these, so the numbers are a typical DIY build
    // (stated per line, all editable) and the physics constants follow the vehicle shape. Speed and distance prefer
    // the controller's own COMM_GET_VALUES_SETUP figures (its configured wheel and gearing) when it answers that.
    // VESC e-scooter: 16s (59 V nominal) 15 Ah = 888 Wh, 30 kg, 10" tyres (254 mm) on a 15 pole-pair hub motor
    // (30 magnets, the common 10" scooter hub); upright rider like any kick scooter (CdA 0.55), cruise ~20 mph.
    VESC_SCOOTER("VESC e-scooter", VehicleClass.KICK_SCOOTER, LinkKind.VESC,
        VehiclePreset(packWh = 888.0, weightKg = 30.0, topSpeedMph = 35.0, ratedRangeMi = 40.0, defaultWhPerMi = 25.0,
            crr = 0.015, cdA = 0.55, cruiseMps = 9.0, regenRecovery = 0.30, wheelDiameterMm = 254.0,
            motorPolePairs = 15, cellsSeries = 16, gearRatio = 1.0)),
    // VESC e-bike: 14s (52 V) 17.5 Ah = 907 Wh, 28 kg, 27.5" x 2.4 tyre (~700 mm), direct-drive hub (23 pole pairs).
    // Bicycle tyres roll easily (crr ~0.008) and the rider sits (CdA ~0.5); little regen. A geared hub or mid-drive
    // needs its gear ratio (or the controller's own speed settings).
    VESC_EBIKE("VESC e-bike", VehicleClass.E_BIKE, LinkKind.VESC,
        VehiclePreset(packWh = 907.0, weightKg = 28.0, topSpeedMph = 28.0, ratedRangeMi = 40.0, defaultWhPerMi = 20.0,
            crr = 0.008, cdA = 0.50, cruiseMps = 8.0, regenRecovery = 0.10, wheelDiameterMm = 700.0,
            motorPolePairs = 23, cellsSeries = 14, gearRatio = 1.0)),
    // VESC e-skateboard, one motor: 10s3p 21700 (36 V x 12 Ah = 432 Wh), 8 kg, 90 mm urethane wheels (crr ~0.02 on
    // asphalt), 14-pole outrunner (7 pole pairs) on a 16:36 belt (2.25), standing rider (CdA 0.6), cruise ~14 mph.
    VESC_ESKATE("VESC e-skateboard (single motor)", VehicleClass.E_SKATEBOARD, LinkKind.VESC,
        VehiclePreset(packWh = 432.0, usableFraction = 0.9, weightKg = 8.0, topSpeedMph = 22.0, ratedRangeMi = 16.0, defaultWhPerMi = 14.0,
            crr = 0.020, cdA = 0.60, cruiseMps = 6.3, regenRecovery = 0.35, wheelDiameterMm = 90.0,
            motorPolePairs = 7, cellsSeries = 10, gearRatio = 2.25)),
    // VESC e-skateboard, two motors (two controllers on CAN): 12s4p 21700 (44 V x 16 Ah = 710 Wh), 11 kg, 97 mm wheels,
    // same drivetrain per side. Power is the sum of both controllers.
    VESC_ESKATE_DUAL("VESC e-skateboard (dual motor)", VehicleClass.E_SKATEBOARD, LinkKind.VESC,
        VehiclePreset(packWh = 710.0, usableFraction = 0.9, weightKg = 11.0, topSpeedMph = 28.0, ratedRangeMi = 22.0, defaultWhPerMi = 16.0,
            crr = 0.020, cdA = 0.60, cruiseMps = 7.0, regenRecovery = 0.35, wheelDiameterMm = 97.0,
            motorPolePairs = 7, cellsSeries = 12, gearRatio = 2.25, motors = 2)),
    // Anything else: no connection, GPS speed and the battery slider.
    GENERIC("Other vehicle (manual)", VehicleClass.OTHER, LinkKind.NONE,
        VehiclePreset(packWh = 500.0, weightKg = 20.0, topSpeedMph = 20.0, ratedRangeMi = 25.0, defaultWhPerMi = 18.0));

    val isOnewheel: Boolean get() = vehicleClass == VehicleClass.ONEWHEEL

    companion object {
        fun of(name: String?): VehicleType? = entries.firstOrNull { it.name == name }
    }
}

/** A learned consumption model as stored with its vehicle. */
data class ModelSnapshot(val coef: DoubleArray, val miles: Double, val rmse: Double, val updatedMs: Long) {
    override fun equals(other: Any?): Boolean = other is ModelSnapshot && coef.contentEquals(other.coef) && miles == other.miles &&
        rmse == other.rmse && updatedMs == other.updatedMs
    override fun hashCode(): Int = coef.contentHashCode() * 31 + miles.hashCode()
}

/**
 * One vehicle in the garage. Range settings, BLE address/key and the learned consumption model belong to the
 * vehicle; trips are tagged with [id].
 */
data class Vehicle(
    val id: String,
    val name: String,
    val type: VehicleType,
    val packWh: Double = type.preset.packWh,
    val usableFraction: Double = type.preset.usableFraction,
    val reservePct: Double = 10.0,
    val defaultWhPerMi: Double = type.preset.defaultWhPerMi,
    val detourFactor: Double = 1.25,
    val weightKg: Double = type.preset.weightKg,
    val topSpeedMph: Double = type.preset.topSpeedMph,
    val ratedRangeMi: Double = type.preset.ratedRangeMi,
    val bleAddress: String? = null,
    val bleName: String? = null,
    /** Ninebot: a key pasted from Ninebot Bridge, used (and cleared) on the next connection. */
    val pastedKeyHex: String? = null,
    val wheelDiameterMm: Double? = type.preset.wheelDiameterMm,
    val motorPolePairs: Int? = type.preset.motorPolePairs,
    val cellsSeries: Int? = type.preset.cellsSeries,
    val gearRatio: Double? = type.preset.gearRatio,
    val model: ModelSnapshot? = null,
    val createdMs: Long = 0,
) {
    val range: RangeConfig get() = RangeConfig(packWh, usableFraction, reservePct, defaultWhPerMi, detourFactor)

    /** Physics constants for this vehicle with the given total rolling mass. */
    fun params(totalMassKg: Double): RideParams = RideParams(
        massKg = totalMassKg, crr = type.preset.crr, cdA = type.preset.cdA, cruiseMps = cruiseMps(),
        regenRecovery = type.preset.regenRecovery,
    )

    /** Typical cruise: the class default, but never above ~85 % of this vehicle's top speed. */
    fun cruiseMps(): Double = minOf(type.preset.cruiseMps, topSpeedMph * 0.44704 * 0.85)

    fun withRange(r: RangeConfig) = copy(packWh = r.packWh, usableFraction = r.usableFraction, reservePct = r.reservePct,
        defaultWhPerMi = r.defaultWhPerMi, detourFactor = r.detourFactor)

    companion object {
        /** A new vehicle with the preset's numbers. */
        fun create(type: VehicleType, id: String, name: String = type.label, nowMs: Long = 0) = Vehicle(id = id, name = name.ifBlank { type.label },
            type = type, createdMs = nowMs)
    }
}

/** Pure list operations on the garage (the store persists the result). */
object Garage {
    fun upsert(list: List<Vehicle>, v: Vehicle): List<Vehicle> =
        if (list.any { it.id == v.id }) list.map { if (it.id == v.id) v else it } else list + v

    /** Removes [id]; if it was active, the first remaining vehicle becomes active. */
    fun remove(list: List<Vehicle>, activeId: String?, id: String): Pair<List<Vehicle>, String?> {
        val rest = list.filterNot { it.id == id }
        val active = if (activeId == id || rest.none { it.id == activeId }) rest.firstOrNull()?.id else activeId
        return rest to active
    }

    fun active(list: List<Vehicle>, activeId: String?): Vehicle? = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()

    /** Plain-language check of the numbers; null = fine. */
    fun validate(v: Vehicle): String? = when {
        v.packWh !in 20.0..5000.0 -> "Battery should be between 20 and 5000 Wh."
        v.usableFraction !in 0.5..1.0 -> "Usable share should be 50–100 %."
        v.weightKg !in 2.0..200.0 -> "Vehicle weight looks wrong."
        v.topSpeedMph !in 3.0..80.0 -> "Top speed looks wrong."
        v.defaultWhPerMi !in 3.0..100.0 -> "Starting use should be 3–100 Wh/mi."
        v.type.link == LinkKind.VESC && (v.cellsSeries ?: 0) !in 6..36 -> "Cells in series should be 6–36."
        v.type.link == LinkKind.VESC && (v.motorPolePairs ?: 0) !in 1..60 -> "Pole pairs should be 1–60."
        v.type.link == LinkKind.VESC && (v.gearRatio ?: 1.0) !in 0.2..20.0 -> "Gear ratio should be 0.2–20 (1 for a hub motor)."
        v.type.link == LinkKind.VESC && (v.wheelDiameterMm ?: 0.0) !in 50.0..800.0 -> "Wheel diameter should be 50–800 mm."
        v.type.link == LinkKind.FUTURE_MOTION && (v.wheelDiameterMm ?: 0.0) !in 100.0..800.0 -> "Tyre diameter should be 100–800 mm."
        else -> null
    }
}
