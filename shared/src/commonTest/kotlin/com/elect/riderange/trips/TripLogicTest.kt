package com.elect.riderange.trips

import com.elect.riderange.core.Geo
import com.elect.riderange.core.LatLon
import com.elect.riderange.range.ModelFitter
import com.elect.riderange.range.PhysicsModel
import com.elect.riderange.range.RideParams
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertFalse
import com.elect.riderange.testing.assertNotNull
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test

class TripLogicTest {
    @Test
    fun vehicleSwitchedOffEndsTrip() {
        val d = VehicleOffDetector()
        var t = 0L
        fun step(data: Boolean, gps: Double?) = d.onSample(data, gps, t).also { t += 1000 }
        repeat(60) { assertFalse(step(true, 7.0)) }               // riding with live scooter data
        repeat(20) { assertFalse(step(false, 6.0)) }              // link drop while moving: never ends
        val ends = (1..40).map { step(false, 0.0) }               // parked, scooter off
        assertEquals(1, ends.count { it })
        assertTrue(ends[30])                                      // after 30 s still and silent
    }

    @Test
    fun noVehicleDataNeverTriggersOff() {
        val d = VehicleOffDetector()
        assertFalse((0..300).any { d.onSample(false, 0.0, it * 1000L) })   // GPS-only trip
    }

    /** Real ride: a 4-minute stop with a few seconds of GPS drift at ~2 mph must still end the trip. */
    @Test
    fun stopSurvivesGpsDrift() {
        val d = TripDetector()
        var t = 0L
        fun feed(v: Double, secs: Int): TripDetector.Event {
            var last = TripDetector.Event.NONE
            repeat(secs) { val e = d.onSpeed(v, t); if (e != TripDetector.Event.NONE) last = e; t += 1000 }
            return last
        }
        assertEquals(TripDetector.Event.START, feed(8.0, 40))
        assertEquals(TripDetector.Event.NONE, feed(0.0, 130))
        assertEquals(TripDetector.Event.NONE, feed(1.0, 4))      // drift
        assertEquals(TripDetector.Event.STOP, feed(0.0, 60))     // 3 min after the stop began
        assertFalse(d.recording)
        assertEquals(TripDetector.Event.START, feed(8.0, 40))    // riding on starts a new trip
    }

    @Test
    fun realRidingCancelsTheStop() {
        val d = TripDetector()
        var t = 0L
        fun feed(v: Double, secs: Int) = (1..secs).map { d.onSpeed(v, t).also { t += 1000 } }.lastOrNull { it != TripDetector.Event.NONE }
        feed(8.0, 40)
        feed(0.0, 170)
        feed(6.0, 10)                                            // rode off
        assertNull(feed(0.0, 170))                               // a fresh stop, not yet 3 min
        assertTrue(d.recording)
    }

    /** Real Max G2 pattern: 1 Hz samples, scooter speed every sample, odometer only refreshed every ~12 s. */
    @Test
    fun distanceWithSlowOdometerUsesScooterSpeed() {
        val start = LatLon(37.08, -113.59)
        val samples = (0..120).map { i ->
            val pos = Geo.destination(start, 90.0, 7.0 * i)
            Sample(t = i * 1000L, lat = pos.lat, lon = pos.lon, gpsAlt = 800.0, ele = 800.0, gpsSpeed = 7.0,
                scooterSpeed = 7.0, odometerM = 1_000_000.0 + 7.0 * (i / 12 * 12))
        }
        val st = TripMath.stats(samples)
        assertEquals(840.0, st.distanceM, 5.0)
        assertEquals(7.0, st.avgSpeed, 0.1)
    }

    /** A synthetic 1 Hz ride: [n] s at [v] m/s going east, climbing [grade], drawing [powerW]. */
    private fun ride(n: Int, v: Double, grade: Double = 0.0, powerW: Double? = 300.0, start: LatLon = LatLon(37.1, -113.6), pct0: Double = 80.0): List<Sample> {
        return (0..n).map { i ->
            val p = Geo.destination(start, 90.0, v * i)
            val volts = 39.0
            Sample(
                t = 1_000_000L + i * 1000L, lat = p.lat, lon = p.lon, gpsAlt = 800 + grade * v * i, ele = 800 + grade * v * i,
                gpsSpeed = v, scooterSpeed = v, voltage = powerW?.let { volts }, current = powerW?.let { it / volts },
                batteryPct = pct0 - i / 100.0, tempC = 22.0, odometerM = null,
            )
        }
    }

