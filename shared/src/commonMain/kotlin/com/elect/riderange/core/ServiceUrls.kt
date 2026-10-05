package com.elect.riderange.core

/**
 * Every network service sits behind a configurable base URL, so a public release can point at its own or paid
 * hosts: the free public servers (brouter.de, nominatim.openstreetmap.org, overpass-api.de) don't allow heavy
 * traffic from a widely distributed app. Blank = the default.
 */
data class ServiceUrls(
    val brouter: String = "",
    val nominatim: String = "",
    /** One or more Overpass interpreter URLs, comma separated (tried in order). */
    val overpass: String = "",
    val openMeteo: String = "",
    val mapStyle: String = "",
) {
    val brouterUrl: String get() = pick(brouter, DEFAULT_BROUTER)
    val nominatimUrl: String get() = pick(nominatim, DEFAULT_NOMINATIM)
    val openMeteoUrl: String get() = pick(openMeteo, DEFAULT_OPEN_METEO)
    val mapStyleUrl: String get() = mapStyle.trim().ifEmpty { DEFAULT_MAP_STYLE }
    val overpassUrls: List<String> get() = overpass.split(',', ' ', '\n').map { it.trim().trimEnd('/') }.filter { valid(it) }
        .ifEmpty { DEFAULT_OVERPASS }

    /** Fields that are set but aren't http(s) URLs. */
    fun invalid(): List<String> = listOf("Routing" to brouter, "Search" to nominatim, "Weather/elevation" to openMeteo, "Map style" to mapStyle)
        .filter { (_, v) -> v.isNotBlank() && !valid(v.trim()) }.map { it.first } +
        if (overpass.isNotBlank() && overpass.split(',').any { it.isNotBlank() && !valid(it.trim()) }) listOf("Bike parking") else emptyList()

    companion object {
        const val DEFAULT_BROUTER = "https://brouter.de/brouter"
        const val DEFAULT_NOMINATIM = "https://nominatim.openstreetmap.org"
        const val DEFAULT_OPEN_METEO = "https://api.open-meteo.com/v1"
        const val DEFAULT_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        val DEFAULT_OVERPASS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")

        fun valid(url: String): Boolean = Regex("""^https?://[A-Za-z0-9.\-]+(:\d+)?(/\S*)?$""").matches(url)

        private fun pick(v: String, def: String): String = v.trim().trimEnd('/').takeIf { valid(it) } ?: def
    }
}
