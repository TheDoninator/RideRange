package com.elect.riderange.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

actual typealias IOException = java.io.IOException

actual class PlatformLock actual constructor() {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

actual object Log {
    actual fun d(tag: String, msg: String) { try { android.util.Log.d(tag, msg) } catch (_: RuntimeException) {} }
    actual fun i(tag: String, msg: String) { try { android.util.Log.i(tag, msg) } catch (_: RuntimeException) {} }
    actual fun w(tag: String, msg: String) { try { android.util.Log.w(tag, msg) } catch (_: RuntimeException) {} }
}

private val random by lazy { SecureRandom() }

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

actual fun gzip(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}
