package com.elect.riderange.debugsim

import android.util.Log
import com.elect.riderange.vehicle.vesc.FloatPackage
import com.elect.riderange.vehicle.vesc.SimulatedVescPort
import com.elect.riderange.vehicle.vesc.VescProtocol
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * DEBUG BUILDS ONLY (this file lives in src/debug and is not compiled into release builds).
 *
 * A pretend VESC setup for the emulator, which has no Bluetooth: it decodes the app's request frames, checks every
 * one against the read-only allow-list (anything else is logged and ignored), and answers with frames laid out like
 * the documented replies, split into 20-byte "notifications" so the app's real decoder reassembles them.
 *
 *  - [dual] = false: a Float board (one controller, CAN id 10, Float package 1.3, VESC BMS with 20 cells on CAN 20).
 *  - [dual] = true:  a dual-motor e-skateboard (controllers 10 and 11, VESC BMS with 12 cells on CAN 20, no Float).
 *
 * A 60 s ride loops: speed up, cruise, a climb with high duty (Float pushback from 30 to 45 s), a descent with regen.
 */
class SimulatedVesc(private val dual: Boolean, private val onBytes: (ByteArray) -> Unit) : SimulatedVescPort {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val decoder = VescProtocol.Decoder()
    private val startMs = System.currentTimeMillis()
    private var lastStepMs = startMs
    @Volatile private var closed = false
    var refused = 0
        private set

    // Ride state
    private var distM = 0.0
    private var whUsed = 0.0
    private var whRegen = 0.0
    private var ahUsed = 0.0
    private val cells = if (dual) 12 else 20
    private val packWh = if (dual) 710.0 else 576.0
    private val wheelMm = if (dual) 97.0 else 283.0
    private val polePairs = if (dual) 7 else 15
    private val gear = if (dual) 2.25 else 1.0
    private val socStart = 0.82

    private data class Now(val t: Double, val speed: Double, val duty: Double, val iPerMotor: Double, val volts: Double, val soc: Double, val pushback: Boolean)

    override fun write(frame: ByteArray) {
        if (closed) return
        exec.execute {
            for (p in decoder.feed(frame)) {
                if (!VescProtocol.isReadOnly(p)) {
                    refused++
                    Log.w("RideRange", "simulated VESC: refused non-read command ${p[0].toInt() and 0xFF}")
                    continue
                }
                reply(p)?.let { send(it) }
            }
        }
    }

    override fun close() {
        closed = true
        exec.shutdownNow()
    }

    private fun send(payload: ByteArray) {
        // One task per reply, in order (the executor is single-threaded and FIFO for equal delays), delivered as
        // 20-byte notifications like a BLE UART.
        val f = VescProtocol.frame(payload)
        exec.schedule({
            var i = 0
            while (i < f.size && !closed) {
                onBytes(f.copyOfRange(i, minOf(f.size, i + 20)))
                i += 20
            }
        }, 8, TimeUnit.MILLISECONDS)
    }

    private fun ocvPerCell(soc: Double) = 3.3 + 0.9 * soc

    private fun state(): Now {
        val nowMs = System.currentTimeMillis()
        val t = ((nowMs - startMs) / 1000.0) % 60.0
        val speed = when {
            t < 8 -> 7.0 * t / 8
            t < 30 -> 7.0 + 0.3 * sin(t)
            t < 45 -> 7.6
            t < 52 -> 7.2
            else -> 7.2 - (t - 52) / 8 * 4
        }
        val pushback = !dual && t in 30.0..45.0
        val climbing = t in 30.0..45.0
        val descending = t in 45.0..52.0
        val motors = if (dual) 2 else 1
        val totalI = when {
            t < 8 -> 16.0
            climbing -> 24.0
            descending -> -6.0
            else -> 9.0 + sin(t * 1.3)
        }
        val soc = (socStart - whUsed / packWh).coerceIn(0.05, 1.0)
        val volts = cells * ocvPerCell(soc) - totalI * 0.12
        val duty = when {
            climbing -> 0.88 + 0.02 * sin(t * 2)
            else -> (speed / 12.5).coerceIn(0.0, 0.8)
        }
        // integrate
        val dt = (nowMs - lastStepMs) / 1000.0
        lastStepMs = nowMs
        if (dt in 0.0..2.0) {
            distM += speed * dt
            val w = volts * totalI * dt / 3600
            if (w >= 0) whUsed += w else whRegen -= w
            ahUsed += max(0.0, totalI) * dt / 3600
        }
        return Now(t, speed, duty, totalI / motors, volts, soc, pushback)
    }

    private fun erpm(speed: Double) = speed / (PI * wheelMm / 1000) * 60 * polePairs * gear
    private fun tach() = (distM / (PI * wheelMm / 1000) * gear * 6 * polePairs).toInt()

    private fun reply(p: ByteArray): ByteArray? = when (p[0].toInt() and 0xFF) {
        VescProtocol.COMM_FW_VERSION -> fwVersion()
        VescProtocol.COMM_GET_VALUES -> values(10, state())
        VescProtocol.COMM_GET_VALUES_SETUP -> setup(state())
        VescProtocol.COMM_PING_CAN -> if (dual) byteArrayOf(62, 11, 20) else byteArrayOf(62, 20)
        VescProtocol.COMM_BMS_GET_VALUES -> bms(state())
        VescProtocol.COMM_FORWARD_CAN -> {
            val id = p[1].toInt() and 0xFF
            // Only the second motor controller answers forwarded GET_VALUES; the BMS (id 20) doesn't.
            if (dual && id == 11 && p.size == 3 && p[2] == VescProtocol.COMM_GET_VALUES.toByte()) values(11, state(), second = true) else null
        }
        VescProtocol.COMM_CUSTOM_APP_DATA -> if (dual) null else when (p[2].toInt() and 0xFF) {
            FloatPackage.CMD_GET_INFO -> byteArrayOf(36, 101, 0, 13, 1)
            FloatPackage.CMD_GET_RTDATA -> floatRt(state())
            else -> null
        }
        else -> null
    }

