package com.elect.riderange.routing

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.vehicle.VehicleClass
import org.json.JSONObject

enum class RouteMode(val label: String) {
    TRAILS("Bike trails"),
    BATTERY("Battery saver"),
    TRAFFIC("Least traffic"),
    FASTEST("Fastest");
}

/** BRouter voice-hint commands (btools.router.VoiceHint). */
enum class Turn(val code: Int, val text: String, val isRealTurn: Boolean) {
    STRAIGHT(1, "Continue straight", false),
    LEFT(2, "Turn left", true),
    SLIGHT_LEFT(3, "Bear left", false),
    SHARP_LEFT(4, "Turn sharp left", true),
    RIGHT(5, "Turn right", true),
    SLIGHT_RIGHT(6, "Bear right", false),
    SHARP_RIGHT(7, "Turn sharp right", true),
    KEEP_LEFT(8, "Keep left", false),
    KEEP_RIGHT(9, "Keep right", false),
    U_TURN_LEFT(10, "Make a U-turn", true),
    U_TURN(11, "Make a U-turn", true),
    U_TURN_RIGHT(12, "Make a U-turn", true),
    OFF_ROUTE(13, "Continue", false),
    ROUNDABOUT(14, "At the roundabout, take exit", true),
    ROUNDABOUT_LEFT(15, "At the roundabout, take exit", true),
    BEELINE(16, "Continue", false),
    EXIT_LEFT(17, "Take the exit on the left", false),
    EXIT_RIGHT(18, "Take the exit on the right", false),
    ARRIVE(100, "You have arrived", false);

    companion object {
        fun of(code: Int): Turn = entries.firstOrNull { it.code == code } ?: STRAIGHT
    }
}

data class Instruction(
    /** Index into [Route.points] where the manoeuvre happens. */
    val index: Int,
    val turn: Turn,
    val exit: Int = 0,
    /** Distance along the route from the start, metres. */
    val atM: Double,
) {
    val text: String get() = if (exit > 0 && (turn == Turn.ROUNDABOUT || turn == Turn.ROUNDABOUT_LEFT)) "${turn.text} $exit" else turn.text
}

data class Route(
    val points: List<LatLon>,
    val elevations: List<Double?>,
    val instructions: List<Instruction>,
    val lengthM: Double,
    val ascendM: Double,
    val timeS: Double,
    val profile: String,
    val alternative: Int,
    /** Cumulative distance at each point, metres. */
    val cum: List<Double>,
    /** Way tags along the route ("highway=cycleway surface=asphalt") with how many metres of each highway type. */
    val highwayMix: Map<String, Double> = emptyMap(),
) {
    val realTurns: Int get() = instructions.count { it.turn.isRealTurn }

    /** Share of the route on dedicated bike/path infrastructure. */
    val bikeInfraShare: Double get() {
        val total = highwayMix.values.sum()
        if (total <= 0) return 0.0
        val bike = highwayMix.filterKeys { it in setOf("cycleway", "path", "footway", "track", "pedestrian") }.values.sum()
        return bike / total
    }
}

