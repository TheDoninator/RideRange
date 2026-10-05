package com.elect.riderange.scooter

import com.elect.riderange.hexToBytes
import com.elect.riderange.scooter.protocol.Nb
import com.elect.riderange.scooter.protocol.NbTimeout
import com.elect.riderange.scooter.protocol.RegisterIo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decoding uses the raw bytes from the real Max G2 report (a Max G2 read on 2026-10-03). */
class TelemetryTest {
    @Test
    fun decodesRealReportValues() {
        assertEquals(0.0, Regs.speedKmh("0000".hexToBytes())!!, 1e-9)
        assertEquals(81, Regs.pct("5100".hexToBytes()))
        assertEquals(28.0, Regs.ctrlTemp("1801".hexToBytes())!!, 1e-9)
        assertEquals(28, Regs.batteryTemp("3030".hexToBytes()))
        // 0x33 = 0b00 (0.11 A), 0x34 = 2e0f (38.86 V)
        val (i, v) = Regs.currentVoltage("0b002e0f".hexToBytes())!!
        assertEquals(0.11, i, 1e-9); assertEquals(38.86, v, 1e-9)
        // Odometer: u32 little-endian metres (4756.5 km = 0x00489414).
        assertEquals(4_756_500L, Regs.odometer(byteArrayOf(0x14, 0x94.toByte(), 0x48, 0x00))!!)
        // Negative speed (reversing / regen sign) and "not supported".
        assertEquals(-1.5, Regs.speedKmh("f1ff".hexToBytes())!!, 1e-9)
        assertNull(Regs.speedKmh("ffff".hexToBytes()))
        assertNull(Regs.pct("ffff".hexToBytes()))
    }

    private class FakeIo(val values: Map<Pair<Int, Int>, ByteArray>) : RegisterIo {
        val reads = ArrayList<Pair<Int, Int>>()
        override suspend fun read(dev: Int, reg: Int, length: Int): ByteArray {
            reads += dev to reg
            return values[dev to reg] ?: throw NbTimeout("no reply")
        }
        override suspend fun write(dev: Int, reg: Int, value: ByteArray) = error("the app must never write")
    }

    @Test
    fun pollerReadsOnlyAndUpdates() = runTest {
        val io = FakeIo(mapOf(
            (Nb.CTRL to Regs.SPEED) to "c800".hexToBytes(),                 // 20.0 km/h
            (Nb.BMS to Regs.BMS_PCT) to "5100".hexToBytes(),
            (Nb.BMS to Regs.BMS_CURRENT) to "e8032e0f".hexToBytes(),        // 10.00 A, 38.86 V
            (Nb.CTRL to Regs.ODO) to byteArrayOf(0x14, 0x94.toByte(), 0x48, 0x00),
        ))
        var last: Telemetry? = null
        val p = TelemetryPoller(io, { last = it }, clock = { 5L })
        p.pollSlow(); p.pollPower(); p.pollSpeed()
        val t = last!!
        assertEquals(20.0, t.speedKmh!!, 1e-9)
        assertEquals(81, t.batteryPct)
        assertEquals(388.6, t.powerW!!, 1e-6)
        assertEquals(4_756_500L, t.odometerM)
        assertNull(t.scooterTempC)        // timed out: stays unknown
    }
}
