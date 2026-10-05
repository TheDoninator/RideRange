package com.elect.riderange.vehicle.vesc

import com.elect.riderange.scooter.Telemetry
import kotlin.math.PI

/**
 * VESC UART/BLE packet format, written from the publicly documented layouts (not copied from the GPL VESC sources):
 *
 *   short frame:  0x02, len (1 byte),  payload, crc16 (big-endian), 0x03     (payload ≤ 255 bytes)
 *   long frame:   0x03, len (2 bytes), payload, crc16 (big-endian), 0x03
 *
 * The CRC is CRC-16/XMODEM (poly 0x1021, init 0, no reflection) over the payload only. Payload byte 0 is the
 * command id (the `COMM_PACKET_ID` enum in the bldc firmware's `datatypes.h`). Numbers are big-endian; the firmware's
 * `buffer_append_float16/float32(value, scale)` (bldc `util/buffer.c`) are plain signed integers holding value × scale,
 * and `buffer_append_float32_auto` writes the IEEE-754 single-precision bit pattern (subnormals flushed to 0).
 *
 * Reply layouts below follow the field order documented in the bldc firmware's `comm/commands.c` (COMM_GET_VALUES,
 * COMM_GET_VALUES_SETUP, COMM_PING_CAN, COMM_FORWARD_CAN) and `bms.c` / vesc_bms_fw (COMM_BMS_GET_VALUES), firmware 5.x
 * and 6.x. Newer firmware appends fields at the end; older firmware stops earlier. Every parser reads the fields it
 * knows in order and leaves later ones null when the payload ends, so a different firmware shows what parses.
 *
 * READ-ONLY: [isReadOnly] is an allow-list of "get" requests. [readOnlyFrame] (and so [request], [forwardCan],
 * [ReadOnlyWriter]) refuses anything else, so motor, configuration, firmware and package commands can't be built.
 */
object VescProtocol {
    const val COMM_FW_VERSION = 0
    const val COMM_GET_VALUES = 4
    const val COMM_FORWARD_CAN = 34
    const val COMM_CUSTOM_APP_DATA = 36
    const val COMM_GET_VALUES_SETUP = 47
    const val COMM_PING_CAN = 62
    const val COMM_BMS_GET_VALUES = 96

    /** Plain reads that take no arguments. */
    val GET_COMMANDS = setOf(COMM_FW_VERSION, COMM_GET_VALUES, COMM_GET_VALUES_SETUP, COMM_PING_CAN, COMM_BMS_GET_VALUES)

    /**
     * Every command id RideRange may send. The two wrappers are checked by content: COMM_FORWARD_CAN only around one
     * of these reads (never nested), COMM_CUSTOM_APP_DATA only with the Float package's read sub-commands.
     */
    val READ_ONLY_COMMANDS = GET_COMMANDS + setOf(COMM_FORWARD_CAN, COMM_CUSTOM_APP_DATA)

    /** CAN ids are 0..254 (255 is the broadcast id: never used). */
    const val MAX_CAN_ID = 254

