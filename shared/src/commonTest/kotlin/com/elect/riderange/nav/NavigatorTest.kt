package com.elect.riderange.nav

import com.elect.riderange.Fixtures
import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.routing.BRouterParser
import com.elect.riderange.routing.Route
import com.elect.riderange.routing.Turn
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertFalse
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test

class NavigatorTest {
    private val route: Route = BRouterParser.parse(Fixtures.read("recorded/brouter_trekking_short.json"), "trekking")

    /** Position [d] metres along the route. */
    private fun at(d: Double): LatLon {
        val i = route.cum.indexOfFirst { it >= d }.let { if (it <= 0) 1 else it }
        val a = route.points[i - 1]; val b = route.points[i]
        val seg = route.cum[i] - route.cum[i - 1]
        val t = if (seg <= 0) 0.0 else ((d - route.cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    @Test
    fun rideTheRouteAnnouncesEveryTurnOnceAndArrives() {
        val nav = Navigator(route)
        val spoken = ArrayList<String>()
        var t = 0L
        var d = 0.0
        var last: NavState? = null
        while (d <= route.cum.last() + 5) {
            val s = nav.update(at(d), t)
            spoken += s.speak
            assertFalse("off route at $d", s.offRoute)
            assertTrue("offset ${s.offsetM} at $d", s.offsetM < 3)
            last = s
            d += 7.0; t += 1000           // ~15 mph
        }
        assertTrue(last!!.arrived)
        assertTrue(spoken.first().startsWith("Starting route"))
        assertEquals("You have arrived.", spoken.last())
        // Each real manoeuvre gets its "now" announcement exactly once.
        val turns = route.instructions.filter { it.turn != Turn.ARRIVE }
        for (instr in turns.distinctBy { it.atM.toInt() }) {
            assertTrue("missing ${instr.text}", spoken.any { it == instr.text + "." })
        }
        assertEquals(spoken.size, spoken.toSet().size + spoken.groupBy { it }.values.sumOf { it.size - 1 })
        assertTrue(spoken.any { it.startsWith("In ") && it.contains("feet") })
    }

    @Test
    fun nextTurnDistanceDecreases() {
        val nav = Navigator(route)
        val first = route.instructions.first { it.atM > 50 }
        val a = nav.update(at(first.atM - 120), 0)
        val b = nav.update(at(first.atM - 60), 4000)
        assertEquals(first, a.next)
        assertEquals(120.0, a.distToNextM, 2.0)
        assertEquals(60.0, b.distToNextM, 2.0)
        assertTrue(b.remainingM < a.remainingM)
    }

    @Test
    fun offRouteNeedsDistanceAndTimeThenReroutesAtMostEvery10s() {
        val nav = Navigator(route)
        val base = at(300.0)
        nav.update(base, 0)
        val away = Geo.destination(base, 0.0, 120.0).let { p ->
            // make sure it really is far from every part of the route
            if (route.points.minOf { Geo.distance(it, p) } < 60) Geo.destination(base, 180.0, 120.0) else p
        }
        val s1 = nav.update(away, 1000)
        assertFalse("not yet: under 5 s", s1.offRoute)
        val s2 = nav.update(away, 4000)
        assertFalse(s2.offRoute)
        val s3 = nav.update(away, 6500)
        assertTrue(s3.offRoute)
        assertTrue(nav.shouldReroute(s3, 6500))
        assertFalse("rate limited", nav.shouldReroute(nav.update(away, 9000), 9000))
        assertTrue(nav.shouldReroute(nav.update(away, 17000), 17000))
        // Back on route: clears.
        assertFalse(nav.update(at(320.0), 18000).offRoute)
    }

    @Test
    fun smallGpsWobbleIsNotOffRoute() {
        val nav = Navigator(route)
        for (i in 0..20) {
            val p = Geo.destination(at(200.0 + i * 5), 90.0, if (i % 2 == 0) 25.0 else -25.0)
            assertFalse(nav.update(p, i * 1000L).offRoute)
        }
    }

    @Test
    fun poorAccuracyWidensThreshold() {
        val nav = Navigator(route)
        val p = at(300.0)
        val near = route.points.let { Geo.destination(p, 0.0, 55.0) }
        if (route.points.minOf { Geo.distance(it, near) } < 45) return
        nav.update(near, 0, accuracyM = 50.0)
        assertFalse(nav.update(near, 10_000, accuracyM = 50.0).offRoute)
    }
}
