package com.elect.riderange.vehicle.onewheel

import com.elect.riderange.scooter.Telemetry
import kotlin.math.PI

/**
 * Future Motion Onewheel BLE characteristics, from the community documentation (pOnewheel `OWDevice.java`, MIT
 * licence, github.com/ponewheel/android-ponewheel; OWCE README). Values are big-endian 16-bit unless noted.
 *
 * Read-only on purpose: RideRange reads/subscribes to these characteristics and never writes to the board.
 * Boards whose firmware requires Future Motion's app authentication are NOT unlocked; see [FmAccess].
 */
object FmUuids {
    private fun u(short: String): String = "e659$short-ea98-11e3-ac10-0800200c9a66"
    val SERVICE: String = u("f300")
    val SERIAL: String = u("f301")
    val RIDE_MODE: String = u("f302")
    val BATTERY_PCT: String = u("f303")
    val TRIP_ODOMETER: String = u("f30a")      // tyre revolutions this trip
    val SPEED_RPM: String = u("f30b")
    val STATUS: String = u("f30f")
    val TEMPERATURE: String = u("f310")        // byte 0 controller °C, byte 1 motor °C
    val FIRMWARE: String = u("f311")
    val CURRENT: String = u("f312")            // signed, mA × hardware factor
    val TRIP_AH: String = u("f313")            // /50
    val TRIP_REGEN_AH: String = u("f314")      // /50
    val BATTERY_TEMP: String = u("f315")       // two bytes °C
    val VOLTAGE: String = u("f316")            // /10 V (removed on XR fw ≥ 4155, Pint fw ≥ 5059 per OWCE)
    val HARDWARE: String = u("f318")
    val LIFETIME_ODOMETER: String = u("f319")  // miles
    val CCCD: String = "00002902-0000-1000-8000-00805f9b34fb"

    /** Subscribed when the board's firmware gives data without authentication. */
    val NOTIFY = listOf(SPEED_RPM, BATTERY_PCT, VOLTAGE, CURRENT, TEMPERATURE, BATTERY_TEMP, TRIP_ODOMETER)
}

/**
 * Whether a board's firmware hands out telemetry to a third-party app without Future Motion's authentication.
 *
 *  - firmware < 4034 (Onewheel V1 and Onewheel+ before the 2018 "Gemini" update): open, data flows.
 *  - 4034–4999 ("Gemini", Onewheel+ 4034 / XR 4134 and later XR builds): the board answers only after a
 *    challenge-response whose secret comes from Future Motion's app. Without it, values read 0 and the board
 *    stops talking after ~24 s (pOnewheel issue #86, UWP-Onewheel README, onewheel-web-bluetooth README).
 *  - ≥ 5000 (Pint / Pint X / Pint S, XR hardware ≥ 4210, GT/GT-S at 6xxx+): a per-board key fetched from
 *    Future Motion's servers (pOnewheel issues #109, #111, #114).
 *
 * Both locked cases would need the secret or the server key, i.e. circumventing Future Motion's protection, which
 * RideRange will not do. Those boards run in manual mode (GPS speed + battery slider).
 */
object FmAccess {
    const val GEMINI_FIRST = 4034
    const val SERVER_KEY_FIRST = 5000

    enum class Access { OPEN, LOCKED_GEMINI, LOCKED_SERVER_KEY, UNKNOWN }

    fun decide(firmware: Int?, hardware: Int? = null): Access = when {
        firmware == null || firmware <= 0 -> Access.UNKNOWN
        hardware != null && hardware in 4210..4999 -> Access.LOCKED_SERVER_KEY
        firmware < GEMINI_FIRST -> Access.OPEN
        firmware < SERVER_KEY_FIRST -> Access.LOCKED_GEMINI
        else -> Access.LOCKED_SERVER_KEY
    }

    fun message(a: Access, firmware: Int?): String = when (a) {
        Access.OPEN -> "Firmware $firmware shares live data with other apps: reading it (read-only)."
        Access.LOCKED_GEMINI, Access.LOCKED_SERVER_KEY ->
            "This board's firmware ($firmware) only talks to Future Motion's own app (it needs their authentication), " +
                "and RideRange doesn't get around that. Manual mode: speed from GPS, battery from the slider on the Ride tab."
        Access.UNKNOWN -> "Couldn't read the board's firmware version. Manual mode: GPS speed and the battery slider."
    }
}

