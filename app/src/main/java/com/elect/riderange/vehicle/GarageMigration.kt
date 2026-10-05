package com.elect.riderange.vehicle

/**
 * What a 1.0.x install stored (one scooter, settings at the top level). Every field is null when it was never
 * written, which is how a fresh install is told apart from an upgrade.
 */
data class LegacyData(
    val packWh: Double? = null,
    val usableFraction: Double? = null,
    val reservePct: Double? = null,
    val whPerMi: Double? = null,
    val detourFactor: Double? = null,
    val scooterAddress: String? = null,
    val scooterName: String? = null,
    val pastedKeyHex: String? = null,
    /** Scooter serials with saved pairing keys. */
    val knownSerials: List<String> = emptyList(),
    val model: ModelSnapshot? = null,
    val tripCount: Int = 0,
    val riderLb: Double? = null,
)

data class MigrationResult(
    val vehicles: List<Vehicle>,
    val activeId: String?,
    /** Trips with no vehicle get this id. */
    val assignTripsTo: String?,
    /** An upgraded install has used the app already: don't show first-run onboarding. */
    val skipOnboarding: Boolean,
    /** 1.0.x used 165 lb when the rider never changed it; keep that so their numbers don't move. */
    val riderLb: Double?,
)

/**
 * 1.0.x -> 1.1: the single Max G2 setup becomes the first vehicle of the garage, with its range settings,
 * Bluetooth address, pasted key and learned model; all existing trips are assigned to it.
 */
object GarageMigration {
    const val LEGACY_DEFAULT_RIDER_LB = 165.0

    fun isUpgrade(l: LegacyData): Boolean =
        listOf(l.packWh, l.usableFraction, l.reservePct, l.whPerMi, l.detourFactor, l.scooterAddress, l.pastedKeyHex, l.model, l.riderLb).any { it != null } ||
            l.knownSerials.isNotEmpty() || l.tripCount > 0

    fun migrate(l: LegacyData, newId: String, nowMs: Long): MigrationResult {
        if (!isUpgrade(l)) return MigrationResult(emptyList(), null, null, skipOnboarding = false, riderLb = null)
        val base = Vehicle.create(VehicleType.NINEBOT_MAX_G2, newId, "Max G2", nowMs)
        val v = base.copy(
            packWh = l.packWh ?: base.packWh,
            usableFraction = l.usableFraction ?: base.usableFraction,
            reservePct = l.reservePct ?: base.reservePct,
            defaultWhPerMi = l.whPerMi ?: base.defaultWhPerMi,
            detourFactor = l.detourFactor ?: base.detourFactor,
            bleAddress = l.scooterAddress,
            bleName = l.scooterName,
            pastedKeyHex = l.pastedKeyHex,
            model = l.model,
        )
        return MigrationResult(listOf(v), v.id, v.id, skipOnboarding = true, riderLb = l.riderLb ?: LEGACY_DEFAULT_RIDER_LB)
    }
}
