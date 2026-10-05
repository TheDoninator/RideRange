package com.elect.riderange.core

/** Texts for the privacy policy and licences screens (also summarised in the README). */
object Legal {
    const val PRIVACY = """RideRange keeps your data on this phone. There is no account, no analytics and no advertising.

What stays on the phone: your settings and vehicles, trips (GPS track, speed, battery and power readings), the learned range model and the bike-parking cache. Uninstalling the app deletes all of it.

What the app sends, and to whom:
• Map tiles: your visible map area to the map server (default OpenFreeMap).
• Route planning: start and destination to the routing server (default brouter.de).
• Place search: what you type, plus your rough area to rank nearby results, to the search server (default OpenStreetMap Nominatim). Your state/city is looked up there or with Android's geocoder for the Rules tab.
• Bike parking: the visible map area to an Overpass server.
• After a trip: up to 300 points of the track to Open-Meteo for terrain elevation, and one point for the temperature.
• Only if you turn it on: trip uploads to your own GitHub repository with your own token, and the "check for updates" request to the GitHub repository you enter.

Bluetooth: the app talks only to the vehicle you connect, read-only. Location is used while the app is open and, during a recorded ride or navigation, by a foreground service with a visible notification.

All server addresses can be changed in Settings → Servers."""

    data class Credit(val name: String, val use: String, val licence: String, val url: String)

    val CREDITS = listOf(
        Credit("OpenStreetMap contributors", "Map data, routing, search and bike parking", "ODbL 1.0", "https://www.openstreetmap.org/copyright"),
        Credit("OpenFreeMap / OpenMapTiles", "Vector map tiles and style", "OpenMapTiles: CC-BY 4.0 (style), data ODbL", "https://openfreemap.org"),
        Credit("BRouter", "Bike routing engine (brouter.de server); the bundled profiles are based on its trekking profile", "MIT", "https://github.com/abrensch/brouter"),
        Credit("Nominatim", "Place search and reverse geocoding", "GPL-2.0 (server software); data ODbL", "https://nominatim.org"),
        Credit("Overpass API", "Bike parking queries", "AGPL-3.0 (server software); data ODbL", "https://overpass-api.de"),
        Credit("Open-Meteo", "Elevation and temperature", "CC-BY 4.0 (data)", "https://open-meteo.com"),
        Credit("MapLibre Native", "Map rendering", "BSD-2-Clause", "https://maplibre.org"),
        Credit("AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines", "App framework", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
        Credit("pOnewheel (community documentation)", "Onewheel characteristic list and value formats (re-implemented, not copied)", "MIT", "https://github.com/ponewheel/android-ponewheel"),
        Credit("VESC packet format (public documentation)", "VESC frame/CRC/COMM_GET_VALUES layout (own implementation)", "-", "https://vesc-project.com"),
    )

    const val TRADEMARKS = "Segway, Ninebot, Onewheel, Future Motion and VESC are trademarks of their owners. RideRange is an independent app, not affiliated with or endorsed by them."
}