    private fun fwVersion(): ByteArray {
        val name = (if (dual) "75_300_R2" else "60_MK6").toByteArray()
        return byteArrayOf(0, 6, 5) + name + byteArrayOf(0) + ByteArray(12) { (it * 7 + 3).toByte() } + byteArrayOf(1, 0, 0)
    }

    private fun values(id: Int, n: Now, second: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(80)
        val fet = 34.0 + n.t / 10 + (if (second) 1.5 else 0.0)
        val mot = 41.0 + n.t / 6 + (if (second) -2.0 else 0.0)
        val i = n.iPerMotor * (if (second) 0.96 else 1.0)
        b.put(4).putShort((fet * 10).toInt().toShort()).putShort((mot * 10).toInt().toShort())
            .putInt((i * 1.6 * 100).toInt()).putInt((i * 100).toInt()).putInt(0).putInt((i * 1.6 * 100).toInt())
            .putShort((n.duty * 1000).toInt().toShort()).putInt(erpm(n.speed).toInt()).putShort((n.volts * 10).toInt().toShort())
            .putInt((ahUsed * 1e4).toInt()).putInt(0).putInt((whUsed * 1e4).toInt()).putInt((whRegen * 1e4).toInt())
            .putInt(tach()).putInt(tach()).put(0)
            .putInt(0).put(id.toByte())
        return b.array().copyOf(b.position())
    }

    private fun setup(n: Now): ByteArray {
        val motors = if (dual) 2 else 1
        val total = n.iPerMotor * motors
        val b = ByteBuffer.allocate(80)
        b.put(47).putShort((350 + n.t).toInt().toShort()).putShort((420 + n.t).toInt().toShort())
            .putInt((total * 1.6 * 100).toInt()).putInt((total * 100).toInt()).putShort((n.duty * 1000).toInt().toShort())
            .putInt(erpm(n.speed).toInt()).putInt((n.speed * 1000).toInt()).putShort((n.volts * 10).toInt().toShort())
            .putShort((n.soc * 1000).toInt().toShort())
            .putInt((ahUsed * 1e4).toInt()).putInt(0).putInt((whUsed * 1e4).toInt()).putInt((whRegen * 1e4).toInt())
            .putInt((distM * 1000).toInt()).putInt((distM * 1000).toInt()).putInt(0)
            .put(0).put(10).put(motors.toByte())
            .putInt((n.soc * packWh * 1000).toInt()).putInt((1_234_567 + distM).toInt()).putInt((System.currentTimeMillis() - startMs).toInt())
        return b.array().copyOf(b.position())
    }

    private fun bms(n: Now): ByteArray {
        val b = ByteBuffer.allocate(512)
        val total = n.iPerMotor * (if (dual) 2 else 1)
        val base = ocvPerCell(n.soc) - total * 0.12 / cells
        val cellV = List(cells) { base + ((it * 37 % 11) - 5) * 0.0016 }
        val mean = cellV.average()
        b.put(96).putInt((cellV.sum() * 1e6).toInt()).putInt(0).putInt((total * 1e6).toInt()).putInt((total * 1e6).toInt())
            .putInt((ahUsed * 1e3).toInt()).putInt((whUsed * 1e3).toInt())
            .put(cells.toByte())
        cellV.forEach { b.putShort((it * 1000).toInt().toShort()) }
        cellV.forEach { b.put(if (it > mean + 0.006) 1 else 0) }
        val temps = listOf(27.5, 28.25, 29.0, 27.0).map { it + n.t / 30 }
        b.put(temps.size.toByte())
        temps.forEach { b.putShort((it * 100).toInt().toShort()) }
        b.putShort(3100).putShort(2600).putShort(3800).putShort((temps.max() * 100).toInt().toShort())
            .putShort((n.soc * 1000).toInt().toShort()).putShort(960).put(20)
            .putFloat(41.5f).putFloat(1520f).putFloat(40.2f).putFloat(1480f)
        return b.array().copyOf(b.position())
    }

    private fun floatRt(n: Now): ByteArray {
        val b = ByteBuffer.allocate(128)
        val pitch = 1.2 * sin(n.t * 1.7) + (if (n.pushback) 3.0 else 0.0)
        val setpointType = if (n.pushback) FloatPackage.Setpoint.TILTBACK_DUTY.code else FloatPackage.Setpoint.NONE.code
        b.put(36).put(101).put(FloatPackage.CMD_GET_RTDATA.toByte())
            .putFloat((pitch * 4).toFloat()).putFloat(pitch.toFloat()).putFloat((2.5 * sin(n.t * 0.6)).toFloat())
            .put((FloatPackage.State.RUNNING.code or (setpointType shl 4)).toByte())
            .put(FloatPackage.Footpad.ON.code.toByte())
            .putFloat(2.91f).putFloat(2.86f)
            .putFloat(if (n.pushback) 3.0f else 0f).putFloat(0.4f).putFloat(0f).putFloat(0.8f).putFloat(0.2f).putFloat(0f)
            .putFloat((pitch + 0.1).toFloat()).putFloat(n.iPerMotor.toFloat()).putFloat(0.01f).putFloat(0f)
            .putFloat((n.iPerMotor * 1.6).toFloat()).putFloat(0f)
        return b.array().copyOf(b.position())
    }
}