object BRouterParser {
    /** Parses one BRouter GeoJSON (format=geojson, timode=3 for voice hints). */
    fun parse(json: String, profile: String = "", alternative: Int = 0): Route {
        val root = JSONObject(json)
        val f = root.getJSONArray("features").getJSONObject(0)
        val props = f.getJSONObject("properties")
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<LatLon>(coords.length())
        val eles = ArrayList<Double?>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts += LatLon(c.getDouble(1), c.getDouble(0))
            eles += if (c.length() > 2) c.getDouble(2) else null
        }
        val cum = ArrayList<Double>(pts.size)
        var acc = 0.0
        for (i in pts.indices) {
            if (i > 0) acc += Geo.distance(pts[i - 1], pts[i])
            cum += acc
        }
        val instr = ArrayList<Instruction>()
        props.optJSONArray("voicehints")?.let { vh ->
            for (i in 0 until vh.length()) {
                val h = vh.getJSONArray(i)
                val idx = h.getInt(0).coerceIn(0, pts.size - 1)
                instr += Instruction(idx, Turn.of(h.getInt(1)), h.optInt(2, 0), cum[idx])
            }
        }
        instr += Instruction(pts.size - 1, Turn.ARRIVE, 0, acc)
        val mix = HashMap<String, Double>()
        props.optJSONArray("messages")?.let { m ->
            // Header row, then: lon lat ele distance ... WayTags ...
            if (m.length() > 1) {
                val header = m.getJSONArray(0)
                var distCol = -1; var tagCol = -1
                for (i in 0 until header.length()) {
                    when (header.getString(i)) { "Distance" -> distCol = i; "WayTags" -> tagCol = i }
                }
                if (distCol >= 0 && tagCol >= 0) for (r in 1 until m.length()) {
                    val row = m.getJSONArray(r)
                    val d = row.optString(distCol).toDoubleOrNull() ?: continue
                    val hw = Regex("highway=([a-z_]+)").find(row.optString(tagCol))?.groupValues?.get(1) ?: "other"
                    mix[hw] = (mix[hw] ?: 0.0) + d
                }
            }
        }
        return Route(
            points = pts,
            elevations = eles,
            instructions = instr,
            lengthM = props.optString("track-length").toDoubleOrNull() ?: acc,
            ascendM = props.optString("filtered ascend").toDoubleOrNull() ?: 0.0,
            timeS = props.optString("total-time").toDoubleOrNull() ?: 0.0,
            profile = profile,
            alternative = alternative,
            cum = cum,
            highwayMix = mix,
        )
    }

    /** BRouter reports errors as plain text. */
    fun looksLikeError(body: String): Boolean = !body.trimStart().startsWith("{")
}

/** Which BRouter requests a mode makes, per vehicle class. */
object RoutePlans {
    data class Request(val profile: String, val alternative: Int)

    /** "@trails" / "@traffic" are the bundled e-scooter profiles (assets/brouter), uploaded on first use. */
    const val TRAILS = "@trails"
    const val TRAFFIC = "@traffic"
    /** One-wheel board variants: dirt trails and unpaved surfaces are fine, steps are still excluded. */
    const val OW_TRAILS = "@ow-trails"
    const val OW_TRAFFIC = "@ow-traffic"
    const val ES_TRAILS = "@es-trails"
    const val ES_TRAFFIC = "@es-traffic"

    /** Bundled profile file for a custom profile name. */
    fun asset(profile: String): String? = when (profile) {
        TRAILS -> "brouter/scooter-trails.brf"
        TRAFFIC -> "brouter/scooter-traffic.brf"
        OW_TRAILS -> "brouter/onewheel-trails.brf"
        OW_TRAFFIC -> "brouter/onewheel-traffic.brf"
        ES_TRAILS -> "brouter/eskate-trails.brf"
        ES_TRAFFIC -> "brouter/eskate-traffic.brf"
        else -> null
    }

    /** Built-in BRouter profile used when the custom profile can't be uploaded. */
    fun fallback(profile: String): String = when (profile) {
        TRAILS, OW_TRAILS, ES_TRAILS -> "trekking"
        TRAFFIC, OW_TRAFFIC, ES_TRAFFIC -> "safety"
        else -> profile
    }

    fun requests(mode: RouteMode, vehicle: VehicleClass = VehicleClass.KICK_SCOOTER): List<Request> {
        // One-wheel boards: dirt is fine. E-skateboards: small hard wheels, paved only. E-bikes: BRouter's own bike
        // profiles (they handle gravel). Scooters and everything else: the scooter profiles.
        val (trails, traffic) = when (vehicle) {
            VehicleClass.ONEWHEEL -> OW_TRAILS to OW_TRAFFIC
            VehicleClass.E_SKATEBOARD -> ES_TRAILS to ES_TRAFFIC
            VehicleClass.E_BIKE -> "trekking" to "safety"
            else -> TRAILS to TRAFFIC
        }
        return when (mode) {
            RouteMode.TRAILS -> listOf(Request(trails, 0))
            RouteMode.TRAFFIC -> listOf(Request(traffic, 0))
            RouteMode.FASTEST -> listOf(Request("fastbike", 0))
            // Battery saver: several candidates, scored by the energy model (fastest first, it's the baseline).
            RouteMode.BATTERY -> listOf(
                Request("fastbike", 0), Request(trails, 0), Request(traffic, 0),
                Request(trails, 1), Request("fastbike", 1),
            )
        }
    }

