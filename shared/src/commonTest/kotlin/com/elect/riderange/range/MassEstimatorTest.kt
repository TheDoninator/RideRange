package com.elect.riderange.range

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.trips.Sample
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertFalse
import com.elect.riderange.testing.assertNotNull
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic 1 Hz rides with a known mass, generated with the same physics the estimator inverts. */
class MassEstimatorTest {
    private val p = RideParams()

    /**
     * [gradeAt]/[speedAt] give the terrain and speed profile over time (s). Power noise ±[powerNoise] W,
     * elevation noise ±[eleNoise] m, speed noise ±0.1 m/s.
     */
    private fun ride(
        massKg: Double, seconds: Int, seed: Int, powerNoise: Double = 20.0, eleNoise: Double = 0.3,
        gradeAt: (Int) -> Double, speedAt: (Int) -> Double,
    ): List<Sample> {
        val r = Random(seed)
        var pos = LatLon(37.1, -113.6)
        var ele = 800.0
        val out = ArrayList<Sample>()
        for (t in 0..seconds) {
            val v = speedAt(t)
            val a = speedAt(t + 1) - speedAt(t - 1)
            val g = gradeAt(t)
            val wheel = (massKg * 9.81 * (p.crr + g) + massKg * a / 2) * v + 0.5 * p.airDensity * p.cdA * v * v * v
            val power = wheel / p.drivetrainEff + (r.nextDouble() * 2 - 1) * powerNoise
            val volts = 38.0
            out += Sample(
                t = 1_000_000L + t * 1000L, lat = pos.lat, lon = pos.lon, gpsAlt = ele, ele = ele + (r.nextDouble() * 2 - 1) * eleNoise,
                gpsSpeed = v, scooterSpeed = v + (r.nextDouble() * 2 - 1) * 0.1, voltage = volts, current = power / volts,
                batteryPct = 80.0, tempC = 20.0, odometerM = null,
            )
            pos = Geo.destination(pos, 90.0, v)
            ele += g * v
        }
        return out
    }

    /** Rolling hills: grade swings between about −4 % and +6 %, plus speed changes. */
    private val hills: (Int) -> Double = { t -> 0.01 + 0.05 * sin(2 * PI * t / 240.0) }
    private val varied: (Int) -> Double = { t -> 7.0 + 1.5 * sin(2 * PI * t / 70.0) }

    @Test
    fun recoversMassOnHills() {
        for ((mass, seed) in listOf(101.0 to 1, 85.0 to 2, 120.0 to 3)) {
            val est = MassEstimator.estimateTrip(ride(mass, 900, seed, gradeAt = hills, speedAt = varied), p)
            assertNotNull(est); est!!
            assertTrue("identifiable for $mass", est.identifiable)
            assertEquals("mass $mass got ${est.massKg}", mass, est.massKg, mass * 0.05)
            assertEquals(p.crr, est.crrImplied, 0.006)
        }
    }

    @Test
    fun accelerationsAloneAlsoIdentify() {
        // Flat, but repeated speed-ups (stop-and-go traffic).
        val est = MassEstimator.estimateTrip(ride(101.0, 900, 4, gradeAt = { 0.0 }, speedAt = { t -> 6.0 + 2.5 * sin(2 * PI * t / 20.0) }), p)!!
        assertTrue(est.identifiable)
        assertEquals(101.0, est.massKg, 101.0 * 0.05)
    }

    @Test
    fun flatSteadyCruisingIsNotConfident() {
        val est = MassEstimator.estimateTrip(ride(101.0, 900, 5, gradeAt = { 0.0 }, speedAt = { 7.5 }), p)
        // Either no fit at all or flagged as not identifiable; never trusted.
        assertTrue(est == null || !est.identifiable)
        assertNull(MassEstimator.combine(listOfNotNull(est)))
    }

