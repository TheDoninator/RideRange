package com.elect.riderange.vehicle.vesc

import com.elect.riderange.scooter.Telemetry
import kotlin.math.abs
import kotlin.math.roundToLong

/** One motor controller's latest COMM_GET_VALUES. [local] = the one the phone talks to; others are read over CAN. */
data class VescController(val id: Int?, val values: VescProtocol.Values, val updatedMs: Long, val local: Boolean)

/** Where the battery % came from (best first). */
enum class BatterySource(val label: String) { BMS("VESC BMS"), CONTROLLER("controller"), VOLTAGE("pack voltage") }

/** Vehicle numbers used when the controller doesn't report speed / battery itself. */
data class VescVehicleParams(val polePairs: Int = 15, val wheelDiameterMm: Double = 283.0, val gearRatio: Double = 1.0, val cellsSeries: Int? = null)

/** Everything read from a VESC setup so far: controllers (local + CAN), setup values, BMS and Float package data. */
data class VescSnapshot(
    val fw: VescProtocol.FwVersion? = null,
    val localId: Int? = null,
    val local: VescController? = null,
    val can: Map<Int, VescController> = emptyMap(),
    /** CAN ids that answered COMM_PING_CAN (may include a BMS or a BLE/Express module that has no motor values). */
    val canIds: List<Int> = emptyList(),
    val canPinged: Boolean = false,
    val setup: VescProtocol.ValuesSetup? = null,
    val setupMs: Long = 0,
    val bms: VescProtocol.Bms? = null,
    val bmsMs: Long = 0,
    val floatInfo: FloatPackage.Info? = null,
    val floatRt: FloatPackage.RtData? = null,
    val floatMs: Long = 0,
) {
    /** Fresh controllers, the local one first. */
    fun controllers(now: Long): List<VescController> =
        listOfNotNull(local?.takeIf { now - it.updatedMs < STALE_MS }) + can.values.filter { now - it.updatedMs < STALE_MS }.sortedBy { it.id }

    fun freshSetup(now: Long) = setup?.takeIf { now - setupMs < STALE_MS }
    fun freshBms(now: Long) = bms?.takeIf { it.present && now - bmsMs < BMS_STALE_MS }
    fun freshFloat(now: Long) = floatRt?.takeIf { now - floatMs < STALE_MS }

    /**
     * Battery input current of the whole vehicle (positive = discharging). The local controller's setup values already
     * sum every VESC it hears on CAN (`num_vescs`); if it hears fewer than RideRange reads (CAN status messages off),
     * the per-controller COMM_GET_VALUES currents are summed instead.
     */
    fun totalInputCurrent(now: Long): Double? {
        val ctrls = controllers(now)
        val s = freshSetup(now)
        if (s != null && (s.numVescs ?: 1) >= ctrls.size.coerceAtLeast(1)) return s.currentInTotA
        if (ctrls.isEmpty()) return null
        return ctrls.sumOf { it.values.inputCurrentA }
    }

    /** True when the total comes from summing several controllers (shown in the UI). */
    fun summedOverCan(now: Long): Boolean {
        val ctrls = controllers(now)
        val s = freshSetup(now)
        return ctrls.size > 1 && !(s != null && (s.numVescs ?: 1) >= ctrls.size)
    }

    fun voltage(now: Long): Double? = controllers(now).firstOrNull()?.values?.inputVoltage ?: freshSetup(now)?.inputVoltage
        ?: freshBms(now)?.packV

    fun totalPowerW(now: Long): Double? {
        val v = voltage(now) ?: return null
        val i = totalInputCurrent(now) ?: return null
        return v * i
    }

    /** Highest |duty cycle| over the fresh controllers (0..1). */
    fun maxDuty(now: Long): Double? = controllers(now).maxOfOrNull { abs(it.values.dutyCycle) } ?: freshSetup(now)?.dutyCycle?.let { abs(it) }

    /** BMS state of charge, else the controller's battery level, else pack voltage ÷ cells. */
    fun battery(now: Long, cellsSeries: Int?): Pair<Int, BatterySource>? {
        freshBms(now)?.soc?.let { return (it * 100).roundToLong().toInt() to BatterySource.BMS }
        freshSetup(now)?.batteryLevel?.let { return (it * 100).roundToLong().toInt() to BatterySource.CONTROLLER }
        val v = voltage(now) ?: return null
        val n = cellsSeries?.takeIf { it > 0 } ?: return null
        return LiIon.percent(v / n) to BatterySource.VOLTAGE
    }

    /** Telemetry for range, trips, mass and the learned model: summed power, best battery %, the controller's speed. */
    fun toTelemetry(now: Long, p: VescVehicleParams): Telemetry? {
        val ctrls = controllers(now)
        val setup = freshSetup(now)
        if (ctrls.isEmpty() && setup == null) return null
        val local = ctrls.firstOrNull()?.values
        val speedMps = setup?.speedMps?.let { abs(it) }
            ?: local?.let { abs(VescProtocol.speedMps(it.erpm, p.polePairs, p.wheelDiameterMm, p.gearRatio)) }
        val odo = setup?.odometerM ?: local?.let { VescProtocol.distanceM(it.tachometerAbs, p.polePairs, p.wheelDiameterMm, p.gearRatio).toLong() }
        val bms = freshBms(now)
        return Telemetry(
            speedKmh = speedMps?.let { it * 3.6 },
            batteryPct = battery(now, p.cellsSeries)?.first,
            voltage = voltage(now),
            current = totalInputCurrent(now),
            scooterTempC = ctrls.maxOfOrNull { it.values.tempMosfetC } ?: setup?.tempMosfetC,
            batteryTempC = (bms?.tempMaxCellC ?: bms?.tempsC?.maxOrNull())?.let { (it).roundToLong().toInt() },
            odometerM = odo,
            updatedMs = now,
        )
    }

    companion object {
        const val STALE_MS = 3_000L
        const val BMS_STALE_MS = 10_000L
    }
}

