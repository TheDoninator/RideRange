package com.elect.riderange.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.elect.riderange.core.ServiceUrls
import com.elect.riderange.range.LearnedModel
import com.elect.riderange.range.MassEstimator
import com.elect.riderange.range.RangeConfig
import com.elect.riderange.vehicle.Garage
import com.elect.riderange.vehicle.GarageMigration
import com.elect.riderange.vehicle.LegacyData
import com.elect.riderange.vehicle.ModelSnapshot
import com.elect.riderange.vehicle.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.core.currentTimeMillis
import com.elect.riderange.core.format

data class AppSettings(
    val metric: Boolean = false,
    /** Range settings of the active vehicle (editing them edits that vehicle). */
    val range: RangeConfig = RangeConfig(),
    /** Battery % used when no vehicle is connected (slider), or the last value read from it. */
    val manualBattery: Double = 80.0,
    val voiceMuted: Boolean = false,
    val showParkingOnRide: Boolean = true,
    val showRepair: Boolean = false,
    val showCharging: Boolean = false,
    /** Active vehicle's Bluetooth address/name (editing them edits that vehicle). */
    val lastScooterAddress: String? = null,
    val lastScooterName: String? = null,
    val autoConnect: Boolean = true,
    val autoTrips: Boolean = true,
    /** GitHub trip upload (advanced, opt-in): the rider's own owner/repo; blank = not set up. */
    val uploadRepo: String = "",
    val uploadWifiOnly: Boolean = false,
    val hasToken: Boolean = false,
    /** Rider weight, lb; null until the rider enters it (first-run onboarding asks). */
    val riderLb: Double? = null,
    val cargoKg: Double = 2.0,
    /** Use the mass estimated from rides once it is confident. */
    val useEstimatedMass: Boolean = true,
    val onboarded: Boolean = false,
    val vehicles: List<Vehicle> = emptyList(),
    val activeVehicleId: String? = null,
    val urls: ServiceUrls = ServiceUrls(),
    /** owner/repo whose GitHub releases are checked for a newer version (blank = never check). */
    val updateRepo: String = "",
    /** VESC: vibrate + speak on pushback / high duty cycle (the mute button on the Float panel turns this off). */
    val rideAlerts: Boolean = true,
    /** VESC: duty cycle (%) that triggers the "high duty" alert. */
    val dutyAlertPct: Int = 85,
) {
    val vehicle: Vehicle? get() = Garage.active(vehicles, activeVehicleId)
    val vehicleKg: Double get() = vehicle?.weightKg ?: MassEstimator.SCOOTER_KG
    /** Rider weight used by the physics until it's entered: 165 lb (75 kg), an average adult. */
    val riderLbOrDefault: Double get() = riderLb ?: DEFAULT_RIDER_LB
    val enteredMassKg: Double get() = riderLbOrDefault / MassEstimator.LB_PER_KG + vehicleKg + cargoKg

    companion object { const val DEFAULT_RIDER_LB = 165.0 }
}

fun ModelSnapshot.toLearned() = LearnedModel(coef, miles, rmse)

