package com.elect.riderange.vehicle.vesc

import com.elect.riderange.BuildConfig

/** A fake VESC that answers framed requests with framed bytes (debug builds only). */
interface SimulatedVescPort {
    fun write(frame: ByteArray)
    fun close()
}

/**
 * Hook for the debug-only simulated VESC (`src/debug/.../debugsim/SimulatedVesc.kt`). The simulator class is only
 * compiled into debug builds, and [open] also checks [BuildConfig.DEBUG], so a release build can never connect to it.
 * Its bytes go through the same [VescProtocol.Decoder] and [VescSession] as a real board's.
 */
object VescSimulator {
    const val FLOAT_BOARD = "SIM:VESC-FLOAT"
    const val DUAL_MOTOR = "SIM:VESC-DUAL"
    private const val CLASS = "com.elect.riderange.debugsim.SimulatedVesc"

    fun isSimAddress(address: String?) = address?.startsWith("SIM:") == true

    val available: Boolean by lazy { BuildConfig.DEBUG && runCatching { Class.forName(CLASS) }.isSuccess }

    /** Opens the simulator; [onBytes] receives its notifications (on a background thread). Null in release builds. */
    fun open(address: String, onBytes: (ByteArray) -> Unit): SimulatedVescPort? {
        if (!BuildConfig.DEBUG || !isSimAddress(address)) return null
        return try {
            Class.forName(CLASS).getConstructor(Boolean::class.javaPrimitiveType, Function1::class.java)
                .newInstance(address == DUAL_MOTOR, onBytes) as SimulatedVescPort
        } catch (_: Throwable) {
            null
        }
    }
}
