package com.elect.riderange.parking

import com.elect.riderange.core.Http
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.RateLimiter
import com.elect.riderange.core.ServiceException
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.core.IOException
import com.elect.riderange.core.Text
import kotlin.math.floor
import com.elect.riderange.core.format

enum class SpotKind(val label: String) { PARKING("Bike parking"), REPAIR("Repair station"), CHARGING("Charging") }

data class ParkingSpot(
    val id: String,
    val kind: SpotKind,
    val lat: Double,
    val lon: Double,
    val name: String?,
    val type: String?,
    val capacity: Int?,
    val covered: Boolean?,
    val fee: String?,
    val access: String?,
    /** Short tag summary for the detail sheet. */
    val tags: Map<String, String>,
) {
    val pos: LatLon get() = LatLon(lat, lon)

    val typeLabel: String get() = when (kind) {
        SpotKind.PARKING -> type?.let { TYPE_NAMES[it] ?: it.replace('_', ' ').replaceFirstChar(Char::uppercase) } ?: "Bike parking"
        else -> kind.label
    }

    companion object {
        val TYPE_NAMES = mapOf(
            "stands" to "Bike stands", "wall_loops" to "Wheel benders", "rack" to "Bike rack", "wave" to "Wave rack",
            "bollard" to "Bollard", "lockers" to "Bike lockers", "building" to "Bike room", "shed" to "Bike shed",
            "shelter" to "Bike shelter", "ground_slots" to "Ground slots", "anchors" to "Ground anchors",
            "two-tier" to "Two-tier rack", "informal" to "Informal", "streetpod" to "Streetpod",
            "handlebar_holder" to "Handlebar holder", "floor" to "Floor area",
        )
    }
}

/** A tile-aligned area so cached queries can be reused (0.02° ≈ 2 km cells). */
data class Cell(val x: Int, val y: Int) {
    val south get() = y * SIZE
    val west get() = x * SIZE
    val north get() = south + SIZE
    val east get() = west + SIZE
    val key: String get() = "$x:$y"

    companion object {
        const val SIZE = 0.02
        fun of(lat: Double, lon: Double) = Cell(floor(lon / SIZE).toInt(), floor(lat / SIZE).toInt())

        /** Cells covering a bounding box (capped so a zoomed-out map doesn't query a whole state). */
        fun cover(south: Double, west: Double, north: Double, east: Double, max: Int = 16): List<Cell> {
            val a = of(south, west); val b = of(north, east)
            val list = ArrayList<Cell>()
            for (y in a.y..b.y) for (x in a.x..b.x) list += Cell(x, y)
            if (list.size <= max) return list
            val c = of((south + north) / 2, (west + east) / 2)
            return list.sortedBy { (it.x - c.x) * (it.x - c.x) + (it.y - c.y) * (it.y - c.y) }.take(max)
        }
    }
}

object OverpassParser {
    fun query(s: Double, w: Double, n: Double, e: Double, withRepair: Boolean, withCharging: Boolean): String {
        val bb = "(%.5f,%.5f,%.5f,%.5f)".format( s, w, n, e)
        val parts = StringBuilder("nwr[\"amenity\"=\"bicycle_parking\"]$bb;")
        if (withRepair) parts.append("nwr[\"amenity\"=\"bicycle_repair_station\"]$bb;")
        if (withCharging) {
            parts.append("nwr[\"amenity\"=\"charging_station\"][\"bicycle\"~\"yes|designated\"]$bb;")
            parts.append("nwr[\"amenity\"=\"charging_station\"][\"scooter\"~\"yes|designated\"]$bb;")
        }
        return "[out:json][timeout:25];($parts);out center tags 500;"
    }

    fun parse(json: String): List<ParkingSpot> {
        val root = JSONObject(json)
        val els = root.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<ParkingSpot>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tagsObj = e.optJSONObject("tags") ?: continue
            val tags = tagsObj.keys().asSequence().associateWith { tagsObj.optString(it) }
            val lat = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val kind = when (tags["amenity"]) {
                "bicycle_parking" -> SpotKind.PARKING
                "bicycle_repair_station" -> SpotKind.REPAIR
                "charging_station" -> SpotKind.CHARGING
                else -> continue
            }
            out += ParkingSpot(
                id = e.optString("type") + "/" + e.optLong("id"),
                kind = kind,
                lat = lat, lon = lon,
                name = tags["name"],
                type = tags["bicycle_parking"],
                capacity = tags["capacity"]?.trim()?.toIntOrNull(),
                covered = when (tags["covered"]) { "yes" -> true; "no" -> false; else -> null },
                fee = tags["fee"],
                access = tags["access"],
                tags = tags.filterKeys { it in setOf("operator", "opening_hours", "description", "surveillance", "lit", "socket:schuko", "level") },
            )
        }
        return out.distinctBy { it.id }
    }

    /** Overpass sometimes answers 200 with an HTML/XML error page. */
    fun isError(body: String): Boolean = !body.trimStart().startsWith("{") || body.contains("\"remark\": \"runtime error")
}

class OverpassClient(
    private val http: Http,
    private val endpointList: () -> List<String> = { com.elect.riderange.core.ServiceUrls.DEFAULT_OVERPASS },
    private val limiter: RateLimiter = RateLimiter(2000),
) {
    suspend fun fetch(s: Double, w: Double, n: Double, e: Double, withRepair: Boolean, withCharging: Boolean): List<ParkingSpot> {
        val q = "data=" + Text.urlEncode(OverpassParser.query(s, w, n, e, withRepair, withCharging))
        var lastError = "Bike parking server unavailable"
        for (url in endpointList()) {
            try {
                val r = limiter.run { http.post(url, q, "application/x-www-form-urlencoded") }
                if (r.code == 200 && !OverpassParser.isError(r.body)) return OverpassParser.parse(r.body)
                lastError = if (r.code == 429) "Bike parking server is busy (rate limit)" else "Bike parking server error ${r.code}"
            } catch (ex: IOException) {
                lastError = "No connection to the bike parking server"
            }
        }
        throw ServiceException(lastError)
    }
}
