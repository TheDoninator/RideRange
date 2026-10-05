package com.elect.riderange.vehicle

import com.elect.riderange.hexToBytes
import com.elect.riderange.toHex
import com.elect.riderange.vehicle.vesc.BatterySource
import com.elect.riderange.vehicle.vesc.FloatPackage
import com.elect.riderange.vehicle.vesc.LiIon
import com.elect.riderange.vehicle.vesc.RideAlertLimiter
import com.elect.riderange.vehicle.vesc.RideWarning
import com.elect.riderange.vehicle.vesc.VescProtocol
import com.elect.riderange.vehicle.vesc.VescSession
import com.elect.riderange.vehicle.vesc.VescSnapshot
import com.elect.riderange.vehicle.vesc.VescVehicleParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 1.2.0 VESC telemetry: allow-list, CAN forwarding, GET_VALUES_SETUP, BMS, Float package, aggregation and alerts.
 * The frames were built independently (Python `struct`, big-endian, + CRC-16/XMODEM) following the documented layouts,
 * or with the small builder below, never with the code under test.
 */
class VescTelemetryTest {
    // ---- independent frame builder (test-only) ----
    private fun xmodem(p: ByteArray): Int {
        var c = 0
        for (b in p) {
            c = c xor ((b.toInt() and 0xFF) shl 8)
            for (k in 0 until 8) c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF
        }
        return c
    }
    private fun frameOf(p: ByteArray): ByteArray = byteArrayOf(2, p.size.toByte()) + p + byteArrayOf((xmodem(p) shr 8).toByte(), xmodem(p).toByte(), 3)
    private fun payloadOf(frameHex: String): ByteArray = VescProtocol.Decoder().feed(frameHex.hexToBytes()).single()

    /** COMM_GET_VALUES payload (documented order) for a controller. */
    private fun values(id: Int?, inputCurrent: Double, duty: Double = 0.3, volts: Double = 74.0, erpm: Int = 15000, tempFet: Double = 30.0): ByteArray {
        val b = ByteBuffer.allocate(64)
        b.put(4).putShort((tempFet * 10).toInt().toShort()).putShort(400).putInt(2000).putInt(Math.round(inputCurrent * 100).toInt())
            .putInt(0).putInt(0).putShort(Math.round(duty * 1000).toInt().toShort()).putInt(erpm).putShort(Math.round(volts * 10).toInt().toShort())
            .putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(9000).put(0)
        if (id != null) b.putInt(0).put(id.toByte())
        return b.array().copyOf(b.position())
    }

    private val setupHex = "02462f013b01e2000009f600000591026c0000520800001c5202e7032f00003a98000009c40010dc68000253b4004e2d80004f588000000000000a02000624440001e2400036ee8002b003"
    private val bmsHex = "02486000ee9a080000000000bebc2000bd35800000092900008e94040f480f410f510f3b000001000209920a410bb8089810360ad7036c03ca1442f100004589800042ec8000458664009ee803"
    private val rtHex = "0249246501414800003fe00000c0600000310240366666403a3d713fa000003f000000be8000003f4000003e000000000000003fc00000412800003ca3d70a000000004192000000000000693403"
    private val infoHex = "02052465000d01025803"
    private val pingHex = "02033e0b1450eb03"
    private val fwHex = "021a00060536305f4d4b36000102030405060708090a0b0c01000002280f03"

    // ---- allow-list ----

    @Test fun allowListHasOnlyGetCommandsAndCheckedWrappers() {
        assertEquals(setOf(0, 4, 34, 36, 47, 62, 96), VescProtocol.READ_ONLY_COMMANDS)
        for (c in listOf(0, 4, 47, 62, 96)) assertTrue("$c", VescProtocol.isReadOnly(byteArrayOf(c.toByte())))
        // Motor, config, reboot, bootloader, terminal, CAN-mode, BMS-write commands: never.
        for (c in listOf(1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 20, 29, 35, 82, 84, 85, 86, 87, 93, 97, 98, 99, 100, 255))
            assertFalse("$c", VescProtocol.isReadOnly(byteArrayOf(c.toByte())))
        // Gets don't take arguments.
        assertFalse(VescProtocol.isReadOnly(byteArrayOf(4, 1)))
        assertFalse(VescProtocol.isReadOnly(ByteArray(0)))
    }

