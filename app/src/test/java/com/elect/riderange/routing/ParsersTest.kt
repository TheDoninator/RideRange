package com.elect.riderange.routing

import com.elect.riderange.Fixtures
import com.elect.riderange.core.LatLon
import com.elect.riderange.parking.Cell
import com.elect.riderange.parking.OverpassParser
import com.elect.riderange.parking.SpotKind
import com.elect.riderange.search.NominatimParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers against real responses recorded from BRouter / Overpass / Nominatim on 2026-10-04. */
class ParsersTest {
    @Test
    fun brouterShortRoute() {
        val r = BRouterParser.parse(Fixtures.read("recorded/brouter_trekking_short.json"), "trekking")
        assertEquals(2658.0, r.lengthM, 0.1)
        assertEquals(34.0, r.ascendM, 0.1)
        assertEquals(631.0, r.timeS, 0.1)
        assertEquals(68, r.points.size)
        assertEquals(r.points.size, r.elevations.size)
        assertTrue(r.elevations.all { it != null && it in 700.0..1000.0 })
        // 9 voice hints + arrival.
        assertEquals(10, r.instructions.size)
        assertEquals(Turn.LEFT, r.instructions[0].turn)
        assertEquals(Turn.RIGHT, r.instructions[1].turn)
        assertEquals(Turn.SLIGHT_LEFT, r.instructions[7].turn)
        assertEquals(Turn.ARRIVE, r.instructions.last().turn)
        // Our own geometry length agrees with the server's.
        assertEquals(r.lengthM, r.cum.last(), 30.0)
        assertTrue(r.instructions.zipWithNext().all { (a, b) -> a.atM <= b.atM })
        assertTrue(r.highwayMix.isNotEmpty())
        assertEquals(37.0968, r.points.first().lat, 0.001)
    }

    @Test
    fun brouterAlternativesDiffer() {
        val f = BRouterParser.parse(Fixtures.read("recorded/brouter_fastbike_0.json"), "fastbike")
        val s = BRouterParser.parse(Fixtures.read("recorded/brouter_safety_0.json"), "safety")
        val c = BRouterParser.parse(Fixtures.read("recorded/brouter_scooter_trails.json"), "@trails")
        assertEquals(6277.0, f.lengthM, 0.1)
        assertEquals(6748.0, s.lengthM, 0.1)
        assertEquals(6249.0, c.lengthM, 0.1)
        assertTrue(s.lengthM > f.lengthM)
        assertTrue(c.instructions.isNotEmpty())
        assertTrue(BRouterParser.looksLikeError("operation killed by thread-priority-watchdog"))
        assertFalse(BRouterParser.looksLikeError(Fixtures.read("recorded/brouter_fastbike_0.json")))
    }

    @Test
    fun routeCacheRoundTrip() {
        val r = BRouterParser.parse(Fixtures.read("recorded/brouter_trekking_short.json"), "trekking")
        val c = RouteCache.fromJson(RouteCache.toJson(r, 37.1, -113.5, "Park"))!!
        assertEquals(r.points.size, c.route.points.size)
        assertEquals(r.instructions.map { it.turn }, c.route.instructions.map { it.turn })
        assertEquals(r.cum.last(), c.route.cum.last(), 1e-6)
        assertEquals("Park", c.name)
        assertEquals(null, RouteCache.fromJson("not json"))
    }

    @Test
    fun routeUrl() {
        val u = RoutePlans.url("https://brouter.de/brouter", LatLon(37.0965, -113.5684), LatLon(37.13, -113.53), "trekking", 1)
        assertEquals("https://brouter.de/brouter?lonlats=-113.568400,37.096500|-113.530000,37.130000&profile=trekking&alternativeidx=1&format=geojson&timode=3", u)
        assertEquals(5, RoutePlans.requests(RouteMode.BATTERY).size)
        assertEquals("trekking", RoutePlans.fallback(RoutePlans.TRAILS))
        assertEquals("safety", RoutePlans.fallback(RoutePlans.TRAFFIC))
    }

    @Test
    fun overpassParking() {
        val spots = OverpassParser.parse(Fixtures.read("recorded/overpass_stgeorge.json"))
        assertTrue("got ${spots.size}", spots.size >= 10)
        assertTrue(spots.all { it.kind == SpotKind.PARKING })
        val s = spots.first { it.id == "node/4465438178" }
        assertEquals(22, s.capacity)
        assertEquals(true, s.covered)
        assertEquals("Bike stands", s.typeLabel)
        assertEquals(37.1024793, s.lat, 1e-7)
        assertTrue(OverpassParser.isError("<?xml version=\"1.0\"?><html>runtime error</html>"))
        assertFalse(OverpassParser.isError(Fixtures.read("recorded/overpass_stgeorge.json")))
        val q = OverpassParser.query(37.0, -113.6, 37.1, -113.5, withRepair = true, withCharging = false)
        assertTrue(q.contains("bicycle_parking") && q.contains("bicycle_repair_station") && !q.contains("charging"))
    }

    @Test
    fun overpassCells() {
        val cells = Cell.cover(37.08, -113.60, 37.12, -113.54)
        assertTrue(cells.size in 4..12)
        assertTrue(cells.any { 37.10 in it.south..it.north && -113.57 in it.west..it.east })
        assertEquals(16, Cell.cover(36.0, -115.0, 38.0, -112.0).size)
    }

    @Test
    fun nominatim() {
        val places = NominatimParser.parseSearch(Fixtures.read("recorded/nominatim_search_pioneer_park.json"))
        assertEquals("Pioneer Park", places.first().name)
        assertTrue(places.first().detail.contains("St. George"))
        val region = NominatimParser.parseReverse(Fixtures.read("recorded/nominatim_reverse_stgeorge.json"))
        assertEquals("UT", region.stateCode)
        assertEquals("St. George", region.city)
        assertEquals("US", region.countryCode)
        assertNotNull(region.stateName)
    }
}
