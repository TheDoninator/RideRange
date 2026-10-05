package com.elect.riderange.vehicle

import com.elect.riderange.scooter.Telemetry
import com.elect.riderange.vehicle.onewheel.FmAccess
import com.elect.riderange.vehicle.onewheel.FmAccess.Access
import com.elect.riderange.vehicle.onewheel.FmParse
import com.elect.riderange.vehicle.onewheel.FmUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FmProtocolTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    @Test fun uuidsAreTheDocumentedOnes() {
        assertEquals("e659f300-ea98-11e3-ac10-0800200c9a66", FmUuids.SERVICE.toString())
        assertEquals("e659f30b-ea98-11e3-ac10-0800200c9a66", FmUuids.SPEED_RPM.toString())
        assertEquals("e659f311-ea98-11e3-ac10-0800200c9a66", FmUuids.FIRMWARE.toString())
    }

    @Test fun firmwareGate() {
        assertEquals(Access.OPEN, FmAccess.decide(3034))           // Onewheel+ before Gemini
        assertEquals(Access.LOCKED_GEMINI, FmAccess.decide(4034))  // Onewheel+ Gemini
        assertEquals(Access.LOCKED_GEMINI, FmAccess.decide(4134))  // XR Gemini
        assertEquals(Access.LOCKED_SERVER_KEY, FmAccess.decide(4142, hardware = 4210))  // late-2019 XR
        assertEquals(Access.LOCKED_SERVER_KEY, FmAccess.decide(5059))  // Pint
        assertEquals(Access.LOCKED_SERVER_KEY, FmAccess.decide(6109))  // GT
        assertEquals(Access.UNKNOWN, FmAccess.decide(null))
        assertTrue(FmAccess.message(Access.LOCKED_SERVER_KEY, 5059).contains("Manual mode"))
    }

    @Test fun parsesValues() {
        assertEquals(4134, FmParse.firmware(b(0x10, 0x26)))
        assertEquals(87, FmParse.batteryPct(b(0x00, 87)))
        assertNull(FmParse.batteryPct(b(0x00, 150)))
        assertEquals(58.7, FmParse.voltage(b(0x02, 0x4B))!!, 1e-9)
        assertEquals(-0.9, FmParse.currentA(b(0xFC, 0x18), plus = false)!!, 1e-9)   // -1000 mA x 0.9
        assertEquals(1.8, FmParse.currentA(b(0x03, 0xE8), plus = true)!!, 1e-9)
        assertEquals(31 to 44, FmParse.temps(b(31, 44)))
        assertEquals(30, FmParse.batteryTemp(b(29, 31)))
        assertEquals(1.5, FmParse.ampHours(b(0x00, 75))!!, 1e-9)
    }

    @Test fun rpmMatchesPOnewheelConversion() {
        // pOnewheel: mph = rpm x 35 in x 60 / 63360 (35 in tyre circumference = 283 mm diameter).
        val rpm = 300
        val pOnewheelMph = 60.0 * 35.0 * rpm / 63360.0
        assertEquals(pOnewheelMph, FmParse.speedMps(rpm, 889.0 / Math.PI) / 0.44704, 1e-9)
        assertEquals(pOnewheelMph, FmParse.speedMps(rpm) / 0.44704, 0.05)
    }

    @Test fun applyBuildsTelemetry() {
        var t = Telemetry()
        t = FmParse.apply(t, FmUuids.BATTERY_PCT, b(0, 64), 283.0, false, 5)
        t = FmParse.apply(t, FmUuids.VOLTAGE, b(0x02, 0x4B), 283.0, false, 6)
        t = FmParse.apply(t, FmUuids.SPEED_RPM, b(0x01, 0x2C), 283.0, false, 7)
        t = FmParse.apply(t, FmUuids.TEMPERATURE, b(35, 40), 283.0, false, 8)
        t = FmParse.apply(t, FmUuids.TRIP_ODOMETER, b(0x03, 0xE8), 283.0, false, 9)
        assertEquals(64, t.batteryPct)
        assertEquals(58.7, t.voltage!!, 1e-9)
        assertEquals(FmParse.speedMps(300, 283.0), t.speedMps!!, 1e-9)
        assertEquals(35.0, t.scooterTempC!!, 1e-9)
        assertEquals((1000 * Math.PI * 0.283).toLong(), t.odometerM)
        assertEquals(9L, t.updatedMs)
        assertFalse(FmParse.looksLocked(t))
        // A locked board reports zeros and no voltage.
        assertTrue(FmParse.looksLocked(FmParse.apply(Telemetry(), FmUuids.BATTERY_PCT, b(0, 0), 283.0, false, 1)))
    }
}
