package com.elect.riderange.vehicle.vesc

import com.elect.riderange.scooter.Telemetry
import kotlin.math.PI

/**
 * VESC UART/BLE packet format, written from the publicly documented frame layout (not copied from the GPL VESC
 * sources):
 *
 *   short frame:  0x02, len (1 byte),  payload, crc16 (big-endian), 0x03     (payload ≤ 255 bytes)
 *   long frame:   0x03, len (2 bytes), payload, crc16 (big-endian), 0x03
 *
 * The CRC is CRC-16/XMODEM (poly 0x1021, init 0, no reflection) over the payload only. Payload byte 0 is the
 * command id. Numbers are big-endian and fixed-point (value / scale).
 */
object VescProtocol {
    const val COMM_FW_VERSION = 0
    const val COMM_GET_VALUES = 4

    /** RideRange only ever sends these (read-only: no motor, config or write commands can be encoded). */
    val READ_ONLY_COMMANDS = setOf(COMM_FW_VERSION, COMM_GET_VALUES)

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    /** Wraps [payload] in a frame. */
    fun frame(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= 0xFFFF)
        val crc = crc16(payload)
        val head = if (payload.size <= 255) byteArrayOf(2, payload.size.toByte())
        else byteArrayOf(3, (payload.size shr 8).toByte(), payload.size.toByte())
        return head + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
    }

    /** A framed request with no arguments. Refuses anything that isn't a read. */
    fun request(command: Int): ByteArray {
        require(command in READ_ONLY_COMMANDS) { "RideRange is read-only: command $command is not allowed" }
        return frame(byteArrayOf(command.toByte()))
    }

    /**
     * Reassembles frames from BLE notifications (a frame is usually split over several 20-byte notifications, and
     * a notification can hold the end of one frame and the start of the next). Bad CRCs and garbage are skipped.
     */
    class Decoder(private val maxPayload: Int = 1024) {
        private var buf = ByteArray(0)
        var crcErrors = 0
            private set

        /** Frame check at [i]: total length if a valid frame starts there, 0 if not, -1 if it might once more bytes arrive. */
        private fun check(i: Int): Int {
            val b0 = buf[i]
            if (b0 != 2.toByte() && b0 != 3.toByte()) return 0
            val long = b0 == 3.toByte()
            val headLen = if (long) 3 else 2
            if (buf.size - i < headLen) return -1
            val len = if (long) ((buf[i + 1].toInt() and 0xFF) shl 8) or (buf[i + 2].toInt() and 0xFF) else buf[i + 1].toInt() and 0xFF
            if (len == 0 || len > maxPayload) return 0
            val total = headLen + len + 3
            if (buf.size - i < total) return -1
            val payloadEnd = i + headLen + len
            val crc = ((buf[payloadEnd].toInt() and 0xFF) shl 8) or (buf[payloadEnd + 1].toInt() and 0xFF)
            return if (buf[i + total - 1] == 3.toByte() && crc16(buf, i + headLen, payloadEnd) == crc) total else 0
        }

        fun feed(chunk: ByteArray): List<ByteArray> {
            buf += chunk
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i < buf.size) {
                val c = check(i)
                when {
                    c > 0 -> {
                        val headLen = if (buf[i] == 3.toByte()) 3 else 2
                        out += buf.copyOfRange(i + headLen, i + c - 3)
                        i += c
                    }
                    c == 0 -> {
                        if (buf[i] == 2.toByte() || buf[i] == 3.toByte()) crcErrors++
                        i++
                    }
                    else -> {
                        // Incomplete candidate. If a complete valid frame starts later, this start byte was noise.
                        val later = (i + 1 until buf.size).firstOrNull { check(it) > 0 }
                        if (later == null) break
                        crcErrors++
                        i = later
                    }
                }
            }
            buf = buf.copyOfRange(i, buf.size)
            if (buf.size > 4 * maxPayload) buf = ByteArray(0)
            return out
        }
    }

    /** Big-endian reader over a payload. */
    class Reader(private val b: ByteArray, var pos: Int = 0) {
        val remaining: Int get() = b.size - pos
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun s16(): Int = ((b[pos].toInt() shl 8) or (b[pos + 1].toInt() and 0xFF)).toShort().toInt().also { pos += 2 }
        fun s32(): Int = ((b[pos].toInt() and 0xFF) shl 24 or ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)).also { pos += 4 }
        fun f16(scale: Double) = s16() / scale
        fun f32(scale: Double) = s32() / scale
    }

    /** COMM_GET_VALUES reply (fields after `faultCode` only exist on newer firmware and may be missing). */
    data class Values(
        val tempMosfetC: Double,
        val tempMotorC: Double,
        val motorCurrentA: Double,
        val inputCurrentA: Double,
        val dutyCycle: Double,
        val erpm: Double,
        val inputVoltage: Double,
        val ampHours: Double,
        val ampHoursCharged: Double,
        val wattHours: Double,
        val wattHoursCharged: Double,
        val tachometer: Int,
        val tachometerAbs: Int,
        val faultCode: Int,
        val controllerId: Int? = null,
    ) {
        val inputPowerW: Double get() = inputVoltage * inputCurrentA
    }

    /**
     * Field order (documented VESC COMM_GET_VALUES layout): temp_mosfet s16/10, temp_motor s16/10,
     * avg_motor_current s32/100, avg_input_current s32/100, avg_id s32/100, avg_iq s32/100, duty s16/1000,
     * erpm s32/1, v_in s16/10, amp_hours s32/1e4, amp_hours_charged s32/1e4, watt_hours s32/1e4,
     * watt_hours_charged s32/1e4, tachometer s32, tachometer_abs s32, fault u8, then pid_pos s32/1e6, controller id u8, …
     */
    fun parseValues(payload: ByteArray): Values? {
        if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != COMM_GET_VALUES) return null
        if (payload.size < 1 + 53) return null
        val r = Reader(payload, 1)
        val tFet = r.f16(10.0)
        val tMot = r.f16(10.0)
        val iMot = r.f32(100.0)
        val iIn = r.f32(100.0)
        r.s32(); r.s32()                         // avg_id, avg_iq
        val duty = r.f16(1000.0)
        val erpm = r.s32().toDouble()
        val vIn = r.f16(10.0)
        val ah = r.f32(1e4)
        val ahC = r.f32(1e4)
        val wh = r.f32(1e4)
        val whC = r.f32(1e4)
        val tach = r.s32()
        val tachAbs = r.s32()
        val fault = r.u8()
        var id: Int? = null
        if (r.remaining >= 5) { r.s32(); id = r.u8() }
        return Values(tFet, tMot, iMot, iIn, duty, erpm, vIn, ah, ahC, wh, whC, tach, tachAbs, fault, id)
    }

    /** COMM_FW_VERSION reply: major, minor, then a zero-terminated hardware name. */
    data class FwVersion(val major: Int, val minor: Int, val hardware: String?)

    fun parseFwVersion(payload: ByteArray): FwVersion? {
        if (payload.size < 3 || (payload[0].toInt() and 0xFF) != COMM_FW_VERSION) return null
        val end = (3 until payload.size).firstOrNull { payload[it] == 0.toByte() } ?: payload.size
        val name = if (end > 3) String(payload, 3, end - 3, Charsets.US_ASCII) else null
        return FwVersion(payload[1].toInt() and 0xFF, payload[2].toInt() and 0xFF, name)
    }

    /** Wheel speed from electrical RPM: wheel rpm = erpm / pole pairs (hub motor, no gearing). */
    fun speedMps(erpm: Double, polePairs: Int, wheelDiameterMm: Double): Double =
        if (polePairs <= 0) 0.0 else erpm / polePairs / 60.0 * PI * wheelDiameterMm / 1000.0

    /** One tachometer step is one commutation: 6 × pole pairs steps per wheel turn. */
    fun distanceM(tachometerAbs: Int, polePairs: Int, wheelDiameterMm: Double): Double =
        if (polePairs <= 0) 0.0 else tachometerAbs.toDouble() / (6.0 * polePairs) * PI * wheelDiameterMm / 1000.0

    fun toTelemetry(v: Values, polePairs: Int, wheelDiameterMm: Double, cellsSeries: Int?, nowMs: Long): Telemetry = Telemetry(
        speedKmh = kotlin.math.abs(speedMps(v.erpm, polePairs, wheelDiameterMm)) * 3.6,
        batteryPct = cellsSeries?.let { LiIon.percent(v.inputVoltage / it) },
        voltage = v.inputVoltage,
        current = v.inputCurrentA,
        scooterTempC = v.tempMosfetC,
        batteryTempC = null,
        odometerM = distanceM(v.tachometerAbs, polePairs, wheelDiameterMm).toLong(),
        updatedMs = nowMs,
    )
}

/** Resting Li-ion (NMC/NCA 18650/21700) cell voltage -> state of charge, piecewise linear. */
object LiIon {
    private val curve = listOf(
        3.00 to 0.0, 3.30 to 5.0, 3.45 to 10.0, 3.55 to 20.0, 3.62 to 30.0, 3.68 to 40.0, 3.74 to 50.0,
        3.81 to 60.0, 3.88 to 70.0, 3.96 to 80.0, 4.06 to 90.0, 4.20 to 100.0,
    )

    fun percent(cellV: Double): Int {
        if (cellV <= curve.first().first) return 0
        if (cellV >= curve.last().first) return 100
        for (i in 1 until curve.size) {
            val (v1, p1) = curve[i]
            if (cellV <= v1) {
                val (v0, p0) = curve[i - 1]
                return Math.round(p0 + (p1 - p0) * (cellV - v0) / (v1 - v0)).toInt()
            }
        }
        return 100
    }
}