    /** [profileId] is the resolved BRouter profile (built-in name or custom_… id). */
    fun url(base: String, from: LatLon, to: LatLon, profileId: String, alternative: Int): String =
        String.format(
            java.util.Locale.US, "%s?lonlats=%.6f,%.6f|%.6f,%.6f&profile=%s&alternativeidx=%d&format=geojson&timode=3",
            base.trimEnd('/'), from.lon, from.lat, to.lon, to.lat, profileId, alternative,
        )
}

/** Battery-saver choice: lowest predicted Wh wins; savings are reported against the fastest candidate. */
object BatterySaver {
    data class Scored(val route: Route, val wh: Double)
    data class Choice(val best: Scored, val fastestWh: Double?, val candidates: List<Scored>) {
        val savesWh: Double get() = fastestWh?.let { it - best.wh } ?: 0.0
    }

    fun choose(candidates: List<Scored>): Choice? {
        if (candidates.isEmpty()) return null
        // Drop near-identical duplicates (same length and climb).
        val unique = candidates.distinctBy { (it.route.lengthM / 20).toInt() to (it.route.ascendM / 3).toInt() }
        val best = unique.minBy { it.wh }
        val fastest = candidates.firstOrNull { it.route.profile == "fastbike" && it.route.alternative == 0 }
        return Choice(best, fastest?.wh, unique.sortedBy { it.wh })
    }
}

/** Last computed route, saved for offline use (destination + geometry + instructions). */
object RouteCache {
    fun toJson(r: Route, destLat: Double, destLon: Double, destName: String): String {
        val o = JSONObject()
        o.put("dest", org.json.JSONArray().put(destLat).put(destLon)).put("name", destName)
        o.put("profile", r.profile).put("alt", r.alternative).put("len", r.lengthM).put("asc", r.ascendM).put("time", r.timeS)
        val pts = org.json.JSONArray()
        r.points.forEachIndexed { i, p -> pts.put(org.json.JSONArray().put(p.lat).put(p.lon).put(r.elevations[i] ?: JSONObject.NULL)) }
        o.put("pts", pts)
        val ins = org.json.JSONArray()
        r.instructions.forEach { ins.put(org.json.JSONArray().put(it.index).put(it.turn.code).put(it.exit)) }
        o.put("ins", ins)
        return o.toString()
    }

    class Cached(val route: Route, val dest: LatLon, val name: String)

    fun fromJson(json: String): Cached? = try {
        val o = JSONObject(json)
        val pa = o.getJSONArray("pts")
        val pts = ArrayList<LatLon>(); val eles = ArrayList<Double?>()
        for (i in 0 until pa.length()) {
            val a = pa.getJSONArray(i)
            pts += LatLon(a.getDouble(0), a.getDouble(1)); eles += if (a.isNull(2)) null else a.getDouble(2)
        }
        val cum = ArrayList<Double>(); var acc = 0.0
        pts.forEachIndexed { i, p -> if (i > 0) acc += Geo.distance(pts[i - 1], p); cum += acc }
        val ia = o.getJSONArray("ins")
        val ins = (0 until ia.length()).map { val a = ia.getJSONArray(it); val idx = a.getInt(0).coerceIn(0, pts.size - 1); Instruction(idx, Turn.of(a.getInt(1)), a.getInt(2), cum[idx]) }
        val d = o.getJSONArray("dest")
        Cached(Route(pts, eles, ins, o.getDouble("len"), o.getDouble("asc"), o.getDouble("time"), o.getString("profile"), o.getInt("alt"), cum),
            LatLon(d.getDouble(0), d.getDouble(1)), o.getString("name"))
    } catch (_: Exception) { null }
}