    @Test fun writerRefusesEverythingElseAndSendsNothing() {
        val sent = ArrayList<ByteArray>()
        val w = VescProtocol.ReadOnlyWriter { sent += it }
        val bad = listOf(
            byteArrayOf(5, 0, 0, 1, 0),                       // COMM_SET_DUTY
            byteArrayOf(13),                                  // COMM_SET_MCCONF
            byteArrayOf(34, 1, 6, 0, 0, 0, 1),                // COMM_SET_CURRENT forwarded over CAN
            byteArrayOf(34, 1, 34, 2, 4),                     // nested forward
            byteArrayOf(34, 255.toByte(), 4),                 // broadcast id
            byteArrayOf(34, 1),                               // forward with nothing inside
            byteArrayOf(36, 101, 2),                          // Float RT_TUNE
            byteArrayOf(36, 101, 4),                          // Float CFG_SAVE
            byteArrayOf(36, 101, 1, 0),                       // read with an extra argument
            byteArrayOf(36, 102, 1),                          // another package's magic
            byteArrayOf(36),
        )
        for (p in bad) {
            try { w.send(p); fail("sent ${p.toHex()}") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(sent.isEmpty())
        w.send(byteArrayOf(4))
        w.send(FloatPackage.rtDataRequest())
        w.send(VescProtocol.forwardCanPayload(11, byteArrayOf(4)))
        assertEquals(3, sent.size)
        assertEquals("020104408403", sent[0].toHex())
    }

    @Test fun forwardCanFrameFollowsTheDocumentedLayout() {
        // COMM_FORWARD_CAN (34), CAN id, then the inner request: 22 0b 04.
        val f = VescProtocol.forwardCan(11, byteArrayOf(4))
        assertArrayEquals(frameOf(byteArrayOf(34, 11, 4)), f)
        assertEquals("0203220b04", f.toHex().substring(0, 10))
        assertArrayEquals(frameOf(byteArrayOf(36, 101, 1)), VescProtocol.readOnlyFrame(FloatPackage.rtDataRequest()))
        try { VescProtocol.forwardCan(300, byteArrayOf(4)); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---- parsers ----

    @Test fun parsesGetValuesSetup() {
        val s = VescProtocol.parseValuesSetup(payloadOf(setupHex))!!
        assertEquals(31.5, s.tempMosfetC, 1e-9); assertEquals(48.2, s.tempMotorC, 1e-9)
        assertEquals(25.5, s.currentTotA, 1e-9); assertEquals(14.25, s.currentInTotA, 1e-9)
        assertEquals(0.62, s.dutyCycle, 1e-9); assertEquals(21000.0, s.rpm, 1e-9)
        assertEquals(7.25, s.speedMps, 1e-9); assertEquals(74.3, s.inputVoltage, 1e-9)
        assertEquals(0.815, s.batteryLevel!!, 1e-9)
        assertEquals(110.5, s.wattHours!!, 1e-9); assertEquals(15.25, s.wattHoursCharged!!, 1e-9)
        assertEquals(5123.456, s.distanceM!!, 1e-9)
        assertEquals(0, s.faultCode); assertEquals(10, s.controllerId); assertEquals(2, s.numVescs)
        assertEquals(402.5, s.whBatteryLeft!!, 1e-9); assertEquals(123456L, s.odometerM); assertEquals(3_600_000L, s.uptimeMs)
    }

    @Test fun setupFromOlderFirmwareKeepsWhatParses() {
        val full = payloadOf(setupHex)
        // Cut after battery level (older firmware / masked reply): the rest is null, not garbage.
        val s = VescProtocol.parseValuesSetup(full.copyOf(1 + 26))!!
        assertEquals(0.815, s.batteryLevel!!, 1e-9)
        assertNull(s.wattHours); assertNull(s.numVescs); assertNull(s.odometerM)
        // Cut in the middle of a field: that field and everything after it are null.
        val t = VescProtocol.parseValuesSetup(full.copyOf(1 + 26 + 2))!!
        assertNull(t.ampHours)
        assertNull(VescProtocol.parseValuesSetup(full.copyOf(10)))                     // too short to be useful
        // Firmware that appends new fields: extra bytes are ignored.
        val longer = VescProtocol.parseValuesSetup(full + byteArrayOf(1, 2, 3, 4, 5))!!
        assertEquals(3_600_000L, longer.uptimeMs)
    }

    @Test fun parsesBmsCellsTempsAndSoc() {
        val b = VescProtocol.parseBms(payloadOf(bmsHex))!!
        assertTrue(b.present)
        assertEquals(15.637, b.packV, 1e-9); assertEquals(12.5, b.currentA, 1e-9)
        assertEquals(listOf(3.912, 3.905, 3.921, 3.899), b.cellV)
        assertEquals(listOf(false, false, true, false), b.balancing)
        assertEquals(1, b.balancingCount)
        assertEquals(0.022, b.cellDelta!!, 1e-9)
        assertEquals(listOf(24.5, 26.25), b.tempsC)
        assertEquals(27.75, b.tempMaxCellC!!, 1e-9); assertEquals(41.5, b.humidityPct!!, 1e-9); assertEquals(30.0, b.tempIcC!!, 1e-9)
        assertEquals(0.876, b.soc!!, 1e-9); assertEquals(0.97, b.soh!!, 1e-9)
        assertEquals(20, b.canId)
        assertEquals(4400.0, b.whChargeTotal!!, 1e-3); assertEquals(4300.5, b.whDischargeTotal!!, 1e-3)
    }

    @Test fun bmsDefensiveParsing() {
        val full = payloadOf(bmsHex)
        // Older BMS firmware stops after the temperatures: SOC unknown, cells still there.
        val old = VescProtocol.parseBms(full.copyOf(1 + 24 + 1 + 12 + 1 + 4))!!
        assertEquals(4, old.cellV.size); assertNull(old.soc); assertNull(old.tempMaxCellC)
        // Cell list cut short: not a valid reply.
        assertNull(VescProtocol.parseBms(full.copyOf(1 + 24 + 1 + 5)))
        // No BMS: the controller answers zeros.
        val none = VescProtocol.parseBms(byteArrayOf(96) + ByteArray(25))!!
        assertFalse(none.present)
        // Absurd cell count.
        assertNull(VescProtocol.parseBms(byteArrayOf(96) + ByteArray(24) + byteArrayOf(200.toByte()) + ByteArray(10)))
    }

    @Test fun parsesPingAndFirmware() {
        assertEquals(listOf(11, 20), VescProtocol.parsePingCan(payloadOf(pingHex)))
        assertEquals(emptyList<Int>(), VescProtocol.parsePingCan(byteArrayOf(62)))
        val fw = VescProtocol.parseFwVersion(payloadOf(fwHex))!!
        assertEquals(6, fw.major); assertEquals(5, fw.minor); assertEquals("60_MK6", fw.hardware)
        assertEquals(0, fw.testBuild); assertEquals(0, fw.hwType)
        assertEquals("6.05 (60_MK6)", fw.label)
    }

    @Test fun parsesFloatRealtimeData() {
        val r = (FloatPackage.parse(payloadOf(rtHex)) as FloatPackage.RtReply).data
        assertEquals(12.5, r.pidValue!!, 1e-6)
        assertEquals(1.75, r.pitch!!, 1e-6); assertEquals(-3.5, r.roll!!, 1e-6)
        assertEquals(FloatPackage.State.RUNNING, r.state)
        assertEquals(FloatPackage.Setpoint.TILTBACK_DUTY, r.setpointAdjust)
        assertTrue(r.pushback)
        assertEquals(FloatPackage.Footpad.ON, r.footpad)
        assertEquals(2.85, r.adc1!!, 1e-6); assertEquals(2.91, r.adc2!!, 1e-6)
        assertEquals(1.25, r.setpoint!!, 1e-6); assertEquals(0.5, r.atr!!, 1e-6)
        assertEquals(1.5, r.truePitch!!, 1e-6); assertEquals(18.25, r.motorCurrent!!, 1e-6)
        val info = (FloatPackage.parse(payloadOf(infoHex)) as FloatPackage.InfoReply).info
        assertEquals(1, info.major); assertEquals(3, info.minor); assertEquals(1, info.build)
    }

    @Test fun unknownFloatVersionsShowWhatParses() {
        val full = payloadOf(rtHex)
        // A shorter (older) layout: angles, state and footpads only.
        val short = (FloatPackage.parse(full.copyOf(3 + 12 + 2)) as FloatPackage.RtReply).data
        assertEquals(1.75, short.pitch!!, 1e-6); assertEquals(FloatPackage.Footpad.ON, short.footpad)
        assertNull(short.adc1); assertNull(short.motorCurrent)
        // Unknown state / setpoint codes are dropped, angles kept.
        val odd = full.copyOf().also { it[15] = 0x7E }
        val o = (FloatPackage.parse(odd) as FloatPackage.RtReply).data
        assertNull(o.state); assertNull(o.setpointAdjust); assertEquals(1.75, o.pitch!!, 1e-6)
        // A layout that doesn't fit at all (NaN / out-of-range angles) gives nothing.
        val nan = byteArrayOf(36, 101, 1) + ByteArray(12) { 0x7F.toByte() }
        assertNull(FloatPackage.parse(nan))
        // Other packages / sub-commands are ignored.
        assertNull(FloatPackage.parse(byteArrayOf(36, 102, 1, 0, 0, 0, 0)))
        assertNull(FloatPackage.parse(byteArrayOf(36, 101, 9, 0, 0)))
    }

    // ---- session: polling, CAN, aggregation ----

    private fun feed(s: VescSession, p: ByteArray, now: Long) = s.onPayload(VescProtocol.Decoder().feed(frameOf(p)).single(), now)

    @Test fun sessionOnlyEverSendsReadOnlyRequests() {
        val s = VescSession()
        feed(s, values(10, 5.0), 0)
        feed(s, payloadOf(pingHex), 0)
        feed(s, payloadOf(infoHex), 0)
        // Controller 11 answers every forwarded request; id 20 (say, a BMS) never does.
        val all = s.openingRequests() + (0 until 400).flatMap { i ->
            s.nextRequests().also { reqs -> if (reqs.any { it[0] == 34.toByte() && it[1] == 11.toByte() }) feed(s, values(11, 5.0), i * 250L) }
        }
        assertTrue(all.all { VescProtocol.isReadOnly(it) })
        // Local values every tick (4 Hz); CAN requests went to the pinged ids; setup and BMS were asked for.
        assertEquals(400, all.count { it.size == 1 && it[0] == 4.toByte() } - 1)
        val can = all.filter { it[0] == 34.toByte() }.map { it[1].toInt() }
        assertTrue(can.contains(11)); assertTrue(can.contains(20))
        assertTrue(all.any { it.contentEquals(byteArrayOf(47)) })
        assertTrue(all.any { it.contentEquals(byteArrayOf(96)) })
        assertTrue(all.count { it.contentEquals(FloatPackage.rtDataRequest()) } >= 190)   // 2 Hz once the package answered
        // At most 2 CAN requests per second (BLE bandwidth); id 20 never answered, so it's only retried slowly.
        assertTrue(can.size <= 200)
        assertTrue(can.count { it == 20 } < can.count { it == 11 })
    }

    @Test fun canControllersAreTrackedByControllerId() {
        val s = VescSession()
        assertTrue(feed(s, values(10, 6.0), 0))                  // first reply: the local controller
        feed(s, payloadOf(pingHex), 0)
        repeat(4) { s.nextRequests() }                           // starts CAN polling
        feed(s, values(11, 5.5, duty = 0.4), 100)                // forwarded reply from the second motor
        feed(s, values(10, 6.5), 200)
        val snap = s.snapshot
        assertEquals(10, snap.localId)
        assertEquals(listOf(11, 20), snap.canIds)
        assertEquals(6.5, snap.local!!.values.inputCurrentA, 1e-9)
        assertEquals(5.5, snap.can[11]!!.values.inputCurrentA, 1e-9)
        assertEquals(2, snap.controllers(300).size)
        // No setup values: power is the sum of both controllers.
        assertEquals(12.0, snap.totalInputCurrent(300)!!, 1e-9)
        assertTrue(snap.summedOverCan(300))
        assertEquals(74.0 * 12.0, snap.totalPowerW(300)!!, 1e-9)
        assertEquals(0.4, snap.maxDuty(300)!!, 1e-9)
        // Stale CAN data (> 3 s) drops out of the sum.
        assertEquals(1, snap.controllers(3_150).size)
    }

    @Test fun setupTotalsWinWhenTheyCoverEveryController() {
        val s = VescSession()
        feed(s, values(10, 6.0), 0)
        feed(s, payloadOf(pingHex), 0)
        repeat(4) { s.nextRequests() }
        feed(s, values(11, 5.0), 0)
        feed(s, payloadOf(setupHex), 0)                          // num_vescs = 2, current_in_tot = 14.25
        assertEquals(14.25, s.snapshot.totalInputCurrent(100)!!, 1e-9)
        assertFalse(s.snapshot.summedOverCan(100))
        // If the controller only counts itself (CAN status messages off), RideRange sums instead.
        val oneVesc = payloadOf(setupHex).copyOf().also { it[1 + 57 - 1] = 1 }
        feed(s, oneVesc, 0)
        assertEquals(1, s.snapshot.setup!!.numVescs)
        assertEquals(11.0, s.snapshot.totalInputCurrent(100)!!, 1e-9)
    }

    @Test fun batteryPrefersBmsThenControllerThenVoltage() {
        val s = VescSession()
        feed(s, values(10, 6.0, volts = 74.0), 0)
        val p = VescVehicleParams(polePairs = 15, wheelDiameterMm = 283.0, cellsSeries = 20)
        assertEquals(LiIon.percent(74.0 / 20) to BatterySource.VOLTAGE, s.snapshot.battery(10, 20))
        feed(s, payloadOf(setupHex), 0)
        val ctl = s.snapshot.battery(10, 20)!!
        assertEquals(BatterySource.CONTROLLER, ctl.second); assertTrue(ctl.first in 81..82)   // battery_level 0.815
        feed(s, payloadOf(bmsHex), 0)
        assertEquals(88 to BatterySource.BMS, s.snapshot.battery(10, 20))          // soc 0.876 -> 88 %
        val t = s.snapshot.toTelemetry(10, p)!!
        assertEquals(88, t.batteryPct)
        assertEquals(28, t.batteryTempC)                                           // BMS hottest cell 27.75 °C
        assertEquals(7.25 * 3.6, t.speedKmh!!, 1e-9)                              // controller's configured speed wins
        assertEquals(123456L, t.odometerM)
        // A BMS that went quiet (> 10 s) no longer counts; nor does the setup reply (> 3 s).
        assertEquals(LiIon.percent(74.0 / 20) to BatterySource.VOLTAGE, s.snapshot.copy(local = s.snapshot.local!!.copy(updatedMs = 11_000))
            .battery(11_000, 20))
        // Without setup values speed comes from ERPM / pole pairs / gear ratio and the wheel.
        val noSetup = VescSnapshot(local = s.snapshot.local)
        val skate = noSetup.toTelemetry(10, VescVehicleParams(polePairs = 7, wheelDiameterMm = 90.0, gearRatio = 2.25, cellsSeries = 20))!!
        assertEquals(VescProtocol.speedMps(15000.0, 7, 90.0, 2.25) * 3.6, skate.speedKmh!!, 1e-9)
        assertEquals(15000.0 / 7 / 2.25 / 60 * Math.PI * 0.090, VescProtocol.speedMps(15000.0, 7, 90.0, 2.25), 1e-9)
    }

    @Test fun oldFirmwareWithoutControllerIdNeverPollsCan() {
        val s = VescSession()
        feed(s, values(null, 4.0), 0)
        feed(s, payloadOf(pingHex), 0)
        val reqs = (0 until 40).flatMap { s.nextRequests() }
        assertTrue(reqs.none { it[0] == 34.toByte() })
        feed(s, values(null, 4.5), 10)
        assertEquals(4.5, s.snapshot.local!!.values.inputCurrentA, 1e-9)
    }

    // ---- alerts ----

    private fun withFloat(setpoint: Int, duty: Double, now: Long): VescSnapshot {
        val s = VescSession()
        feed(s, values(10, 6.0, duty = duty), now)
        val rt = payloadOf(rtHex).copyOf().also { it[15] = (1 or (setpoint shl 4)).toByte() }
        feed(s, rt, now)
        return s.snapshot
    }

    @Test fun pushbackAndDutyWarnings() {
        val l = RideAlertLimiter()
        assertEquals(setOf(RideWarning.PUSHBACK_DUTY), l.active(withFloat(3, 0.5, 0), 0, 0.85))
        assertEquals(setOf(RideWarning.PUSHBACK_LOW_VOLTAGE), l.active(withFloat(5, 0.5, 0), 0, 0.85))
        assertEquals(emptySet<RideWarning>(), l.active(withFloat(2, 0.5, 0), 0, 0.85))
        assertEquals(setOf(RideWarning.DUTY), l.active(withFloat(2, 0.86, 0), 0, 0.85))
        // Hysteresis: stays on until 5 points below the threshold.
        assertEquals(setOf(RideWarning.DUTY), l.active(withFloat(2, 0.82, 0), 0, 0.85))
        assertEquals(emptySet<RideWarning>(), l.active(withFloat(2, 0.79, 0), 0, 0.85))
        assertEquals(emptySet<RideWarning>(), l.active(null, 0, 0.85))
    }

    @Test fun alertsAreRateLimited() {
        val l = RideAlertLimiter(repeatMs = 10_000, minGapMs = 3_000)
        val both = setOf(RideWarning.PUSHBACK_DUTY, RideWarning.DUTY)
        val first = l.next(both, 0, 88)!!
        assertEquals(RideWarning.PUSHBACK_DUTY, first.warning)              // most urgent first
        assertEquals("Pushback. Duty cycle.", first.speech)
        assertNull(l.next(both, 1_000, 88))                                 // min gap
        assertEquals("Duty 88 percent.", l.next(both, 3_000, 88)!!.speech)
        assertNull(l.next(both, 6_000, 88))                                 // both already said, not yet due again
        assertEquals(RideWarning.PUSHBACK_DUTY, l.next(both, 10_000, 88)!!.warning)   // repeats while it lasts
        // A warning that ends and comes back alerts again right away (after the min gap).
        assertNull(l.next(emptySet(), 11_000, null))
        assertEquals(RideWarning.PUSHBACK_DUTY, l.next(setOf(RideWarning.PUSHBACK_DUTY), 13_500, null)!!.warning)
    }
}
