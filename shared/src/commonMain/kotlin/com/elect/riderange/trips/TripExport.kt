package com.elect.riderange.trips

import com.elect.riderange.core.json.JSONArray
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.core.DateFmt
import com.elect.riderange.core.format

/** Everything about a trip that leaves the phone (export / GitHub upload). */
data class TripRecord(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val serial: String?,
    val stats: TripStats,
    val predictedWh: Double?,
    val modelLabel: String?,
    val modelCoef: DoubleArray?,
    val weatherTempC: Double?,
    val samples: List<Sample>,
    val tripMass: com.elect.riderange.range.TripMass? = null,
    val combinedMass: com.elect.riderange.range.MassEstimate? = null,
    val massUsedKg: Double? = null,
    val vehicleName: String? = null,
    val vehicleType: String? = null,
)

object TripExport {
    private fun iso(ms: Long): String =
        DateFmt.utc(ms, "yyyy-MM-dd'T'HH:mm:ss'Z'")

    private fun f(v: Double?, digits: Int = 2): String = v?.let { "%.${digits}f".format( it) } ?: ""

    fun gpx(trip: TripRecord): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"RideRange\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk><name>RideRange trip ${iso(trip.startMs)}</name><trkseg>\n")
        for (s in trip.samples) {
            append("    <trkpt lat=\"${f(s.lat, 7)}\" lon=\"${f(s.lon, 7)}\">")
            (s.ele ?: s.gpsAlt)?.let { append("<ele>${f(it, 1)}</ele>") }
            append("<time>${iso(s.t)}</time>")
            s.speed?.let { append("<extensions><speed>${f(it, 2)}</speed></extensions>") }
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    const val CSV_HEADER = "time_utc,lat,lon,gps_alt_m,ele_m,gps_speed_mps,scooter_speed_mps,voltage_v,current_a,power_w,battery_pct,temp_c,odometer_m,accuracy_m"

    fun csv(trip: TripRecord): String = buildString {
        append(CSV_HEADER).append('\n')
        for (s in trip.samples) {
            append(listOf(iso(s.t), f(s.lat, 7), f(s.lon, 7), f(s.gpsAlt, 1), f(s.ele, 1), f(s.gpsSpeed), f(s.scooterSpeed),
                f(s.voltage), f(s.current), f(s.powerW, 1), f(s.batteryPct, 0), f(s.tempC, 1), f(s.odometerM, 0), f(s.accuracy, 0))
                .joinToString(",")).append('\n')
        }
    }

    fun statsJson(s: TripStats): JSONObject = JSONObject()
        .put("distance_m", s.distanceM).put("duration_s", s.durationS).put("moving_s", s.movingS)
        .put("avg_speed_mps", s.avgSpeed).put("max_speed_mps", s.maxSpeed)
        .put("climb_m", s.climbM).put("descent_m", s.descentM)
        .put("wh_used", s.whUsed ?: JSONObject.NULL).put("regen_wh", s.regenWh ?: JSONObject.NULL)
        .put("wh_per_mi", s.whPerMi ?: JSONObject.NULL).put("energy_source", s.energySource ?: JSONObject.NULL)
        .put("battery_start", s.batteryStart ?: JSONObject.NULL).put("battery_end", s.batteryEnd ?: JSONObject.NULL)
        .put("avg_temp_c", s.avgTempC ?: JSONObject.NULL)

    /** Full trip JSON for the GitHub upload: samples as compact column arrays. */
    fun json(trip: TripRecord, appVersion: String): String {
        val cols = JSONObject()
        fun col(name: String, get: (Sample) -> Any?) {
            val a = JSONArray()
            trip.samples.forEach { a.put(get(it) ?: JSONObject.NULL) }
            cols.put(name, a)
        }
        col("t") { it.t }
        col("lat") { it.lat }; col("lon") { it.lon }
        col("gps_alt") { it.gpsAlt }; col("ele") { it.ele }
        col("gps_speed") { it.gpsSpeed }; col("scooter_speed") { it.scooterSpeed }
        col("voltage") { it.voltage }; col("current") { it.current }; col("power_w") { it.powerW }
        col("battery_pct") { it.batteryPct }; col("temp_c") { it.tempC }; col("odometer_m") { it.odometerM }
        col("accuracy") { it.accuracy }
        val actual = trip.stats.whUsed
        return JSONObject()
            .put("schema", "riderange/trip/1")
            .put("app_version", appVersion)
            .put("trip_id", trip.id)
            .put("start", iso(trip.startMs)).put("end", iso(trip.endMs))
            .put("serial", trip.serial ?: JSONObject.NULL)
            .put("vehicle", JSONObject().put("name", trip.vehicleName ?: JSONObject.NULL).put("type", trip.vehicleType ?: JSONObject.NULL))
            .put("stats", statsJson(trip.stats))
            .put("prediction", JSONObject()
                .put("predicted_wh", trip.predictedWh ?: JSONObject.NULL)
                .put("actual_wh", actual ?: JSONObject.NULL)
                .put("error_pct", if (trip.predictedWh != null && actual != null && actual > 1) (trip.predictedWh - actual) / actual * 100 else JSONObject.NULL)
                .put("model", trip.modelLabel ?: JSONObject.NULL)
                .put("coefficients", trip.modelCoef?.let { c -> JSONArray().apply { c.forEach { put(it) } } } ?: JSONObject.NULL))
            .put("weather_temp_c", trip.weatherTempC ?: JSONObject.NULL)
            .put("mass", JSONObject()
                .put("used_kg", trip.massUsedKg ?: JSONObject.NULL)
                .put("trip", trip.tripMass?.let { m -> JSONObject().put("kg", m.massKg).put("se_kg", m.seKg).put("samples", m.samples)
                    .put("spread", m.spread).put("identifiable", m.identifiable).put("crr_implied", m.crrImplied) } ?: JSONObject.NULL)
                .put("combined", trip.combinedMass?.let { m -> JSONObject().put("kg", m.massKg).put("se_kg", m.seKg).put("trips", m.trips)
                    .put("confident", m.confident).put("from_grade_coef_kg", m.fromGradeCoefKg ?: JSONObject.NULL) } ?: JSONObject.NULL))
            .put("sample_rate_hz", 1)
            .put("samples", cols)
            .toString()
    }
}
