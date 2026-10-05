package com.elect.riderange.vehicle

import com.elect.riderange.hexToBytes
import com.elect.riderange.toHex
import com.elect.riderange.vehicle.vesc.LiIon
import com.elect.riderange.vehicle.vesc.VescProtocol
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertNotNull
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test
import kotlin.math.PI

class VescProtocolTest {
    @Test fun crc16XmodemCheckValue() {
        // The CRC-16/XMODEM catalogue check value: crc("123456789") = 0x31C3.
        assertEquals(0x31C3, VescProtocol.crc16("123456789".encodeToByteArray()))
        assertEquals(0, VescProtocol.crc16(ByteArray(0)))
    }

    @Test fun requestFramesMatchTheWidelyPublishedBytes() {
        // COMM_GET_VALUES request as sent by VESC UART clients: 02 01 04 40 84 03.
        assertEquals("020104408403", VescProtocol.request(VescProtocol.COMM_GET_VALUES).toHex())
        assertEquals("020100000003", VescProtocol.request(VescProtocol.COMM_FW_VERSION).toHex())
    }

    @Test fun refusesAnythingButReads() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { VescProtocol.request(5) }   // COMM_SET_DUTY: never allowed
    }

    @Test fun readOnlyWhitelistHasOnlyReads() {
        // 1.2.0 adds the other gets (setup values, CAN ping, BMS) and the two checked wrappers.
        assertEquals(setOf(0, 4, 47, 62, 96, 34, 36), VescProtocol.READ_ONLY_COMMANDS)
    }

    /** Built independently with Python struct (big-endian) + CRC-16/XMODEM, not with VescProtocol. */
    private val valuesFrame = "023b0400fd019c000004d2000002370000000000000000015900003a9802d500003039000002a6000d9883000011d7000003e800015f90000000000007cbda03"
    private val fwFrame = "0206000502363000bcaf03"

    @Test fun parsesGetValues() {
        val payloads = VescProtocol.Decoder().feed(valuesFrame.hexToBytes())
        assertEquals(1, payloads.size)
        val v = VescProtocol.parseValues(payloads[0])
        assertNotNull(v); v!!
        assertEquals(25.3, v.tempMosfetC, 1e-9)
        assertEquals(41.2, v.tempMotorC, 1e-9)
        assertEquals(12.34, v.motorCurrentA, 1e-9)
        assertEquals(5.67, v.inputCurrentA, 1e-9)
        assertEquals(0.345, v.dutyCycle, 1e-9)
        assertEquals(15000.0, v.erpm, 1e-9)
        assertEquals(72.5, v.inputVoltage, 1e-9)
        assertEquals(1.2345, v.ampHours, 1e-9)
        assertEquals(89.1011, v.wattHours, 1e-9)
        assertEquals(90000, v.tachometerAbs)
        assertEquals(0, v.faultCode)
        assertEquals(7, v.controllerId)
        assertEquals(72.5 * 5.67, v.inputPowerW, 1e-9)
    }

    @Test fun decoderHandlesBleFragmentsGarbageAndBadCrc() {
        val frame = valuesFrame.hexToBytes()
        val bad = frame.copyOf().also { it[10] = (it[10] + 1).toByte() }   // corrupted payload byte
        val stream = byteArrayOf(0x55, 0x00) + bad + frame + fwFrame.hexToBytes()
        val d = VescProtocol.Decoder()
        val out = stream.toList().chunked(20).flatMap { d.feed(it.toByteArray()) }
        assertEquals(2, out.size)
        assertNotNull(VescProtocol.parseValues(out[0]))
        val fw = VescProtocol.parseFwVersion(out[1])!!
        assertEquals(5, fw.major); assertEquals(2, fw.minor); assertEquals("60", fw.hardware)
        assertTrue(d.crcErrors >= 1)
    }

    @Test fun longFramesRoundTrip() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val f = VescProtocol.frame(payload)
        assertEquals(3, f[0].toInt())
        assertEquals(300 + 6, f.size)
        assertTrue(VescProtocol.Decoder().feed(f).single().contentEquals(payload))
    }

    @Test fun shortOrWrongPayloadsAreRejected() {
        assertNull(VescProtocol.parseValues(byteArrayOf(4, 0, 1)))
        assertNull(VescProtocol.parseValues(byteArrayOf(5) + ByteArray(60)))
    }

    @Test fun speedDistanceAndBattery() {
        // 15000 ERPM / 15 pole pairs = 1000 wheel rpm on a 283 mm tyre.
        assertEquals(1000 / 60.0 * PI * 0.283, VescProtocol.speedMps(15000.0, 15, 283.0), 1e-9)
        // 6 commutation steps x 15 pole pairs per wheel turn.
        assertEquals(PI * 0.283, VescProtocol.distanceM(90, 15, 283.0), 1e-9)
        assertEquals(0, LiIon.percent(2.9)); assertEquals(100, LiIon.percent(4.25)); assertEquals(50, LiIon.percent(3.74))
        val v = VescProtocol.parseValues(VescProtocol.Decoder().feed(valuesFrame.hexToBytes())[0])!!
        val t = VescProtocol.toTelemetry(v, 15, 283.0, 20, 1L)
        assertEquals(LiIon.percent(72.5 / 20), t.batteryPct)
        assertEquals(72.5 * 5.67, t.powerW!!, 1e-9)
        assertEquals(VescProtocol.speedMps(15000.0, 15, 283.0), t.speedMps!!, 1e-9)
    }
}
