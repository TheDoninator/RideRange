package com.elect.riderange.range

import com.elect.riderange.Fixtures
import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.routing.BRouterParser
import com.elect.riderange.routing.BatterySaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyModelTest {
    private val p = RideParams()
    private val m = PhysicsModel(p, 16.0)

    @Test
    fun calibratedToFlatSetting() {
        assertEquals(16.0, m.whPerMi(p.cruiseMps, 0.0), 1e-9)
        assertEquals(22.0, PhysicsModel(p, 22.0).whPerMi(p.cruiseMps, 0.0), 1e-9)
    }

    @Test
    fun shape() {
        assertTrue(m.whPerMi(10.0, 0.0) > m.whPerMi(6.0, 0.0))            // aero
        assertTrue(m.whPerMi(8.0, 0.05) > 2 * m.whPerMi(8.0, 0.0))        // 5 % climb is expensive
        assertTrue(m.whPerMi(8.0, -0.06) < 0)                              // steep descent: regen
        // Regen returns much less than the climb costs.
        assertTrue(m.whPerMi(8.0, 0.06) + m.whPerMi(8.0, -0.06) > 2 * m.whPerMi(8.0, 0.0))
        assertTrue(m.whPerMi(8.0, 0.0, tempC = 0.0) > m.whPerMi(8.0, 0.0, tempC = 20.0))
    }

    @Test
    fun priorMatchesPhysicsOnGentleTerrain() {
        val prior = m.priorCoefficients()
        val lm = LearnedModel(prior, 100.0, 0.0)
        for (v in listOf(5.0, 7.0, 9.0)) for (g in listOf(0.0, 0.02, 0.04)) {
            assertEquals("v=$v g=$g", m.whPerMi(v, g), lm.whPerMi(v, g), 0.6)
        }
    }

    @Test
    fun segmentsAndClimb() {
        // 1 km due north climbing 50 m.
        val pts = (0..10).map { Geo.destination(LatLon(37.0, -113.5), 0.0, it * 100.0) }
        val ele = (0..10).map { 800.0 + it * 5.0 }
        val segs = EnergyModel.segmentsOf(pts, ele, 8.0, stepM = 100.0)
        assertEquals(10, segs.size)
        segs.forEach { assertEquals(0.05, it.grade, 1e-3) }
        assertEquals(1000.0, segs.sumOf { it.lengthM }, 1.0)
        val (up, down) = EnergyModel.climbDescent(ele)
        assertEquals(50.0, up, 1e-9); assertEquals(0.0, down, 1e-9)
        // Noise under 2 m is ignored.
        val noisy = listOf(100.0, 101.0, 100.0, 101.5, 100.2, 105.0, 104.0)
        assertEquals(5.0, EnergyModel.climbDescent(noisy).first, 1e-9)
    }

    @Test
    fun stopPenaltyPositiveAndSmall() {
        val s = EnergyModel.stopWh(p)
        assertTrue(s > 0.3 && s < 2.0)
    }

    @Test
    fun batterySaverPicksLowestEnergyRealRoutes() {
        val routes = listOf("fastbike", "trekking", "safety").map {
            BRouterParser.parse(Fixtures.read("recorded/brouter_${it}_0.json"), it, 0)
        }
        val scored = routes.map { BatterySaver.Scored(it, EnergyModel.routeWh(it.points, it.elevations, it.realTurns, m, p)) }
        val choice = BatterySaver.choose(scored)
        assertNotNull(choice)
        assertEquals(scored.minOf { it.wh }, choice!!.best.wh, 1e-9)
        assertTrue(choice.savesWh >= 0)
        // Every candidate is a few-km city ride: plausible energy (≈ 4 mi × 16 Wh/mi ± hills).
        scored.forEach { assertTrue("${it.route.profile} ${it.wh}", it.wh in 30.0..140.0) }
    }

    @Test
    fun batterySaverPrefersFlatterRoute() {
        val flat = route(lengthM = 3000.0, climb = 0.0, profile = "trekking")
        val hilly = route(lengthM = 2800.0, climb = 60.0, profile = "fastbike")
        val s = listOf(hilly, flat).map { BatterySaver.Scored(it, EnergyModel.routeWh(it.points, it.elevations, 0, m, p)) }
        val c = BatterySaver.choose(s)!!
        assertEquals("trekking", c.best.route.profile)
        assertTrue(c.savesWh > 0)
    }

    private fun route(lengthM: Double, climb: Double, profile: String): com.elect.riderange.routing.Route {
        val n = 30
        val pts = (0..n).map { Geo.destination(LatLon(37.1, -113.6), 90.0, lengthM * it / n) }
        // Up then back down to the same height: climb costs more than regen returns.
        val ele = (0..n).map { if (it <= n / 2) 800 + climb * it / (n / 2) else 800 + climb * (n - it) / (n / 2) }
        val cum = (0..n).map { lengthM * it / n }
        return com.elect.riderange.routing.Route(pts, ele, emptyList(), lengthM, climb, 600.0, profile, 0, cum)
    }

    @Test
    fun rollingTerrain() {
        assertEquals(m.whPerMi(8.0, 0.0), EnergyModel.rollingTerrainWhPerMi(m, 8.0, 0.0), 1e-9)
        // Gentle hills where the motor still pulls downhill cancel out (up costs what down saves);
        // steeper ones cost extra because only part of the coasting surplus comes back as regen.
        assertEquals(m.whPerMi(8.0, 0.0), EnergyModel.rollingTerrainWhPerMi(m, 8.0, 0.01), 1e-6)
        assertTrue(EnergyModel.rollingTerrainWhPerMi(m, 8.0, 0.06) > m.whPerMi(8.0, 0.0) * 1.05)
    }
}
