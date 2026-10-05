package com.elect.riderange.data

import com.elect.riderange.vehicle.ModelSnapshot
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.VehicleType
import org.json.JSONArray
import org.json.JSONObject

/** Garage <-> JSON (stored as one DataStore string). Unknown/old fields are ignored, missing ones get defaults. */
object VehicleJson {
    fun modelToJson(m: ModelSnapshot): JSONObject = JSONObject().put("coef", JSONArray().apply { m.coef.forEach { put(it) } })
        .put("miles", m.miles).put("rmse", m.rmse).put("updated", m.updatedMs)

    fun modelFrom(o: JSONObject?): ModelSnapshot? = try {
        if (o == null) null else {
            val a = o.getJSONArray("coef")
            ModelSnapshot(DoubleArray(a.length()) { a.getDouble(it) }, o.getDouble("miles"), o.getDouble("rmse"), o.optLong("updated"))
        }
    } catch (_: Exception) { null }

    fun toJson(v: Vehicle): JSONObject = JSONObject()
        .put("id", v.id).put("name", v.name).put("type", v.type.name)
        .put("packWh", v.packWh).put("usable", v.usableFraction).put("reserve", v.reservePct).put("whPerMi", v.defaultWhPerMi)
        .put("detour", v.detourFactor).put("weightKg", v.weightKg).put("topMph", v.topSpeedMph).put("rangeMi", v.ratedRangeMi)
        .put("created", v.createdMs)
        .apply {
            v.bleAddress?.let { put("ble", it) }
            v.bleName?.let { put("bleName", it) }
            v.pastedKeyHex?.let { put("pastedKey", it) }
            v.wheelDiameterMm?.let { put("wheelMm", it) }
            v.motorPolePairs?.let { put("polePairs", it) }
            v.cellsSeries?.let { put("cells", it) }
            v.model?.let { put("model", modelToJson(it)) }
        }

    fun fromJson(o: JSONObject): Vehicle? {
        val type = VehicleType.of(o.optString("type")) ?: return null
        val id = o.optString("id").ifBlank { return null }
        val base = Vehicle.create(type, id, o.optString("name", type.label))
        fun d(k: String, def: Double) = if (o.has(k)) o.optDouble(k, def) else def
        fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).ifBlank { null } else null
        return base.copy(
            packWh = d("packWh", base.packWh), usableFraction = d("usable", base.usableFraction), reservePct = d("reserve", base.reservePct),
            defaultWhPerMi = d("whPerMi", base.defaultWhPerMi), detourFactor = d("detour", base.detourFactor),
            weightKg = d("weightKg", base.weightKg), topSpeedMph = d("topMph", base.topSpeedMph), ratedRangeMi = d("rangeMi", base.ratedRangeMi),
            bleAddress = s("ble"), bleName = s("bleName"), pastedKeyHex = s("pastedKey"),
            wheelDiameterMm = if (o.has("wheelMm")) o.optDouble("wheelMm") else base.wheelDiameterMm,
            motorPolePairs = if (o.has("polePairs")) o.optInt("polePairs") else base.motorPolePairs,
            cellsSeries = if (o.has("cells")) o.optInt("cells") else base.cellsSeries,
            model = modelFrom(o.optJSONObject("model")),
            createdMs = o.optLong("created"),
        )
    }

    fun listToJson(list: List<Vehicle>): String = JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()

    fun listFrom(s: String?): List<Vehicle> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
}
