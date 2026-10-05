package com.elect.riderange.vehicle.vesc

/**
 * The Float package (VESC package for self-balancing boards) talks over COMM_CUSTOM_APP_DATA: payload
 * `36, 101 (magic), sub-command, …`. Written from the package's documented telemetry layout (`float.c`,
 * `send_realtime_data` / FLOAT_COMMAND_GET_INFO, Float 1.x); no package code is copied.
 *
 * RideRange only ever sends the two read sub-commands, with no arguments:
 *   FLOAT_COMMAND_GET_INFO (0)   -> 101, 0, version (major × 10 + minor), build
 *   FLOAT_COMMAND_GET_RTDATA (1) -> 101, 1, then float32_auto / u8 fields (see [parseRtData])
 * Every other Float sub-command (tune, save config, handtest, flywheel, lights …) changes the board and is refused by
 * [isReadOnlyRequest]. Other package versions (or Refloat answering in its own layout) parse as far as the fields
 * look sane; anything after that, or a reply that doesn't start with the magic, is ignored.
 */
object FloatPackage {
    const val MAGIC = 101
    const val CMD_GET_INFO = 0
    const val CMD_GET_RTDATA = 1

    /** The only Float sub-commands RideRange sends. */
    val READ_ONLY_SUBCOMMANDS = setOf(CMD_GET_INFO, CMD_GET_RTDATA)

    /** [data] is what follows the COMM_CUSTOM_APP_DATA byte. Exactly `magic, read sub-command`. */
    fun isReadOnlyRequest(data: ByteArray): Boolean =
        data.size == 2 && (data[0].toInt() and 0xFF) == MAGIC && (data[1].toInt() and 0xFF) in READ_ONLY_SUBCOMMANDS

    fun infoRequest(): ByteArray = byteArrayOf(VescProtocol.COMM_CUSTOM_APP_DATA.toByte(), MAGIC.toByte(), CMD_GET_INFO.toByte())
    fun rtDataRequest(): ByteArray = byteArrayOf(VescProtocol.COMM_CUSTOM_APP_DATA.toByte(), MAGIC.toByte(), CMD_GET_RTDATA.toByte())

    /** Board state (low nibble of the state byte). */
    enum class State(val code: Int, val label: String, val running: Boolean = false, val fault: Boolean = false) {
        STARTUP(0, "Starting"),
        RUNNING(1, "Running", running = true),
        RUNNING_TILTBACK(2, "Tiltback", running = true),
        RUNNING_WHEELSLIP(3, "Wheelslip", running = true),
        RUNNING_UPSIDEDOWN(4, "Upside down", running = true),
        RUNNING_FLYWHEEL(5, "Flywheel", running = true),
        FAULT_ANGLE_PITCH(6, "Stopped: pitch angle", fault = true),
        FAULT_ANGLE_ROLL(7, "Stopped: roll angle", fault = true),
        FAULT_SWITCH_HALF(8, "Stopped: half footpad", fault = true),
        FAULT_SWITCH_FULL(9, "Ready (step on)", fault = true),
        FAULT_STARTUP(11, "Ready", fault = true),
        FAULT_REVERSE(12, "Stopped: reverse", fault = true),
        FAULT_QUICKSTOP(13, "Stopped: quickstop", fault = true),
        DISABLED(15, "Disabled");

        companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
    }

    /** What is moving the setpoint (high nibble of the state byte); TILTBACK_* = pushback. */
    enum class Setpoint(val code: Int, val label: String, val pushback: Boolean = false) {
        CENTERING(0, "Centering"),
        REVERSE_STOP(1, "Reverse stop"),
        NONE(2, "Normal"),
        TILTBACK_DUTY(3, "Pushback: duty cycle", pushback = true),
        TILTBACK_HV(4, "Pushback: high voltage", pushback = true),
        TILTBACK_LV(5, "Pushback: low voltage", pushback = true),
        TILTBACK_TEMP(6, "Pushback: temperature", pushback = true);

        companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
    }

    /** Footpad sensor state (low nibble of the switch byte). */
    enum class Footpad(val code: Int, val label: String) {
        OFF(0, "Off"), HALF(1, "One side"), ON(2, "Both");

        companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
    }

    data class Info(val major: Int, val minor: Int, val build: Int?) {
        val label: String get() = "$major.$minor" + (build?.let { " (build $it)" } ?: "")
    }