    @Test
    fun noScooterDataIsIgnored() {
        val noPower = ride(101.0, 600, 6, gradeAt = hills, speedAt = varied).map { it.copy(voltage = null, current = null) }
        assertTrue(MassEstimator.points(noPower, p).isEmpty())
        assertNull(MassEstimator.estimateTrip(noPower, p))
    }

    @Test
    fun outliersDontMoveIt() {
        val r = ride(101.0, 900, 7, gradeAt = hills, speedAt = varied).toMutableList()
        // 5 % of samples with wild power spikes (BMS glitches).
        val rnd = Random(9)
        for (i in r.indices) if (rnd.nextDouble() < 0.05) r[i] = r[i].copy(current = (r[i].current ?: 0.0) * 3 + 10)
        val est = MassEstimator.estimateTrip(r, p)!!
        assertEquals(101.0, est.massKg, 101.0 * 0.05)
    }

    @Test
    fun combinesTripsAndNeedsTwoForConfidence() {
        val t1 = MassEstimator.estimateTrip(ride(101.0, 900, 11, gradeAt = hills, speedAt = varied), p)!!
        val t2 = MassEstimator.estimateTrip(ride(101.0, 900, 12, gradeAt = hills, speedAt = varied), p)!!
        val flat = MassEstimator.estimateTrip(ride(101.0, 900, 13, gradeAt = { 0.0 }, speedAt = { 7.5 }), p)
        val one = MassEstimator.combine(listOf(t1))!!
        assertFalse(one.confident)
        val two = MassEstimator.combine(listOfNotNull(t1, t2, flat))!!
        assertEquals(2, two.trips)
        assertTrue(two.confident)
        assertEquals(101.0, two.massKg, 101.0 * 0.05)
        assertTrue(two.seKg < one.seKg + 1e-9)
        assertEquals(101.0 - MassEstimator.SCOOTER_KG, two.riderPlusCargoKg, 101.0 * 0.05)
    }

    @Test
    fun scatterBetweenTripsWidensUncertainty() {
        val a = TripMass(95.0, 1.0, 500, 0.4, true, 0.015)
        val b = TripMass(115.0, 1.0, 500, 0.4, true, 0.015)
        val c = MassEstimator.combine(listOf(a, b))!!
        assertEquals(105.0, c.massKg, 1e-9)
        assertTrue("scatter should dominate, got ${c.seKg}", c.seKg > 5)
        assertFalse(c.confident)
    }

    @Test
    fun gradeCoefficientCrossCheckAndEffectiveMass() {
        val physics = PhysicsModel(p.copy(massKg = 101.0), flatWhPerMi = 16.0)
        // A learned model that matches unscaled physics for 101 kg gives back ~101 kg.
        val c2 = 101.0 * 9.81 * 0.01 * Geo.M_PER_MI / 3600.0 / p.drivetrainEff
        val learned = LearnedModel(doubleArrayOf(10.0, 0.1, c2, 1.0, 0.0), 30.0, 1.0)
        assertEquals(101.0, MassEstimator.massFromGradeCoef(learned, p)!!, 1e-6)
        assertNull(MassEstimator.massFromGradeCoef(LearnedModel(doubleArrayOf(10.0, 0.1, -1.0, 1.0, 0.0), 30.0, 1.0), p))
        assertTrue(physics.whPerMi(8.0, 0.05) > 0)
        // 165 lb + 24.5 + 2 kg.
        assertEquals(165 / 2.20462 + 26.5, MassEstimator.effectiveMass(165.0, 2.0, null, true), 1e-9)
        val conf = MassEstimate(110.0, 3.0, 3, true)
        assertEquals(110.0, MassEstimator.effectiveMass(165.0, 2.0, conf, true), 1e-9)
        assertEquals(165 / 2.20462 + 26.5, MassEstimator.effectiveMass(165.0, 2.0, conf, false), 1e-9)
        assertEquals(165 / 2.20462 + 26.5, MassEstimator.effectiveMass(165.0, 2.0, conf.copy(confident = false), true), 1e-9)
    }
}