    /** True if [payload] (command id + arguments) is one of the allowed read requests. */
    fun isReadOnly(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        return when (payload[0].toInt() and 0xFF) {
            in GET_COMMANDS -> payload.size == 1
            COMM_CUSTOM_APP_DATA -> FloatPackage.isReadOnlyRequest(payload.copyOfRange(1, payload.size))
            COMM_FORWARD_CAN -> payload.size >= 3 && (payload[1].toInt() and 0xFF) <= MAX_CAN_ID &&
                (payload[2].toInt() and 0xFF) != COMM_FORWARD_CAN && isReadOnly(payload.copyOfRange(2, payload.size))
            else -> false
        }
    }

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    /** Wraps [payload] in a frame (no content check: anything sent to a vehicle goes through [readOnlyFrame]). */
    fun frame(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= 0xFFFF)
        val crc = crc16(payload)
        val head = if (payload.size <= 255) byteArrayOf(2, payload.size.toByte())
        else byteArrayOf(3, (payload.size shr 8).toByte(), payload.size.toByte())
        return head + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3)
    }

    /** The only way RideRange builds bytes for a vehicle: frames [payload] if it is an allowed read, else throws. */
    fun readOnlyFrame(payload: ByteArray): ByteArray {
        require(isReadOnly(payload)) {
            "RideRange is read-only: command ${payload.firstOrNull()?.toInt()?.and(0xFF)} (${payload.size} bytes) is not allowed"
        }
        return frame(payload)
    }

    /** A framed request with no arguments. Refuses anything that isn't a read. */
    fun request(command: Int): ByteArray = readOnlyFrame(byteArrayOf(command.toByte()))

    /** Payload asking the controller with CAN id [canId] for [inner] (itself an allowed read). */
    fun forwardCanPayload(canId: Int, inner: ByteArray): ByteArray {
        require(canId in 0..MAX_CAN_ID) { "bad CAN id $canId" }
        return byteArrayOf(COMM_FORWARD_CAN.toByte(), canId.toByte()) + inner
    }

    fun forwardCan(canId: Int, inner: ByteArray): ByteArray = readOnlyFrame(forwardCanPayload(canId, inner))

    /** Sends payloads through [raw] (BLE or the debug simulator) after the read-only check. */
    class ReadOnlyWriter(private val raw: (ByteArray) -> Unit) {
        var sent = 0
            private set

        fun send(payload: ByteArray) {
            val f = readOnlyFrame(payload)
            sent++
            raw(f)
        }
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

    /**
     * Big-endian reader over a payload. The `opt…` reads return null once the payload has run out (fields are only
     * ever missing at the end), and stay null afterwards.
     */
    class Reader(private val b: ByteArray, var pos: Int = 0) {
        val remaining: Int get() = b.size - pos
        var truncated = false
            private set

        private fun avail(n: Int): Boolean {
            if (truncated || remaining < n) { truncated = true; return false }
            return true
        }

        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun s16(): Int = ((b[pos].toInt() shl 8) or (b[pos + 1].toInt() and 0xFF)).toShort().toInt().also { pos += 2 }
        fun s32(): Int = ((b[pos].toInt() and 0xFF) shl 24 or ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)).also { pos += 4 }
        fun f16(scale: Double) = s16() / scale
        fun f32(scale: Double) = s32() / scale

        fun optU8(): Int? = if (avail(1)) u8() else null
        fun optF16(scale: Double): Double? = if (avail(2)) f16(scale) else null
        fun optF32(scale: Double): Double? = if (avail(4)) f32(scale) else null
        fun optU32(): Long? = if (avail(4)) s32().toLong() and 0xFFFFFFFFL else null
        /** `float32_auto`: IEEE-754 bits; NaN/infinite read as null. */
        fun optFloatAuto(): Double? = if (avail(4)) java.lang.Float.intBitsToFloat(s32()).toDouble().takeIf { it.isFinite() } else null
        fun skip(n: Int): Boolean = avail(n).also { if (it) pos += n }
    }

    private fun cmd(payload: ByteArray) = if (payload.isEmpty()) -1 else payload[0].toInt() and 0xFF

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
     * Field order (COMM_GET_VALUES in commands.c): temp_mosfet s16/10, temp_motor s16/10, avg_motor_current s32/100,
     * avg_input_current s32/100, avg_id s32/100, avg_iq s32/100, duty s16/1000, erpm s32/1, v_in s16/10,
     * amp_hours s32/1e4, amp_hours_charged s32/1e4, watt_hours s32/1e4, watt_hours_charged s32/1e4, tachometer s32,
     * tachometer_abs s32, fault u8, then (3.x+) pid_pos s32/1e6, controller id u8, then (5.x+) per-MOSFET temps,
     * vd, vq, status (ignored).
     */
    fun parseValues(payload: ByteArray): Values? {
        if (cmd(payload) != COMM_GET_VALUES || payload.size < 1 + 53) return null
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
        val id = if (r.skip(4)) r.optU8() else null     // pid_pos, then controller id
        return Values(tFet, tMot, iMot, iIn, duty, erpm, vIn, ah, ahC, wh, whC, tach, tachAbs, fault, id)
    }

    /**
     * COMM_GET_VALUES_SETUP reply: the controller's own view using its configured battery, wheel and gearing, with
     * currents and Ah/Wh summed over every VESC it hears on CAN ([numVescs]). Order (commands.c, unmasked request):
     * temp_fet s16/10, temp_motor s16/10, current_tot s32/100, current_in_tot s32/100, duty s16/1000, rpm s32/1,
     * speed s32/1000 (m/s), v_in s16/10, battery_level s16/1000 (0..1), ah_tot s32/1e4, ah_charge_tot s32/1e4,
     * wh_tot s32/1e4, wh_charge_tot s32/1e4, distance s32/1000 (m), distance_abs s32/1000, pid_pos s32/1e6, fault u8,
     * controller_id u8, num_vescs u8, wh_batt_left s32/1000, odometer u32 (m), uptime u32 (ms).
     */
    data class ValuesSetup(
        val tempMosfetC: Double,
        val tempMotorC: Double,
        val currentTotA: Double,
        val currentInTotA: Double,
        val dutyCycle: Double,
        val rpm: Double,
        val speedMps: Double,
        val inputVoltage: Double,
        val batteryLevel: Double? = null,
        val ampHours: Double? = null,
        val ampHoursCharged: Double? = null,
        val wattHours: Double? = null,
        val wattHoursCharged: Double? = null,
        val distanceM: Double? = null,
        val distanceAbsM: Double? = null,
        val faultCode: Int? = null,
        val controllerId: Int? = null,
        val numVescs: Int? = null,
        val whBatteryLeft: Double? = null,
        val odometerM: Long? = null,
        val uptimeMs: Long? = null,
    )

    fun parseValuesSetup(payload: ByteArray): ValuesSetup? {
        if (cmd(payload) != COMM_GET_VALUES_SETUP || payload.size < 1 + 24) return null
        val r = Reader(payload, 1)
        val tFet = r.f16(10.0); val tMot = r.f16(10.0)
        val iTot = r.f32(100.0); val iInTot = r.f32(100.0)
        val duty = r.f16(1000.0); val rpm = r.s32().toDouble(); val speed = r.f32(1000.0); val vIn = r.f16(10.0)
        val level = r.optF16(1000.0)
        val ah = r.optF32(1e4); val ahC = r.optF32(1e4); val wh = r.optF32(1e4); val whC = r.optF32(1e4)
        val dist = r.optF32(1000.0); val distAbs = r.optF32(1000.0)
        r.optF32(1e6)                                   // pid_pos
        val fault = r.optU8(); val id = r.optU8(); val num = r.optU8()
        val whLeft = r.optF32(1000.0); val odo = r.optU32(); val up = r.optU32()
        return ValuesSetup(tFet, tMot, iTot, iInTot, duty, rpm, speed, vIn, level?.takeIf { it in -0.05..1.05 }?.coerceIn(0.0, 1.0),
            ah, ahC, wh, whC, dist, distAbs, fault, id, num, whLeft, odo, up)
    }

    /** COMM_PING_CAN reply: the CAN ids that answered a ping, one byte each. */
    fun parsePingCan(payload: ByteArray): List<Int>? {
        if (cmd(payload) != COMM_PING_CAN) return null
        return (1 until payload.size).map { payload[it].toInt() and 0xFF }.filter { it <= MAX_CAN_ID }.distinct()
    }

    /**
     * COMM_BMS_GET_VALUES reply (VESC BMS, relayed by the motor controller from the BMS's CAN status messages).
     * Order (bms.c): v_tot s32/1e6, v_charge s32/1e6, i_in s32/1e6, i_in_ic s32/1e6, ah_cnt s32/1e3, wh_cnt s32/1e3,
     * cell_num u8, cell voltages s16/1e3 × n, balancing state u8 × n, temp_adc_num u8, temps s16/1e2 × m, temp_ic s16/1e2,
     * temp_hum s16/1e2, humidity s16/1e2, temp_max_cell s16/1e2, soc s16/1e3 (0..1), soh s16/1e3, can_id u8,
     * ah_chg_total / wh_chg_total / ah_dis_total / wh_dis_total float32_auto.
     * A controller without a BMS answers with zeros (no cells): [present] is false then.
     */
    data class Bms(
        val packV: Double,
        val chargeV: Double,
        val currentA: Double,
        val currentIcA: Double,
        val ahCount: Double,
        val whCount: Double,
        val cellV: List<Double>,
        val balancing: List<Boolean>,
        val tempsC: List<Double> = emptyList(),
        val tempIcC: Double? = null,
        val humidityTempC: Double? = null,
        val humidityPct: Double? = null,
        val tempMaxCellC: Double? = null,
        val soc: Double? = null,
        val soh: Double? = null,
        val canId: Int? = null,
        val ahChargeTotal: Double? = null,
        val whChargeTotal: Double? = null,
        val ahDischargeTotal: Double? = null,
        val whDischargeTotal: Double? = null,
    ) {
        val present: Boolean get() = cellV.isNotEmpty() && packV > 1.0
        val cellMin: Double? get() = cellV.minOrNull()
        val cellMax: Double? get() = cellV.maxOrNull()
        val cellDelta: Double? get() = if (cellV.isEmpty()) null else cellV.max() - cellV.min()
        val balancingCount: Int get() = balancing.count { it }
    }

    const val MAX_BMS_CELLS = 128
    const val MAX_BMS_TEMPS = 64

    fun parseBms(payload: ByteArray): Bms? {
        if (cmd(payload) != COMM_BMS_GET_VALUES || payload.size < 1 + 24 + 1) return null
        val r = Reader(payload, 1)
        val vTot = r.f32(1e6); val vChg = r.f32(1e6); val iIn = r.f32(1e6); val iIc = r.f32(1e6)
        val ah = r.f32(1e3); val wh = r.f32(1e3)
        val n = r.u8()
        if (n > MAX_BMS_CELLS || r.remaining < n * 3) return null
        val cells = List(n) { r.f16(1e3) }
        val bal = List(n) { r.u8() != 0 }
        val m = r.optU8()?.takeIf { it <= MAX_BMS_TEMPS && r.remaining >= it * 2 } ?: 0
        val temps = List(m) { r.f16(1e2) }
        val tIc = r.optF16(1e2); val tHum = r.optF16(1e2); val hum = r.optF16(1e2); val tMax = r.optF16(1e2)
        val soc = r.optF16(1e3); val soh = r.optF16(1e3); val canId = r.optU8()
        val ahChg = r.optFloatAuto(); val whChg = r.optFloatAuto(); val ahDis = r.optFloatAuto(); val whDis = r.optFloatAuto()
        return Bms(vTot, vChg, iIn, iIc, ah, wh, cells, bal, temps, tIc, tHum, hum, tMax,
            soc?.takeIf { it in -0.05..1.05 }?.coerceIn(0.0, 1.0), soh?.takeIf { it in 0.0..1.5 }, canId, ahChg, whChg, ahDis, whDis)
    }

    /**
     * COMM_FW_VERSION reply: major, minor, a zero-terminated hardware name, then (newer firmware) a 12-byte UUID,
     * "pairing done" u8, test-build number u8 (0 = release), hardware type u8 (0 VESC, 1 VESC BMS, 2 custom module).
     */
    data class FwVersion(val major: Int, val minor: Int, val hardware: String?, val testBuild: Int? = null, val hwType: Int? = null) {
        val label: String get() = "$major.${"%02d".format(minor)}" + (testBuild?.takeIf { it > 0 }?.let { " beta $it" } ?: "") +
            (hardware?.let { " ($it)" } ?: "")
    }

    fun parseFwVersion(payload: ByteArray): FwVersion? {
        if (payload.size < 3 || cmd(payload) != COMM_FW_VERSION) return null
        val end = (3 until payload.size).firstOrNull { payload[it] == 0.toByte() } ?: payload.size
        val name = if (end > 3) String(payload, 3, end - 3, Charsets.US_ASCII).filter { it in ' '..'~' }.ifBlank { null } else null
        val r = Reader(payload, minOf(end + 1, payload.size))
        var test: Int? = null
        var hw: Int? = null
        if (r.skip(12)) { r.optU8(); test = r.optU8(); hw = r.optU8() }
        return FwVersion(payload[1].toInt() and 0xFF, payload[2].toInt() and 0xFF, name, test, hw)
    }

    /** Wheel speed from electrical RPM: wheel rpm = erpm / pole pairs / gear ratio (motor turns per wheel turn). */
    fun speedMps(erpm: Double, polePairs: Int, wheelDiameterMm: Double, gearRatio: Double = 1.0): Double =
        if (polePairs <= 0 || gearRatio <= 0) 0.0 else erpm / polePairs / gearRatio / 60.0 * PI * wheelDiameterMm / 1000.0

    /** One tachometer step is one commutation: 6 × pole pairs steps per motor turn. */
    fun distanceM(tachometerAbs: Int, polePairs: Int, wheelDiameterMm: Double, gearRatio: Double = 1.0): Double =
        if (polePairs <= 0 || gearRatio <= 0) 0.0 else tachometerAbs.toDouble() / (6.0 * polePairs) / gearRatio * PI * wheelDiameterMm / 1000.0

    /** Telemetry from one controller's COMM_GET_VALUES only (no CAN, BMS or setup values). */
    fun toTelemetry(v: Values, polePairs: Int, wheelDiameterMm: Double, cellsSeries: Int?, nowMs: Long, gearRatio: Double = 1.0): Telemetry = Telemetry(
        speedKmh = kotlin.math.abs(speedMps(v.erpm, polePairs, wheelDiameterMm, gearRatio)) * 3.6,
        batteryPct = cellsSeries?.let { LiIon.percent(v.inputVoltage / it) },
        voltage = v.inputVoltage,
        current = v.inputCurrentA,
        scooterTempC = v.tempMosfetC,
        batteryTempC = null,
        odometerM = distanceM(v.tachometerAbs, polePairs, wheelDiameterMm, gearRatio).toLong(),
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