/** The "riderange" Preferences DataStore (same file and keys as 1.x on Android) plus the token cipher. */
class SettingsStore(private val store: DataStore<Preferences>, private val cipher: com.elect.riderange.core.SecretCipher) {
    private object K {
        val METRIC = booleanPreferencesKey("metric")
        // 1.0.x single-scooter keys, read once by the garage migration.
        val PACK = doublePreferencesKey("pack_wh")
        val USABLE = doublePreferencesKey("usable")
        val RESERVE = doublePreferencesKey("reserve")
        val WHMI = doublePreferencesKey("wh_per_mi")
        val DETOUR = doublePreferencesKey("detour")
        val ADDR = stringPreferencesKey("scooter_address")
        val NAME = stringPreferencesKey("scooter_name")
        val PASTED = stringPreferencesKey("pasted_key")
        val MODEL = stringPreferencesKey("learned_model")
        // current keys
        val BATTERY = doublePreferencesKey("manual_battery")
        val MUTED = booleanPreferencesKey("voice_muted")
        val P_RIDE = booleanPreferencesKey("parking_on_ride")
        val P_REPAIR = booleanPreferencesKey("parking_repair")
        val P_CHARGE = booleanPreferencesKey("parking_charging")
        val AUTO = booleanPreferencesKey("auto_connect")
        val AUTO_TRIPS = booleanPreferencesKey("auto_trips")
        val KEYS = stringPreferencesKey("scooter_keys")
        val REPO = stringPreferencesKey("upload_repo")
        val WIFI = booleanPreferencesKey("upload_wifi_only")
        val TOKEN = stringPreferencesKey("github_token_enc")
        val LAST_ROUTE = stringPreferencesKey("last_route")
        val RIDER_LB = doublePreferencesKey("rider_lb")
        val CARGO = doublePreferencesKey("cargo_kg")
        val USE_EST = booleanPreferencesKey("use_estimated_mass")
        val GARAGE = stringPreferencesKey("garage")
        val ACTIVE = stringPreferencesKey("active_vehicle")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val U_BROUTER = stringPreferencesKey("url_brouter")
        val U_NOMINATIM = stringPreferencesKey("url_nominatim")
        val U_OVERPASS = stringPreferencesKey("url_overpass")
        val U_METEO = stringPreferencesKey("url_open_meteo")
        val U_STYLE = stringPreferencesKey("url_map_style")
        val UPDATE_REPO = stringPreferencesKey("update_repo")
        val RIDE_ALERTS = booleanPreferencesKey("ride_alerts")
        val DUTY_ALERT = intPreferencesKey("duty_alert_pct")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val vehicles = VehicleJson.listFrom(p[K.GARAGE])
        val active = Garage.active(vehicles, p[K.ACTIVE])
        AppSettings(
            metric = p[K.METRIC] ?: false,
            range = active?.range ?: RangeConfig(),
            manualBattery = p[K.BATTERY] ?: 80.0,
            voiceMuted = p[K.MUTED] ?: false,
            showParkingOnRide = p[K.P_RIDE] ?: true,
            showRepair = p[K.P_REPAIR] ?: false,
            showCharging = p[K.P_CHARGE] ?: false,
            lastScooterAddress = active?.bleAddress, lastScooterName = active?.bleName,
            autoConnect = p[K.AUTO] ?: true,
            autoTrips = p[K.AUTO_TRIPS] ?: true,
            uploadRepo = p[K.REPO] ?: "",
            uploadWifiOnly = p[K.WIFI] ?: false,
            hasToken = !p[K.TOKEN].isNullOrBlank(),
            riderLb = p[K.RIDER_LB],
            cargoKg = p[K.CARGO] ?: 2.0,
            useEstimatedMass = p[K.USE_EST] ?: true,
            onboarded = p[K.ONBOARDED] ?: false,
            vehicles = vehicles,
            activeVehicleId = active?.id,
            urls = ServiceUrls(p[K.U_BROUTER] ?: "", p[K.U_NOMINATIM] ?: "", p[K.U_OVERPASS] ?: "", p[K.U_METEO] ?: "", p[K.U_STYLE] ?: ""),
            updateRepo = p[K.UPDATE_REPO] ?: "",
            rideAlerts = p[K.RIDE_ALERTS] ?: true,
            dutyAlertPct = (p[K.DUTY_ALERT] ?: 85).coerceIn(50, 100),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(f: (AppSettings) -> AppSettings) {
        val old = current()
        val n = f(old)
        store.edit { p ->
            p[K.METRIC] = n.metric
            p[K.BATTERY] = n.manualBattery
            p[K.MUTED] = n.voiceMuted
            p[K.P_RIDE] = n.showParkingOnRide; p[K.P_REPAIR] = n.showRepair; p[K.P_CHARGE] = n.showCharging
            p[K.AUTO] = n.autoConnect
            p[K.AUTO_TRIPS] = n.autoTrips
            p[K.REPO] = n.uploadRepo.trim()
            p[K.WIFI] = n.uploadWifiOnly
            n.riderLb?.let { p[K.RIDER_LB] = it } ?: p.remove(K.RIDER_LB)
            p[K.CARGO] = n.cargoKg
            p[K.USE_EST] = n.useEstimatedMass
            p[K.ONBOARDED] = n.onboarded
            p[K.U_BROUTER] = n.urls.brouter.trim(); p[K.U_NOMINATIM] = n.urls.nominatim.trim(); p[K.U_OVERPASS] = n.urls.overpass.trim()
            p[K.U_METEO] = n.urls.openMeteo.trim(); p[K.U_STYLE] = n.urls.mapStyle.trim()
            p[K.UPDATE_REPO] = n.updateRepo.trim()
            p[K.RIDE_ALERTS] = n.rideAlerts
            p[K.DUTY_ALERT] = n.dutyAlertPct.coerceIn(50, 100)
            // Range / Bluetooth edits go to the active vehicle (re-read so concurrent garage edits aren't lost).
            val list = VehicleJson.listFrom(p[K.GARAGE])
            val active = Garage.active(list, p[K.ACTIVE])
            if (active != null && (n.range != old.range || n.lastScooterAddress != old.lastScooterAddress || n.lastScooterName != old.lastScooterName)) {
                val v = active.withRange(n.range).copy(bleAddress = n.lastScooterAddress, bleName = n.lastScooterName)
                p[K.GARAGE] = VehicleJson.listToJson(Garage.upsert(list, v))
            }
        }
    }

    // ---- garage ----
    private suspend fun editGarage(f: (List<Vehicle>, String?) -> Pair<List<Vehicle>, String?>) = store.edit { p ->
        val (list, active) = f(VehicleJson.listFrom(p[K.GARAGE]), p[K.ACTIVE])
        p[K.GARAGE] = VehicleJson.listToJson(list)
        if (active != null) p[K.ACTIVE] = active else p.remove(K.ACTIVE)
    }

    /** Adds or replaces [v]; the first vehicle (or [makeActive]) becomes the active one. */
    suspend fun saveVehicle(v: Vehicle, makeActive: Boolean = false) = editGarage { list, active ->
        Garage.upsert(list, v) to (if (makeActive || active == null || list.none { it.id == active }) v.id else active)
    }

    suspend fun deleteVehicle(id: String) = editGarage { list, active -> Garage.remove(list, active, id) }

    suspend fun setActiveVehicle(id: String) = editGarage { list, _ -> list to id }

    suspend fun updateVehicle(id: String, f: (Vehicle) -> Vehicle) = editGarage { list, active ->
        list.map { if (it.id == id) f(it) else it } to active
    }

    /** Saves a fitted model with its vehicle. */
    suspend fun saveModel(vehicleId: String, m: ModelSnapshot?) = updateVehicle(vehicleId) { it.copy(model = m) }

    /**
     * One-time 1.0.x -> 1.1 migration: the single scooter's settings, key and learned model become the first
     * vehicle ("Max G2"). Returns the vehicle id existing trips should be assigned to, or null.
     */
    suspend fun migrateToGarage(tripCount: Int, newId: String): String? {
        var assign: String? = null
        store.edit { p ->
            if (p.contains(K.GARAGE)) return@edit
            val legacy = LegacyData(
                packWh = p[K.PACK], usableFraction = p[K.USABLE], reservePct = p[K.RESERVE], whPerMi = p[K.WHMI],
                detourFactor = p[K.DETOUR], scooterAddress = p[K.ADDR], scooterName = p[K.NAME], pastedKeyHex = p[K.PASTED],
                knownSerials = keysOf(p[K.KEYS]).keys.toList(), model = legacyModel(p[K.MODEL]), tripCount = tripCount, riderLb = p[K.RIDER_LB],
            )
            val r = GarageMigration.migrate(legacy, newId, currentTimeMillis())
            p[K.GARAGE] = VehicleJson.listToJson(r.vehicles)
            r.activeId?.let { p[K.ACTIVE] = it }
            if (r.skipOnboarding) p[K.ONBOARDED] = true
            r.riderLb?.let { p[K.RIDER_LB] = it }
            assign = r.assignTripsTo
        }
        return assign
    }

    /** The first vehicle's id, for trips that somehow have none. */
    suspend fun firstVehicleId(): String? = current().vehicles.firstOrNull()?.id

    private fun legacyModel(s: String?): ModelSnapshot? = try {
        VehicleJson.modelFrom(JSONObject(s ?: return null))
    } catch (_: Exception) { null }

    suspend fun saveLastRoute(json: String?) = store.edit { if (json == null) it.remove(K.LAST_ROUTE) else it[K.LAST_ROUTE] = json }
    suspend fun lastRoute(): String? = store.data.first()[K.LAST_ROUTE]

    // ---- scooter pairing keys (serial -> 32 hex), private app storage ----
    private fun keysOf(s: String?): Map<String, String> = try {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) { emptyMap() }

    private suspend fun keyMap(): Map<String, String> = keysOf(store.data.first()[K.KEYS])

    suspend fun key(serial: String): ByteArray? = keyMap()[serial]?.let { AppKeys.parse(it) }

    suspend fun putKey(serial: String, key: ByteArray) = store.edit {
        val m = try { JSONObject(it[K.KEYS] ?: "{}") } catch (_: Exception) { JSONObject() }
        m.put(serial, AppKeys.hex(key))
        it[K.KEYS] = m.toString()
    }

    suspend fun knownSerials(): List<String> = keyMap().keys.toList()

    /** A key pasted for the active vehicle (from the Ninebot Bridge app), used for its next connection. */
    suspend fun pastedKey(): ByteArray? = current().vehicle?.pastedKeyHex?.let { AppKeys.parse(it) }
    suspend fun setPastedKey(hex: String?) {
        val id = current().vehicle?.id ?: return
        updateVehicle(id) { it.copy(pastedKeyHex = hex) }
    }

    // ---- GitHub token, encrypted with a non-exportable Android Keystore key (iOS: kept in the Keychain) ----
    suspend fun setToken(token: String?) = store.edit {
        if (token.isNullOrBlank()) it.remove(K.TOKEN) else it[K.TOKEN] = cipher.encrypt(token.trim())
    }

    suspend fun token(): String? = store.data.first()[K.TOKEN]?.let { cipher.decryptOrNull(it) }
}

object AppKeys {
    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

    fun parse(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() && it != ':' && it != '-' }
        if (!HEX32.matches(clean)) return null
        return ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun hex(key: ByteArray): String = key.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