    data class RtData(
        val pidValue: Double? = null,
        val pitch: Double? = null,
        val roll: Double? = null,
        val stateCode: Int? = null,
        val setpointCode: Int? = null,
        val footpadCode: Int? = null,
        val beepReason: Int? = null,
        val adc1: Double? = null,
        val adc2: Double? = null,
        val setpoint: Double? = null,
        val atr: Double? = null,
        val brakeTilt: Double? = null,
        val torqueTilt: Double? = null,
        val turnTilt: Double? = null,
        val inputTilt: Double? = null,
        val truePitch: Double? = null,
        val atrCurrent: Double? = null,
        val accDiff: Double? = null,
        val boosterCurrent: Double? = null,
        val motorCurrent: Double? = null,
        val throttle: Double? = null,
    ) {
        val state: State? get() = stateCode?.let { State.of(it) }
        val setpointAdjust: Setpoint? get() = setpointCode?.let { Setpoint.of(it) }
        val footpad: Footpad? get() = footpadCode?.let { Footpad.of(it) }
        val pushback: Boolean get() = setpointAdjust?.pushback == true
    }

    sealed interface Reply
    data class InfoReply(val info: Info) : Reply
    data class RtReply(val data: RtData) : Reply

    /** Parses a COMM_CUSTOM_APP_DATA payload (starting with byte 36). Null if it isn't a Float reply we understand. */
    fun parse(payload: ByteArray): Reply? {
        if (payload.size < 3 || (payload[0].toInt() and 0xFF) != VescProtocol.COMM_CUSTOM_APP_DATA) return null
        if ((payload[1].toInt() and 0xFF) != MAGIC) return null
        return when (payload[2].toInt() and 0xFF) {
            CMD_GET_INFO -> parseInfo(payload)?.let { InfoReply(it) }
            CMD_GET_RTDATA -> parseRtData(payload)?.let { RtReply(it) }
            else -> null
        }
    }

    private fun parseInfo(p: ByteArray): Info? {
        if (p.size < 4) return null
        val v = p[3].toInt() and 0xFF
        return Info(v / 10, v % 10, if (p.size >= 5) p[4].toInt() and 0xFF else null)
    }

    private fun angle(x: Double?) = x?.takeIf { it in -180.0..180.0 }
    private fun volts(x: Double?) = x?.takeIf { it in -1.0..20.0 }
    private fun tilt(x: Double?) = x?.takeIf { it in -90.0..90.0 }
    private fun amps(x: Double?) = x?.takeIf { it in -1000.0..1000.0 }

    /**
     * Float 1.x real-time data after `36, 101, 1`: pid_value, pitch, roll (float32_auto); state byte (state | setpoint
     * adjustment type << 4); switch byte (footpad state | beep reason << 4); adc1, adc2 (V); float_setpoint, atr,
     * brake tilt, torque tilt, turn tilt, input tilt (degrees); true pitch, ATR filtered current, acceleration
     * difference, booster current, motor current, throttle (all float32_auto). Fields past the end stay null; values
     * outside a physical range are dropped (a different layout then shows as missing, not as nonsense).
     */
    fun parseRtData(p: ByteArray): RtData? {
        val r = VescProtocol.Reader(p, 3)
        val pid = r.optFloatAuto()
        val pitch = angle(r.optFloatAuto())
        val roll = angle(r.optFloatAuto())
        val state = r.optU8()
        val sw = r.optU8()
        val adc1 = volts(r.optFloatAuto()); val adc2 = volts(r.optFloatAuto())
        val setpoint = tilt(r.optFloatAuto()); val atr = tilt(r.optFloatAuto()); val brake = tilt(r.optFloatAuto())
        val torque = tilt(r.optFloatAuto()); val turn = tilt(r.optFloatAuto()); val input = tilt(r.optFloatAuto())
        val truePitch = angle(r.optFloatAuto()); val atrI = amps(r.optFloatAuto()); val acc = r.optFloatAuto()
        val booster = amps(r.optFloatAuto()); val motor = amps(r.optFloatAuto()); val throttle = r.optFloatAuto()?.takeIf { it in -1.5..1.5 }
        if (pitch == null && roll == null && state == null) return null
        val stateCode = state?.and(0x0F)?.takeIf { State.of(it) != null }
        val spCode = state?.shr(4)?.takeIf { Setpoint.of(it) != null }
        return RtData(pid, pitch, roll, stateCode, spCode, sw?.and(0x0F)?.takeIf { Footpad.of(it) != null }, sw?.shr(4),
            adc1, adc2, setpoint, atr, brake, torque, turn, input, truePitch, atrI, acc, booster, motor, throttle)
    }
}
