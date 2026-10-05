package com.elect.riderange.routing

import com.elect.riderange.core.Http
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.RateLimiter
import com.elect.riderange.core.ServiceException
import org.json.JSONObject
import java.io.IOException

/**
 * Free public BRouter server. Custom e-scooter profiles (no steps, no rough tracks) are uploaded once per
 * session; if that fails the built-in profile is used instead. Requests are spaced ≥ 1.5 s apart.
 */
class BRouterClient(
    private val http: Http,
    private val profileText: (String) -> String?,
    private val baseUrl: () -> String = { com.elect.riderange.core.ServiceUrls.DEFAULT_BROUTER },
    private val limiter: RateLimiter = RateLimiter(1500),
) {
    private val uploaded = HashMap<String, String>()
    private val base: String get() = baseUrl()

    private suspend fun resolve(profile: String, force: Boolean = false): String {
        if (!profile.startsWith("@")) return profile
        val key = "$base|$profile"
        if (!force) uploaded[key]?.let { return it }
        val text = profileText(profile) ?: return RoutePlans.fallback(profile)
        return try {
            val r = limiter.run { http.post("$base/profile", text, "text/plain") }
            val id = if (r.code == 200) JSONObject(r.body).optString("profileid", "") else ""
            if (id.startsWith("custom_")) { uploaded[key] = id; id } else RoutePlans.fallback(profile)
        } catch (_: Exception) {
            RoutePlans.fallback(profile)
        }
    }

    suspend fun route(from: LatLon, to: LatLon, req: RoutePlans.Request): Route {
        var id = resolve(req.profile)
        var body = fetch(from, to, id, req.alternative)
        if (BRouterParser.looksLikeError(body) && id.startsWith("custom_") && body.contains("profile", true)) {
            // Custom profiles expire on the server: upload again once.
            id = resolve(req.profile, force = true)
            body = fetch(from, to, id, req.alternative)
        }
        if (BRouterParser.looksLikeError(body)) throw ServiceException(friendly(body))
        return BRouterParser.parse(body, req.profile, req.alternative)
    }

    private suspend fun fetch(from: LatLon, to: LatLon, id: String, alt: Int): String {
        val r = try {
            limiter.run { http.get(RoutePlans.url(base, from, to, id, alt)) }
        } catch (e: IOException) {
            throw ServiceException("No connection to the routing server. Check your internet and try again.")
        }
        if (r.code != 200 && !BRouterParser.looksLikeError(r.body)) throw ServiceException("Routing server error ${r.code}")
        return r.body
    }

    private fun friendly(body: String): String = when {
        body.contains("not mapped", true) || body.contains("no track found", true) ->
            "No bike-friendly route found between these points."
        body.contains("operation killed", true) || body.contains("timeout", true) ->
            "The routing server is busy. Try again in a moment."
        else -> "Routing failed: " + body.lineSequence().firstOrNull().orEmpty().take(120)
    }
}