/** Pure decoding of the characteristic values (unit-tested with hand-made byte arrays). */
object FmParse {
    /** pOnewheel converts with a 35" (889 mm) tyre circumference; RideRange uses the vehicle's tyre diameter. */
    const val DEFAULT_WHEEL_MM = 283.0

    fun u16(b: ByteArray): Int? = if (b.size >= 2) ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF) else null
    fun s16(b: ByteArray): Int? = u16(b)?.toShort()?.toInt()

    fun firmware(b: ByteArray): Int? = u16(b)
    /** Battery % is the low byte (byte 1). */
    fun batteryPct(b: ByteArray): Int? = if (b.size >= 2) (b[1].toInt() and 0xFF).takeIf { it in 0..100 } else null
    fun rpm(b: ByteArray): Int? = u16(b)
    fun voltage(b: ByteArray): Double? = u16(b)?.let { it / 10.0 }?.takeIf { it > 0 }
    /** [plus] = Onewheel+ hardware (factor 1.8), otherwise 0.9 (pOnewheel). Amps, positive = discharge. */
    fun currentA(b: ByteArray, plus: Boolean): Double? = s16(b)?.let { it / 1000.0 * if (plus) 1.8 else 0.9 }
    fun temps(b: ByteArray): Pair<Int, Int>? = if (b.size >= 2) (b[0].toInt() and 0xFF) to (b[1].toInt() and 0xFF) else null
    fun batteryTemp(b: ByteArray): Int? = temps(b)?.let { (a, c) -> (a + c) / 2 }
    fun ampHours(b: ByteArray): Double? = u16(b)?.let { it / 50.0 }
    fun revolutions(b: ByteArray): Int? = u16(b)

    fun speedMps(rpm: Int, wheelDiameterMm: Double = DEFAULT_WHEEL_MM): Double = rpm / 60.0 * PI * wheelDiameterMm / 1000.0
    fun revsToMeters(revs: Int, wheelDiameterMm: Double = DEFAULT_WHEEL_MM): Double = revs * PI * wheelDiameterMm / 1000.0

    /** Applies one notification/read to [t]. Unknown UUIDs leave it unchanged. */
    fun apply(t: Telemetry, uuid: String, value: ByteArray, wheelMm: Double, plusHardware: Boolean, nowMs: Long): Telemetry = when (uuid) {
        FmUuids.SPEED_RPM -> rpm(value)?.let { t.copy(speedKmh = speedMps(it, wheelMm) * 3.6, updatedMs = nowMs) } ?: t
        FmUuids.BATTERY_PCT -> batteryPct(value)?.let { t.copy(batteryPct = it, updatedMs = nowMs) } ?: t
        FmUuids.VOLTAGE -> voltage(value)?.let { t.copy(voltage = it, updatedMs = nowMs) } ?: t
        FmUuids.CURRENT -> currentA(value, plusHardware)?.let { t.copy(current = it, updatedMs = nowMs) } ?: t
        FmUuids.TEMPERATURE -> temps(value)?.let { t.copy(scooterTempC = it.first.toDouble(), updatedMs = nowMs) } ?: t
        FmUuids.BATTERY_TEMP -> batteryTemp(value)?.let { t.copy(batteryTempC = it, updatedMs = nowMs) } ?: t
        FmUuids.TRIP_ODOMETER -> revolutions(value)?.let { t.copy(odometerM = revsToMeters(it, wheelMm).toLong(), updatedMs = nowMs) } ?: t
        else -> t
    }

    /**
     * A locked board still accepts the connection but reports zeros. After a few seconds of notifications, all-zero
     * battery and voltage mean "locked" even if the firmware number suggested otherwise.
     */
    fun looksLocked(t: Telemetry): Boolean = (t.batteryPct == null || t.batteryPct == 0) && t.voltage == null
}
