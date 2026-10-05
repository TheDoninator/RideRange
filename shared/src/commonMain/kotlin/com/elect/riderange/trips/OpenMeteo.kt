package com.elect.riderange.trips

import com.elect.riderange.core.Http
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.core.format

/** Open-Meteo (free, no key): DEM elevation for trip tracks and the current air temperature. */
class OpenMeteo(private val http: Http, private val baseUrl: () -> String = { com.elect.riderange.core.ServiceUrls.DEFAULT_OPEN_METEO }) {
    private val base: String get() = baseUrl()
    /** Up to 100 points per call. Returns null on any failure (the trip just keeps its own elevation). */
    suspend fun elevations(points: List<LatLon>): List<Double>? = try {
        val out = ArrayList<Double>()
        for (chunk in points.chunked(100)) {
            val lat = chunk.joinToString(",") { "%.5f".format( it.lat) }
            val lon = chunk.joinToString(",") { "%.5f".format( it.lon) }
            val r = http.get("$base/elevation?latitude=$lat&longitude=$lon")
            if (r.code != 200) return null
            val a = JSONObject(r.body).getJSONArray("elevation")
            if (a.length() != chunk.size) return null
            for (i in 0 until a.length()) out += a.getDouble(i)
        }
        out
    } catch (_: Exception) { null }

    suspend fun temperature(p: LatLon): Double? = try {
        val r = http.get("%s/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m".format( base, p.lat, p.lon))
        if (r.code == 200) JSONObject(r.body).getJSONObject("current").getDouble("temperature_2m") else null
    } catch (_: Exception) { null }
}
