package com.elect.riderange.vehicle.vesc

/** A fake VESC that answers framed requests with framed bytes (debug builds only). */
interface SimulatedVescPort {
    fun write(frame: ByteArray)
    fun close()
}

/**
 * Hook for the debug-only simulated VESC (Android: `app/src/debug/.../debugsim/SimulatedVesc.kt`). The simulator class
 * is only compiled into debug builds and registers itself through [install] at start-up; [open] also checks [debug],
 * so a release build can never connect to it. Its bytes go through the same [VescProtocol.Decoder] and [VescSession]
 * as a real board's. iPhone builds have no simulator.
 */
object VescSimulator {
    const val FLOAT_BOARD = "SIM:VESC-FLOAT"
    const val DUAL_MOTOR = "SIM:VESC-DUAL"

    private var debug = false
    private var factory: ((dual: Boolean, onBytes: (ByteArray) -> Unit) -> SimulatedVescPort)? = null

    fun isSimAddress(address: String?) = address?.startsWith("SIM:") == true

    /** Called by debug builds only. */
    fun install(debugBuild: Boolean, create: (dual: Boolean, onBytes: (ByteArray) -> Unit) -> SimulatedVescPort) {
        debug = debugBuild
        if (debugBuild) factory = create
    }

    val available: Boolean get() = debug && factory != null

    /** Opens the simulator; [onBytes] receives its notifications (on a background thread). Null in release builds. */
    fun open(address: String, onBytes: (ByteArray) -> Unit): SimulatedVescPort? {
        if (!debug || !isSimAddress(address)) return null
        return try {
            factory?.invoke(address == DUAL_MOTOR, onBytes)
        } catch (_: Throwable) {
            null
        }
    }
}