/**
 * The read-only VESC conversation, without Bluetooth: which requests to send every 250 ms tick, and how replies update
 * the [VescSnapshot]. Request payloads are checked by [VescProtocol.isReadOnly] before they are framed and sent.
 *
 * Rates (BLE UART carries ~1-2 kB/s comfortably; a GET_VALUES reply is ~80 bytes):
 *  - local COMM_GET_VALUES 4 Hz (speed, power);
 *  - COMM_GET_VALUES_SETUP 1 Hz (battery level, summed currents, configured speed/odometer);
 *  - each CAN controller via COMM_FORWARD_CAN: 2 requests/s shared round-robin (dual motor: 1 Hz each);
 *  - Float package real-time data 2 Hz once the package has answered (probed every 5 s, then every 30 s);
 *  - COMM_BMS_GET_VALUES every 2 s while a BMS answers (every 30 s otherwise);
 *  - COMM_PING_CAN on connect and once more after 10 s if nothing answered.
 * Requests that keep going unanswered (an id that is a BMS or BLE module, old firmware) drop to a slow retry.
 */
class VescSession {
    var snapshot = VescSnapshot()
        private set
    private var tick = 0L
    private var canCursor = 0
    private val canMisses = HashMap<Int, Int>()
    private var setupMisses = 0
    private var bmsMisses = 0
    private var floatMisses = 0
    private var canStarted = false
    var lastReplyMs = 0L
        private set

    /** Requests sent right after connecting. */
    fun openingRequests(): List<ByteArray> = listOf(
        byteArrayOf(VescProtocol.COMM_FW_VERSION.toByte()),
        byteArrayOf(VescProtocol.COMM_GET_VALUES.toByte()),
        FloatPackage.infoRequest(),
        byteArrayOf(VescProtocol.COMM_PING_CAN.toByte()),
    )