    @Test
    fun statsFromPower() {
        val s = TripMath.stats(ride(600, 8.0))
        assertEquals(4800.0, s.distanceM, 5.0)
        assertEquals(600.0, s.durationS, 1e-9)
        assertEquals(8.0, s.avgSpeed, 0.05)
        assertEquals(8.0, s.maxSpeed, 0.05)
        // 300 W × 600 s = 50 Wh.
        assertEquals(50.0, s.whUsed!!, 0.01)
        assertEquals("power", s.energySource)
        assertEquals(0.0, s.regenWh!!, 1e-9)
        assertEquals(50.0 / (4800 / Geo.M_PER_MI), s.whPerMi!!, 0.1)
        assertEquals(22.0, s.avgTempC!!, 1e-9)
    }

    @Test
    fun regenCountsSeparately() {
        val down = ride(100, 8.0, grade = -0.05, powerW = -120.0)
        val s = TripMath.stats(down)
        assertEquals(-120.0 * 100 / 3600, s.whUsed!!, 0.01)
        assertEquals(120.0 * 100 / 3600, s.regenWh!!, 0.01)
        assertEquals(40.0, s.descentM, 1.0)
    }

    @Test
    fun batteryFallbackWithoutPower() {
        val s = TripMath.stats(ride(600, 8.0, powerW = null))
        assertEquals("battery", s.energySource)
        // 6 % of 551 × 0.95.
        assertEquals(0.06 * 551 * 0.95, s.whUsed!!, 0.01)
        assertNull(TripMath.stats(ride(600, 8.0, powerW = null).map { it.copy(batteryPct = null) }).whUsed)
    }

    @Test
    fun gpsJumpsIgnored() {
        val r = ride(60, 8.0).toMutableList()
        r[30] = r[30].copy(lat = r[30].lat + 0.05)  // 5.5 km teleport
        val s = TripMath.stats(r)
        assertTrue(s.distanceM < 600)
    }

    @Test
    fun scooterSpeedPreferredOverOdometer() {
        // Per-step distance comes from the scooter's own speed; the coarse odometer is not used per step.
        val r = ride(100, 8.0).mapIndexed { i, s -> s.copy(odometerM = 1000.0 + i * 7.0) }
        assertEquals(800.0, TripMath.stats(r).distanceM, 1e-6)
    }

    @Test
    fun segmentsFeedTheFitter() {
        val segs = TripMath.measuredSegments(ride(600, 8.0, grade = 0.02))
        assertEquals(24, segs.size)                       // 4800 m / 200 m
        segs.forEach {
            assertEquals(8.0, it.speedMps, 0.1)
            assertEquals(0.02, it.grade, 0.002)
            assertTrue(ModelFitter.usable(it))
        }
        val model = TripMath.modelSegments(ride(600, 8.0))
        assertEquals(4800.0, model.sumOf { it.lengthM }, 5.0)
        assertEquals(8.0, TripMath.typicalSpeed(model)!!, 0.1)
        assertEquals(0.0, TripMath.typicalGrade(model)!!, 1e-6)
        // A whole fitted model from synthetic trips: 25 trips × 3 mi.
        val many = (0 until 25).flatMap { k -> TripMath.measuredSegments(ride(600, 6.0 + (k % 5), grade = (k % 7 - 3) * 0.01, powerW = 200.0 + 30 * (k % 5))) }
        val fit = ModelFitter.fit(many, PhysicsModel(RideParams()).priorCoefficients())
        assertNotNull(fit.model)
    }

