package com.elect.riderange.rules

import com.elect.riderange.core.json.JSONArray
import com.elect.riderange.core.json.JSONObject

data class Source(val title: String, val url: String)

/** One topic row ("Sidewalks", "Helmet", …) of a rules card. */
data class RuleItem(val topic: String, val text: String)

data class RuleSet(val items: List<RuleItem>) {
    operator fun get(topic: String): String? = items.firstOrNull { it.topic == topic }?.text
}

/** How a state's law treats one-wheel self-balancing boards (Onewheel, VESC "float" boards). */
data class BoardRules(val category: String, val text: String, val confidence: String, val sources: List<Source>) {
    val specificallyAddressed: Boolean get() = category != NOT_ADDRESSED

    companion object { const val NOT_ADDRESSED = "Not specifically addressed" }
}

data class StateRules(
    val code: String,
    val name: String,
    val reviewed: String,
    /** "verified" = checked against the statute text on the review date; "summary" = condensed summary, check the source. */
    val confidence: String,
    val summary: String,
    val scooter: RuleSet,
    val ebike: RuleSet,
    val sources: List<Source>,
    val boards: BoardRules? = null,
)

data class CityRules(
    val state: String,
    val city: String,
    val aliases: List<String>,
    val reviewed: String,
    val summary: String,
    val scooter: RuleSet,
    val ebike: RuleSet,
    val sources: List<Source>,
)

data class RulesLookup(val state: StateRules?, val city: CityRules?)

/** The bundled regulations dataset (assets/regulations.json) and its lookups. */
class Regulations(
    val reviewed: String,
    val disclaimer: String,
    val states: List<StateRules>,
    val cities: List<CityRules>,
    val boardsDefault: BoardRules? = null,
) {
    /** Rules for a one-wheel board in [state]: its own entry, or the "not specifically addressed" default. */
    fun boards(state: StateRules?): BoardRules? = state?.boards ?: boardsDefault

    /** Which device class heads the Rules tab for the active vehicle. */
    fun primaryFor(vehicle: com.elect.riderange.vehicle.VehicleClass): String = when (vehicle) {
        com.elect.riderange.vehicle.VehicleClass.ONEWHEEL -> "boards"
        com.elect.riderange.vehicle.VehicleClass.E_BIKE -> "ebike"
        com.elect.riderange.vehicle.VehicleClass.E_SKATEBOARD -> "eskate"
        else -> "scooter"
    }


    fun state(codeOrName: String?): StateRules? {
        val k = codeOrName?.trim()?.lowercase() ?: return null
        return states.firstOrNull { it.code.lowercase() == k || it.name.lowercase() == k }
    }

    fun city(stateCode: String?, city: String?): CityRules? {
        val s = stateCode?.uppercase() ?: return null
        val c = norm(city ?: return null)
        return cities.firstOrNull { it.state == s && (norm(it.city) == c || it.aliases.any { a -> norm(a) == c }) }
    }

    fun citiesIn(stateCode: String): List<CityRules> = cities.filter { it.state == stateCode.uppercase() }

    fun lookup(stateCode: String?, city: String?): RulesLookup = RulesLookup(state(stateCode), city(stateCode, city))

    companion object {
        val TOPICS = listOf("Where to ride", "Sidewalks", "Max speed", "Age", "Helmet", "Licence & registration", "Lights", "Parking", "Classes", "Notes")

        private val KEYS = mapOf(
            "where" to "Where to ride", "sidewalk" to "Sidewalks", "maxSpeed" to "Max speed", "age" to "Age",
            "helmet" to "Helmet", "license" to "Licence & registration", "lights" to "Lights", "parking" to "Parking",
            "classes" to "Classes", "notes" to "Notes",
        )

        /** "St. George" == "Saint George" == "st george". */
        fun norm(s: String): String = s.lowercase().replace(Regex("\\bsaint\\b"), "st").replace(Regex("[^a-z0-9]"), "")

        private fun ruleSet(o: JSONObject?): RuleSet {
            if (o == null) return RuleSet(emptyList())
            val items = KEYS.mapNotNull { (k, label) -> o.optString(k).takeIf { it.isNotBlank() }?.let { RuleItem(label, it) } }
            return RuleSet(items.sortedBy { TOPICS.indexOf(it.topic) })
        }

        private fun boardRules(o: JSONObject?): BoardRules? = o?.let {
            BoardRules(it.optString("category", BoardRules.NOT_ADDRESSED), it.optString("text"), it.optString("confidence", "summary"),
                sources(it.optJSONArray("sources")))
        }

        private fun sources(a: JSONArray?): List<Source> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it).let { s -> Source(s.getString("title"), s.getString("url")) } }

        fun parse(json: String): Regulations {
            val root = JSONObject(json)
            val reviewed = root.getString("reviewed")
            val states = root.getJSONArray("states").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    StateRules(
                        code = o.getString("code"), name = o.getString("name"),
                        reviewed = o.optString("reviewed", reviewed), confidence = o.optString("confidence", "summary"),
                        summary = o.optString("summary"),
                        scooter = ruleSet(o.optJSONObject("scooter")), ebike = ruleSet(o.optJSONObject("ebike")),
                        sources = sources(o.optJSONArray("sources")),
                        boards = boardRules(o.optJSONObject("boards")),
                    )
                }
            }
            val cities = root.optJSONArray("cities")?.let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    CityRules(
                        state = o.getString("state"), city = o.getString("city"),
                        aliases = o.optJSONArray("aliases")?.let { al -> (0 until al.length()).map { al.getString(it) } } ?: emptyList(),
                        reviewed = o.optString("reviewed", reviewed), summary = o.optString("summary"),
                        scooter = ruleSet(o.optJSONObject("scooter")), ebike = ruleSet(o.optJSONObject("ebike")),
                        sources = sources(o.optJSONArray("sources")),
                    )
                }
            } ?: emptyList()
            return Regulations(reviewed, root.optString("disclaimer"), states, cities, boardRules(root.optJSONObject("boards_default")))
        }
    }
}
