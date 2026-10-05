package com.elect.riderange.scooter

import com.elect.riderange.scooter.protocol.Nb
import com.elect.riderange.scooter.protocol.NbTimeout
import com.elect.riderange.scooter.protocol.RegisterIo
import com.elect.riderange.scooter.protocol.s16
import com.elect.riderange.scooter.protocol.u16
import com.elect.riderange.scooter.protocol.u32
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Live values read from the scooter (registers confirmed on the user's Max G2, see ninebot-bridge RegisterCatalog). */
data class Telemetry(
    val speedKmh: Double? = null,
    val batteryPct: Int? = null,
    val voltage: Double? = null,
    val current: Double? = null,
    val scooterTempC: Double? = null,
    val batteryTempC: Int? = null,
    val odometerM: Long? = null,
    val updatedMs: Long = 0,
) {
    val speedMps: Double? get() = speedKmh?.let { it / 3.6 }
    val powerW: Double? get() = if (voltage != null && current != null) voltage * current else null
}

/** Register decoding (pure, unit-tested). */
object Regs {
    const val SPEED = 0x26          // ctrl, s16, 0.1 km/h
    const val ODO = 0x29            // ctrl, u32, metres
    const val CTRL_TEMP = 0x3E      // ctrl, u16, 0.1 °C
    const val BMS_PCT = 0x32        // bms, u16, %
    const val BMS_CURRENT = 0x33    // bms, s16, 0.01 A (positive = discharge)
    const val BMS_VOLTAGE = 0x34    // bms, u16, 0.01 V
    const val BMS_TEMPS = 0x35      // bms, 2 bytes, °C + 20

    fun speedKmh(b: ByteArray): Double? = if (b.size >= 2 && b.u16() != 0xFFFF) b.s16() / 10.0 else null
    fun odometer(b: ByteArray): Long? = if (b.size >= 4) b.u32() else null
    fun ctrlTemp(b: ByteArray): Double? = if (b.size >= 2 && b.u16() != 0xFFFF) b.u16() / 10.0 else null
    fun pct(b: ByteArray): Int? = if (b.size >= 2 && b.u16() in 0..100) b.u16() else null
    /** Reads 0x33..0x34 in one go: current then voltage. */
    fun currentVoltage(b: ByteArray): Pair<Double, Double>? = if (b.size >= 4) (b.s16(0) / 100.0) to (b.u16(2) / 100.0) else null
    fun batteryTemp(b: ByteArray): Int? = if (b.isNotEmpty()) (b[0].toInt() and 0xFF) - 20 else null
}

/**
 * Read-only polling loop: speed ~4 Hz, voltage+current 1 Hz (for power), battery %, temperatures and
 * odometer every 10 s. Never writes (the session itself is also opened read-only).
 */
class TelemetryPoller(
    private val io: RegisterIo,
    private val onUpdate: (Telemetry) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val speedPeriodMs: Long = 250,
) {
    var current = Telemetry()
        private set
    var failures = 0
        private set

    private suspend fun <T> tryRead(block: suspend () -> T): T? = try {
        block().also { failures = 0 }
    } catch (e: NbTimeout) {
        failures++
        null
    }

    suspend fun pollSlow() {
        val pct = tryRead { Regs.pct(io.read(Nb.BMS, Regs.BMS_PCT)) }
        val odo = tryRead { Regs.odometer(io.read(Nb.CTRL, Regs.ODO, 4)) }
        val ct = tryRead { Regs.ctrlTemp(io.read(Nb.CTRL, Regs.CTRL_TEMP)) }
        val bt = tryRead { Regs.batteryTemp(io.read(Nb.BMS, Regs.BMS_TEMPS)) }
        current = current.copy(
            batteryPct = pct ?: current.batteryPct, odometerM = odo ?: current.odometerM,
            scooterTempC = ct ?: current.scooterTempC, batteryTempC = bt ?: current.batteryTempC, updatedMs = clock(),
        )
        onUpdate(current)
    }

    suspend fun pollPower() {
        tryRead { Regs.currentVoltage(io.read(Nb.BMS, Regs.BMS_CURRENT, 4)) }?.let { (i, v) ->
            current = current.copy(current = i, voltage = v, updatedMs = clock())
            onUpdate(current)
        }
    }

    suspend fun pollSpeed() {
        tryRead { Regs.speedKmh(io.read(Nb.CTRL, Regs.SPEED)) }?.let {
            current = current.copy(speedKmh = it, updatedMs = clock())
            onUpdate(current)
        }
    }

    /** Runs until cancelled or until 8 reads in a row fail (link lost). */
    suspend fun run() {
        var tick = 0L
        pollSlow()
        while (coroutineContext.isActive && failures < 8) {
            pollSpeed()
            if (tick % 4 == 0L) pollPower()
            if (tick % 40 == 39L) pollSlow()
            tick++
            delay(speedPeriodMs)
        }
    }
}
