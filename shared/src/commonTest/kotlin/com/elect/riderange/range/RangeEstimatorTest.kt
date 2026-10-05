package com.elect.riderange.range

import com.elect.riderange.core.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEstimatorTest {
    private val est = RangeEstimator()
    private val mi = Geo.M_PER_MI

    @Test
    fun fullBatteryDefaults() {
        // (100 − 10) % × 551 Wh × 0.95 = 471.1 Wh; / 16 Wh/mi = 29.45 mi road range.
        val r = est.estimate(100.0, 16.0)
        assertEquals(471.105, r.usableWh, 0.01)
        assertEquals(29.44, r.roadRangeM / mi, 0.01)
        assertEquals(29.44 / 1.25, r.oneWayRadiusM / mi, 0.01)
        assertEquals(29.44 / 1.25 / 2, r.roundTripRadiusM / mi, 0.01)
    }

    @Test
    fun reserveAndEmpty() {
        assertEquals(0.0, est.estimate(10.0, 16.0).roadRangeM, 1e-9)
        assertEquals(0.0, est.estimate(4.0, 16.0).oneWayRadiusM, 1e-9)
        assertEquals(0.0, est.estimate(-5.0, 16.0).usableWh, 1e-9)
        // Over 100 % is clamped.
        assertEquals(est.estimate(100.0, 16.0).usableWh, est.estimate(130.0, 16.0).usableWh, 1e-9)
    }

    @Test
    fun scalesLinearly() {
        val a = est.estimate(55.0, 16.0)
        val b = est.estimate(55.0, 32.0)
        assertEquals(a.roadRangeM / 2, b.roadRangeM, 1e-6)
        val c = est.estimate(100.0, 16.0)
        assertEquals(c.usableWh / 2, est.estimate(55.0, 16.0).usableWh, 1e-6)
    }

    @Test
    fun customConfig() {
        val e = RangeEstimator(RangeConfig(packWh = 500.0, usableFraction = 1.0, reservePct = 0.0, detourFactor = 1.0))
        val r = e.estimate(50.0, 10.0)
        assertEquals(250.0, r.usableWh, 1e-9)
        assertEquals(25.0, r.roadRangeM / mi, 1e-9)
        assertEquals(25.0, r.oneWayRadiusM / mi, 1e-9)
        assertEquals(12.5, r.roundTripRadiusM / mi, 1e-9)
    }

    @Test
    fun usesModelAtTypicalSpeedAndGrade() {
        val m = PhysicsModel(RideParams(), 16.0)
        val flat = est.estimate(80.0, m, RideParams().cruiseMps, 0.0)
        assertEquals(est.estimate(80.0, 16.0).roadRangeM, flat.roadRangeM, 1.0)
        // Hilly terrain costs more (regen returns less than the climb took).
        val hilly = est.estimate(80.0, m, RideParams().cruiseMps, 0.04)
        assertTrue(hilly.roadRangeM < flat.roadRangeM)
        // Faster costs more (aero).
        val fast = est.estimate(80.0, m, 10.0, 0.0)
        assertTrue(fast.roadRangeM < flat.roadRangeM)
    }

    @Test
    fun batteryPercentages() {
        // 52.3 Wh is 10 % of 523.45 Wh (551 × 0.95).
        assertEquals(10.0, est.pctFor(52.345), 0.01)
        assertEquals(70.0, est.pctAfter(80.0, 52.345), 0.01)
        assertTrue(est.reachable(50.0, 100.0))
        assertFalse(est.reachable(20.0, 100.0))   // only 52 Wh above the 10 % reserve
    }

    @Test
    fun chooserFallsBackUntilEnoughData() {
        val cfg = RangeConfig(); val p = RideParams()
        assertEquals("Default model", ModelChooser.choose(cfg, p, null, null, 0.0).label)
        val avg = ModelChooser.choose(cfg, p, null, 20.0, 5.0)
        assertEquals(20.0, avg.whPerMi(p.cruiseMps, 0.0), 1e-6)
        val few = LearnedModel(doubleArrayOf(10.0, 0.1, 2.0, 1.0, 0.0), 12.0, 1.0)
        assertTrue(ModelChooser.choose(cfg, p, few, 20.0, 12.0) is PhysicsModel)
        val many = LearnedModel(doubleArrayOf(10.0, 0.1, 2.0, 1.0, 0.0), 25.0, 1.0)
        assertTrue(ModelChooser.choose(cfg, p, many, 20.0, 25.0) === many)
    }
}
