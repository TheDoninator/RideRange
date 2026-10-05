package com.elect.riderange.search

import com.elect.riderange.core.Http
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.RateLimiter
import com.elect.riderange.core.ServiceException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val detail: String, val pos: LatLon)

/** Where the rider is, for the Rules tab. */
data class Region(val countryCode: String?, val stateCode: String?, val stateName: String?, val city: String?)

object NominatimParser {
    fun parseSearch(json: String): List<Place> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(',') }
            Place(name, display.substringAfter(", ", "").take(120), LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
    }

    fun parseReverse(json: String): Region {
        val a = JSONObject(json).optJSONObject("address") ?: return Region(null, null, null, null)
        val iso = a.optString("ISO3166-2-lvl4").ifBlank { null }       // "US-UT"
        val state = iso?.substringAfter('-', "")?.ifBlank { null }
        val city = listOf("city", "town", "village", "hamlet").firstNotNullOfOrNull { a.optString(it).ifBlank { null } }
        return Region(a.optString("country_code").ifBlank { null }?.uppercase(), state, a.optString("state").ifBlank { null }, city)
    }
}

/** OSM Nominatim: max 1 request per second with an identifying User-Agent (usage policy). */
class NominatimClient(
    private val http: Http,
    private val baseUrl: () -> String = { com.elect.riderange.core.ServiceUrls.DEFAULT_NOMINATIM },
    private val limiter: RateLimiter = RateLimiter(1100),
) {
    private val base: String get() = baseUrl()
    /** Places near the rider first (~80 km box); only if there are none, search the whole US. */
    suspend fun search(query: String, near: LatLon?): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val root = "$base/search?q=$q&format=jsonv2&limit=8&addressdetails=0&countrycodes=us"
        if (near != null) {
            val box = String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f&bounded=1", near.lon - 0.9, near.lat + 0.7, near.lon + 0.9, near.lat - 0.7)
            val local = NominatimParser.parseSearch(call(root + box))
            if (local.isNotEmpty()) return local
        }
        return NominatimParser.parseSearch(call(root))
    }

    suspend fun reverse(p: LatLon): Region {
        val url = String.format(Locale.US, "%s/reverse?lat=%.5f&lon=%.5f&format=jsonv2&zoom=12&addressdetails=1", base, p.lat, p.lon)
        return NominatimParser.parseReverse(call(url))
    }

    private suspend fun call(url: String): String {
        val r = try {
            limiter.run { http.get(url, mapOf("Accept-Language" to "en")) }
        } catch (e: IOException) {
            throw ServiceException("No connection to the place search server")
        }
        if (r.code != 200) throw ServiceException("Place search error ${r.code}")
        return r.body
    }
}
