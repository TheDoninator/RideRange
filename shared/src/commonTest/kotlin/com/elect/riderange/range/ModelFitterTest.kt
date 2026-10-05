package com.elect.riderange.range

import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertNotNull
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test
import kotlin.math.abs
import kotlin.random.Random

/** Model fitting against synthetic rides generated from known coefficients. */
class ModelFitterTest {
    private val truth = doubleArrayOf(9.0, 0.12, 3.2, 1.1, 0.25)
    private val prior = PhysicsModel(RideParams(), 16.0).priorCoefficients()

    private fun synth(n: Int, seed: Int, noise: Double, withCold: Boolean = true): List<MeasuredSegment> {
        val r = Random(seed)
        return (0 until n).map {
            val v = 4.0 + r.nextDouble() * 6.0                  // 9–22 mph
            val g = (r.nextDouble() - 0.5) * 0.12                // ±6 %
            val t = if (withCold) 0.0 + r.nextDouble() * 30 else 20.0
            val len = 150.0 + r.nextDouble() * 100
            val y = ModelFitter.features(v, g, t).withIndex().sumOf { (i, x) -> truth[i] * x } + (if (noise > 0) r.nextDouble(-noise, noise) else 0.0)
            MeasuredSegment(len, y * len / 1609.344, v, g, t)
        }
    }

    @Test
    fun recoversKnownCoefficientsFromCleanData() {
        val segs = synth(600, 1, noise = 0.0)
        val fit = ModelFitter.fit(segs, prior, lambda = 0.001)
        val m = assertNotNull(fit.model).let { fit.model!! }
        truth.forEachIndexed { i, t -> assertEquals("coef $i", t, m.coef[i], abs(t) * 0.03 + 0.02) }
        assertTrue(m.rmse < 0.1)
    }

    @Test
    fun noisyDataStillPredictsWell() {
        val segs = synth(800, 2, noise = 3.0)
        val m = ModelFitter.fit(segs, prior).model!!
        val test = synth(200, 99, noise = 0.0)
        val err = test.sumOf { abs(m.whPerMi(it.speedMps, it.grade, it.tempC) - it.whPerMi) } / test.size
        assertTrue("mean abs error $err", err < 1.0)
    }

    @Test
    fun needsTwentyMilesBeforeTrusting() {
        // ~150 segments × ~200 m ≈ 18.6 mi: not enough.
        val few = synth(140, 3, 1.0)
        assertTrue(few.sumOf { it.miles } < 20)
        assertNull(ModelFitter.fit(few, prior).model)
        val enough = synth(200, 3, 1.0)
        assertTrue(enough.sumOf { it.miles } >= 20)
        assertNotNull(ModelFitter.fit(enough, prior).model)
    }

    @Test
    fun regularisationKeepsSparseFeaturesNearPrior() {
        // All rides at 20 °C: the cold coefficient can't be learned, so it stays at the prior.
        val segs = synth(400, 4, noise = 1.0, withCold = false)
        val m = ModelFitter.fit(segs, prior).model!!
        assertEquals(prior[4], m.coef[4], 1e-6)
    }

    @Test
    fun outliersAreDropped() {
        val segs = synth(400, 5, noise = 0.5).toMutableList()
        // GPS glitches / stops: absurd values.
        repeat(10) { segs += MeasuredSegment(200.0, 5.0, 0.5, 0.0, 20.0) }        // speed too low
        repeat(10) { segs += MeasuredSegment(200.0, 40.0, 7.0, 0.0, 20.0) }       // 322 Wh/mi
        repeat(6) { segs += MeasuredSegment(200.0, 4.0, 7.0, 0.01, 20.0) }        // plausible-looking but off (32 Wh/mi)
        val fit = ModelFitter.fit(segs, prior)
        val m = fit.model!!
        assertTrue(fit.dropped >= 20)
        assertEquals(truth[0], m.coef[0], 1.5)
        assertEquals(truth[2], m.coef[2], 0.4)
    }

    @Test
    fun gaussSolves() {
        val a = arrayOf(doubleArrayOf(2.0, 1.0, -1.0), doubleArrayOf(-3.0, -1.0, 2.0), doubleArrayOf(-2.0, 1.0, 2.0))
        val x = ModelFitter.gauss(a, doubleArrayOf(8.0, -11.0, -3.0))
        assertEquals(2.0, x[0], 1e-9); assertEquals(3.0, x[1], 1e-9); assertEquals(-1.0, x[2], 1e-9)
    }

    @Test
    fun accuracySummary() {
        assertEquals(10.0, Accuracy.errorPct(110.0, 100.0)!!, 1e-9)
        assertNull(Accuracy.errorPct(10.0, 0.0))
        assertEquals(6.0, Accuracy.meanAbsPct(listOf(50.0, 4.0, -8.0, 6.0), n = 3)!!, 1e-9)
        assertEquals("Range estimates are within ±6% over your last 3 trips", Accuracy.summary(listOf(50.0, 4.0, -8.0, 6.0), 3))
    }
}