    /** Requests for the next 250 ms tick. */
    fun nextRequests(): List<ByteArray> {
        val t = tick
        val out = ArrayList<ByteArray>(4)
        out += byteArrayOf(VescProtocol.COMM_GET_VALUES.toByte())
        val slot = (t % 4).toInt()
        if (slot == 1 && (setupMisses < 5 || t % 40 == 1L)) { out += byteArrayOf(VescProtocol.COMM_GET_VALUES_SETUP.toByte()); setupMisses++ }
        if (slot == 0 || slot == 2) nextCanId()?.let { id ->
            out += VescProtocol.forwardCanPayload(id, byteArrayOf(VescProtocol.COMM_GET_VALUES.toByte()))
            canMisses[id] = (canMisses[id] ?: 0) + 1
            canStarted = true
        }
        val floatKnown = snapshot.floatInfo != null || snapshot.floatRt != null
        if (floatKnown && (slot == 1 || slot == 3) || !floatKnown && t % 20 == 10L && (floatMisses < 3 || t % 120 == 10L)) {
            out += FloatPackage.rtDataRequest(); floatMisses++
        }
        if (t % 8 == 3L && (bmsMisses < 3 || t % 120 == 3L)) { out += byteArrayOf(VescProtocol.COMM_BMS_GET_VALUES.toByte()); bmsMisses++ }
        if (t == 40L && !snapshot.canPinged) out += byteArrayOf(VescProtocol.COMM_PING_CAN.toByte())
        tick++
        return out
    }

    /** Next CAN id to read, round-robin; ids that never answer are only retried every 10 s. Null until the local id is known. */
    private fun nextCanId(): Int? {
        val s = snapshot
        if (s.local == null || s.localId == null) return null       // old firmware without controller ids: CAN replies can't be told apart
        val ids = s.canIds.filter { it != s.localId }
        if (ids.isEmpty()) return null
        repeat(ids.size) {
            val id = ids[canCursor++ % ids.size]
            if ((canMisses[id] ?: 0) < 4 || tick % 40 == 0L) return id
        }
        return null
    }

    /** Feeds one decoded payload; returns true if the snapshot changed. */
    fun onPayload(p: ByteArray, now: Long): Boolean {
        if (p.isEmpty()) return false
        val s = snapshot
        val next: VescSnapshot = when (p[0].toInt() and 0xFF) {
            VescProtocol.COMM_GET_VALUES -> {
                val v = VescProtocol.parseValues(p) ?: return false
                val id = v.controllerId
                when {
                    s.local == null && !canStarted -> s.copy(local = VescController(id, v, now, true), localId = id)
                    id != null && id == s.localId -> s.copy(local = VescController(id, v, now, true))
                    id != null -> { canMisses[id] = 0; s.copy(can = s.can + (id to VescController(id, v, now, false))) }
                    s.localId == null -> s.copy(local = VescController(null, v, now, true))
                    else -> return false
                }
            }
            VescProtocol.COMM_GET_VALUES_SETUP -> {
                val v = VescProtocol.parseValuesSetup(p) ?: return false
                setupMisses = 0
                s.copy(setup = v, setupMs = now)
            }
            VescProtocol.COMM_BMS_GET_VALUES -> {
                val b = VescProtocol.parseBms(p) ?: return false
                if (b.present) bmsMisses = 0
                s.copy(bms = b, bmsMs = now)
            }
            VescProtocol.COMM_PING_CAN -> {
                val ids = VescProtocol.parsePingCan(p) ?: return false
                s.copy(canIds = ids.filter { it != s.localId }, canPinged = true)
            }
            VescProtocol.COMM_FW_VERSION -> s.copy(fw = VescProtocol.parseFwVersion(p) ?: return false)
            VescProtocol.COMM_CUSTOM_APP_DATA -> when (val r = FloatPackage.parse(p)) {
                is FloatPackage.InfoReply -> { floatMisses = 0; s.copy(floatInfo = r.info) }
                is FloatPackage.RtReply -> { floatMisses = 0; s.copy(floatRt = r.data, floatMs = now) }
                null -> return false
            }
            else -> return false
        }
        snapshot = next
        lastReplyMs = now
        return true
    }
}