    @Test
    fun detectorStartsAfter30sAndStopsAfter3min() {
        val d = TripDetector()
        var t = 0L
        repeat(30) { assertEquals(TripDetector.Event.NONE, d.onSpeed(3.0, t)); t += 1000 }
        assertEquals(TripDetector.Event.START, d.onSpeed(3.0, t))
        assertTrue(d.recording)
        t += 1000
        repeat(180) { assertEquals(TripDetector.Event.NONE, d.onSpeed(0.0, t)); t += 1000 }
        assertEquals(TripDetector.Event.STOP, d.onSpeed(0.0, t))
        assertFalse(d.recording)
    }

    @Test
    fun detectorShortStopsAndManual() {
        val d = TripDetector()
        repeat(40) { d.onSpeed(5.0, it * 1000L) }
        assertTrue(d.recording)
        // A 2-minute red light doesn't end the trip.
        repeat(120) { d.onSpeed(0.0, 40_000L + it * 1000L) }
        d.onSpeed(5.0, 161_000L)
        assertTrue(d.recording)
        // Brief slow bits before starting reset the 30 s timer.
        val e = TripDetector()
        repeat(20) { e.onSpeed(3.0, it * 1000L) }
        e.onSpeed(0.5, 20_000L)
        repeat(20) { e.onSpeed(3.0, 21_000L + it * 1000L) }
        assertFalse(e.recording)
        // Manual trips don't auto-stop.
        val m = TripDetector()
        m.manualStart()
        repeat(400) { m.onSpeed(0.0, it * 1000L) }
        assertTrue(m.recording)
        m.manualStop()
        assertFalse(m.recording)
    }

    @Test
    fun elevationFilter() {
        val f = ElevationFilter()
        // GPS only: noisy ±10 m around 800 is smoothed.
        val outs = (0 until 60).map { f.update(it * 1000L, 800.0 + if (it % 2 == 0) 10 else -10, 5.0, null)!! }
        assertTrue(outs.drop(20).all { it in 795.0..805.0 })
        // Barometer: relative changes pass straight through.
        val b = ElevationFilter()
        b.update(0, 800.0, 5.0, 100.0)
        val e1 = b.update(1000, 800.0, 5.0, 110.0)!!
        assertEquals(810.0, e1, 0.2)
    }

    @Test
    fun demCorrection() {
        val r = ride(300, 8.0)
        val idx = DemCorrection.pickIndices(r.size, 100)
        assertTrue(idx.size <= 100 && idx.first() == 0 && idx.last() == r.size - 1)
        val dem = idx.map { 900.0 + it * 0.1 }
        val out = DemCorrection.apply(r, idx, dem)
        assertEquals(915.0, out[150].ele!!, 0.5)
        assertEquals(r.size, out.size)
    }

    @Test
    fun exports() {
        val samples = ride(10, 8.0)
        val rec = TripRecord(7, samples.first().t, samples.last().t, "N4GTEST000001", TripMath.stats(samples), 1.0, "Default model",
            doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 21.0, samples)
        val gpx = TripExport.gpx(rec)
        assertEquals(11, Regex("<trkpt ").findAll(gpx).count())
        val csv = TripExport.csv(rec).trim().lines()
        assertEquals(TripExport.CSV_HEADER, csv[0])
        assertEquals(12, csv.size)
        val j = JSONObject(TripExport.json(rec, "1.0.0"))
        assertEquals("riderange/trip/1", j.getString("schema"))
        assertEquals(11, j.getJSONObject("samples").getJSONArray("lat").length())
        assertEquals(5, j.getJSONObject("prediction").getJSONArray("coefficients").length())
        assertEquals("N4GTEST000001", j.getString("serial"))
        assertTrue(j.getJSONObject("mass").has("combined"))
        val withMass = rec.copy(tripMass = com.elect.riderange.range.TripMass(100.0, 3.0, 300, 0.4, true, 0.015),
            combinedMass = com.elect.riderange.range.MassEstimate(101.0, 2.0, 3, true), massUsedKg = 101.0)
        val jm = JSONObject(TripExport.json(withMass, "1.0.2")).getJSONObject("mass")
        assertEquals(101.0, jm.getDouble("used_kg"), 1e-9)
        assertEquals(100.0, jm.getJSONObject("trip").getDouble("kg"), 1e-9)
        assertTrue(jm.getJSONObject("combined").getBoolean("confident"))
    }
}
