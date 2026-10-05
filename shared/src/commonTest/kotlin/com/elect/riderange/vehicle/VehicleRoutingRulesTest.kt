package com.elect.riderange.vehicle

import com.elect.riderange.Fixtures
import com.elect.riderange.core.ServiceUrls
import com.elect.riderange.core.UpdateCheck
import com.elect.riderange.core.LatLon
import com.elect.riderange.routing.RouteMode
import com.elect.riderange.routing.RoutePlans
import com.elect.riderange.rules.BoardRules
import com.elect.riderange.rules.Regulations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleRoutingRulesTest {
    // ---- routing profile per vehicle type ----

    @Test fun onewheelsGetTheTrailProfilesScootersKeepTheirs() {
        val ow = RouteMode.entries.associateWith { RoutePlans.requests(it, VehicleClass.ONEWHEEL).map { r -> r.profile } }
        val sc = RouteMode.entries.associateWith { RoutePlans.requests(it, VehicleClass.KICK_SCOOTER).map { r -> r.profile } }
        assertEquals(listOf(RoutePlans.OW_TRAILS), ow[RouteMode.TRAILS])
        assertEquals(listOf(RoutePlans.OW_TRAFFIC), ow[RouteMode.TRAFFIC])
        assertEquals(listOf(RoutePlans.TRAILS), sc[RouteMode.TRAILS])
        assertEquals(listOf(RoutePlans.TRAFFIC), sc[RouteMode.TRAFFIC])
        assertTrue(ow[RouteMode.BATTERY]!!.none { it == RoutePlans.TRAILS || it == RoutePlans.TRAFFIC })
        assertTrue(sc[RouteMode.BATTERY]!!.none { it == RoutePlans.OW_TRAILS || it == RoutePlans.OW_TRAFFIC })
        assertEquals("fastbike", ow[RouteMode.BATTERY]!!.first())   // baseline for "saves X Wh"
        assertEquals(RoutePlans.requests(RouteMode.TRAILS), RoutePlans.requests(RouteMode.TRAILS, VehicleClass.KICK_SCOOTER))
        assertEquals("trekking", RoutePlans.fallback(RoutePlans.OW_TRAILS))
        assertEquals("safety", RoutePlans.fallback(RoutePlans.OW_TRAFFIC))
    }

    @Test fun bundledProfilesExistAndDifferOnSurfacesNotSteps() {
        for (p in listOf(RoutePlans.TRAILS, RoutePlans.TRAFFIC, RoutePlans.OW_TRAILS, RoutePlans.OW_TRAFFIC)) {
            val text = Fixtures.main("assets/" + RoutePlans.asset(p)!!)
            // Steps are avoided for every vehicle type.
            assertTrue(p, text.contains("assign   allow_steps              = false"))
            assertTrue(p, text.contains("if ( highway=steps ) then ( if allow_steps then 40 else 10000 )"))
        }
        val scooter = Fixtures.main("assets/brouter/scooter-trails.brf")
        val board = Fixtures.main("assets/brouter/onewheel-trails.brf")
        // Scooters: rough tracks and MTB scale 2+ are impassable; boards may use them.
        assertTrue(scooter.contains("( tracktype=grade4 ) then 10000"))
        assertTrue(scooter.contains("mtb:scale=2|3|4|5|6 ) then 10000"))
        assertFalse(board.contains("( tracktype=grade4 ) then 10000"))
        assertTrue(board.contains("mtb:scale=3|4|5|6 ) then 10000"))
        assertTrue(board.contains("if isunpaved then 1.3"))
        assertTrue(scooter.contains("if isunpaved then 8.0"))
        // The least-traffic board profile keeps the big main-road penalties.
        assertTrue(Fixtures.main("assets/brouter/onewheel-traffic.brf").contains("if isbike then 4 else 10000"))
    }

    @Test fun routeUrlUsesTheConfiguredBase() {
        val u = RoutePlans.url("https://route.example.org/brouter/", LatLon(37.1, -113.5), LatLon(37.2, -113.6), "trekking", 1)
        assertTrue(u.startsWith("https://route.example.org/brouter?lonlats=-113.500000,37.100000|"))
    }

    // ---- Rules tab: one-wheel boards ----

    private val regs by lazy { Regulations.parse(Fixtures.main("assets/regulations.json")) }

    @Test fun utahNamesTheOnewheelCategory() {
        val b = regs.boards(regs.state("UT"))!!
        assertEquals("Self-balancing electric skateboard", b.category)
        assertTrue(b.specificallyAddressed)
        assertEquals("verified", b.confidence)
        assertTrue(b.text.contains("41-6a-102"))
        assertTrue(b.text.contains("high power electric device"))
        assertTrue(b.sources.any { it.url.contains("le.utah.gov") })
    }

    @Test fun californiaUsesElectricallyMotorizedBoard() {
        val b = regs.boards(regs.state("CA"))!!
        assertEquals("Electrically motorized board", b.category)
        assertTrue(b.text.contains("16 or older"))
    }

    @Test fun otherStatesSayNotSpecificallyAddressed() {
        for (code in listOf("TX", "NY", "AL", "DC")) {
            val b = regs.boards(regs.state(code))!!
            assertEquals(code, BoardRules.NOT_ADDRESSED, b.category)
            assertFalse(b.specificallyAddressed)
        }
        assertEquals(51, regs.states.count { it.boards != null })
        assertNotNull(regs.boardsDefault)
    }

    @Test fun rulesTabLeadsWithTheActiveVehicleClass() {
        assertEquals("boards", regs.primaryFor(VehicleClass.ONEWHEEL))
        assertEquals("scooter", regs.primaryFor(VehicleClass.KICK_SCOOTER))
        assertEquals("scooter", regs.primaryFor(VehicleClass.OTHER))
    }

    // ---- public release prep ----

    @Test fun serviceUrlsDefaultAndOverride() {
        val d = ServiceUrls()
        assertEquals(ServiceUrls.DEFAULT_BROUTER, d.brouterUrl)
        assertEquals(ServiceUrls.DEFAULT_OVERPASS, d.overpassUrls)
        val c = ServiceUrls(brouter = "https://r.example.com/brouter/", overpass = "https://o1.example.com/api/interpreter, https://o2.example.com/api/interpreter",
            nominatim = "not a url")
        assertEquals("https://r.example.com/brouter", c.brouterUrl)
        assertEquals(2, c.overpassUrls.size)
        assertEquals(ServiceUrls.DEFAULT_NOMINATIM, c.nominatimUrl)   // invalid -> default
        assertEquals(listOf("Search"), c.invalid())
    }

    @Test fun updateCheckComparesVersions() {
        assertTrue(UpdateCheck.isNewer("v1.2.0", "1.1.0"))
        assertTrue(UpdateCheck.isNewer("1.10", "1.9.3"))
        assertFalse(UpdateCheck.isNewer("v1.1.0", "1.1.0"))
        assertFalse(UpdateCheck.isNewer("v1.0.9", "1.1.0"))
        assertFalse(UpdateCheck.isNewer("nightly", "1.1.0"))
        val r = UpdateCheck.parse("""{"tag_name":"v1.2.0","name":"RideRange 1.2.0","html_url":"https://github.com/o/r/releases/tag/v1.2.0",
            "assets":[{"name":"notes.txt","browser_download_url":"x"},{"name":"RideRange-1.2.0.apk","browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/RideRange-1.2.0.apk"}]}""")!!
        assertEquals("v1.2.0", r.tag)
        assertTrue(r.apkUrl!!.endsWith(".apk"))
        assertNull(UpdateCheck.parse("{}"))
        assertEquals("https://api.github.com/repos/o/r/releases/latest", UpdateCheck.latestUrl("o", "r"))
    }

    @Test fun noPersonalDefaultsInSourcesOrAssets() {
        val app = Fixtures.main("java/com/elect/riderange/data/Settings.kt") + Fixtures.main("java/com/elect/riderange/upload/UploadCore.kt") +
            Fixtures.main("java/com/elect/riderange/core/Http.kt")
        assertFalse(app.contains("TheDoninator"))
        assertFalse(app.contains("personal e-scooter app"))
    }
}
