package com.elect.riderange.debugsim

import com.elect.riderange.vehicle.vesc.FloatPackage
import com.elect.riderange.vehicle.vesc.VescProtocol
import com.elect.riderange.vehicle.vesc.VescSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug-only simulator, driven through the real decoder and session (as the app does on the emulator). */
class SimulatedVescTest {
    private fun run(dual: Boolean, ticks: Int, extra: (SimulatedVesc) -> Unit = {}): Pair<VescSession, SimulatedVesc> {
        val session = VescSession()
        val decoder = VescProtocol.Decoder()
        val lock = Any()
        val sim = SimulatedVesc(dual) { bytes -> synchronized(lock) { decoder.feed(bytes).forEach { session.onPayload(it, System.currentTimeMillis()) } } }
        val writer = VescProtocol.ReadOnlyWriter { sim.write(it) }
        synchronized(lock) { session.openingRequests() }.forEach { writer.send(it) }
        extra(sim)
        repeat(ticks) {
            Thread.sleep(25)
            synchronized(lock) { session.nextRequests() }.forEach { writer.send(it) }
        }
        Thread.sleep(150)
        sim.close()
        return session to sim
    }

    @Test fun dualMotorSetupComesThroughTheRealDecoder() {
        val (s, _) = run(dual = true, ticks = 24)
        val snap = s.snapshot
        val now = System.currentTimeMillis()
        assertEquals(10, snap.localId)
        assertEquals(listOf(11, 20), snap.canIds)
        assertNotNull(snap.can[11])
        assertNull(snap.can[20])                                    // the BMS id has no motor values
        assertEquals(2, snap.setup!!.numVescs)
        assertEquals(12, snap.bms!!.cellV.size)
        assertNull(snap.floatRt)                                     // no Float package on this one
        assertEquals("75_300_R2", snap.fw!!.hardware)
        assertTrue(snap.toTelemetry(now, com.elect.riderange.vehicle.vesc.VescVehicleParams(7, 97.0, 2.25, 12))!!.batteryPct!! in 1..100)
    }

    @Test fun floatBoardAnswersTheFloatPackageAndRefusesWrites() {
        val (s, sim) = run(dual = false, ticks = 24) { sim ->
            // A raw (unchecked) COMM_SET_CURRENT and a Float tune command: the simulator refuses them like a sentinel.
            sim.write(VescProtocol.frame(byteArrayOf(6, 0, 0, 0x27, 0x10)))
            sim.write(VescProtocol.frame(byteArrayOf(36, 101, 2, 0)))
        }
        val snap = s.snapshot
        assertEquals(2, sim.refused)
        assertEquals(1, snap.floatInfo!!.major); assertEquals(3, snap.floatInfo!!.minor)
        assertEquals(FloatPackage.State.RUNNING, snap.floatRt!!.state)
        assertEquals(FloatPackage.Footpad.ON, snap.floatRt!!.footpad)
        assertEquals(20, snap.bms!!.cellV.size)
        assertEquals(listOf(20), snap.canIds)
        assertTrue(snap.can.isEmpty())
    }
}
