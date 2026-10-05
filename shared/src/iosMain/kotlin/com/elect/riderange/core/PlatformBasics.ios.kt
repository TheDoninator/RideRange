package com.elect.riderange.core

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSLog
import platform.Foundation.NSRecursiveLock
import platform.Foundation.create
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.memcpy
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.z_stream
import kotlin.random.Random

actual open class IOException actual constructor(message: String?) : Exception(message)

actual class PlatformLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

actual object Log {
    actual fun d(tag: String, msg: String) = NSLog("%@", "$tag: $msg")
    actual fun i(tag: String, msg: String) = NSLog("%@", "$tag: $msg")
    actual fun w(tag: String, msg: String) = NSLog("%@", "$tag: $msg")
}

actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val ok = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) } == 0
    // SecRandomCopyBytes doesn't fail in practice; never hand out zeros if it ever does.
    if (!ok) Random.nextBytes(out)
    return out
}

/** zlib deflate with a gzip header (windowBits 15 + 16). */
actual fun gzip(data: ByteArray): ByteArray = memScoped {
    val strm = alloc<z_stream>()
    if (deflateInit2(strm.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY) != Z_OK) throw IOException("gzip init failed")
    try {
        val bound = deflateBound(strm.ptr, data.size.convert()).toInt() + 32
        val out = ByteArray(bound)
        data.usePinned { inp ->
            out.usePinned { o ->
                strm.next_in = if (data.isEmpty()) null else inp.addressOf(0).reinterpret<UByteVar>()
                strm.avail_in = data.size.convert()
                strm.next_out = o.addressOf(0).reinterpret()
                strm.avail_out = bound.convert()
                if (deflate(strm.ptr, Z_FINISH) != Z_STREAM_END) throw IOException("gzip failed")
            }
        }
        out.copyOf(strm.total_out.toInt())
    } finally {
        deflateEnd(strm.ptr)
    }
}

fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.convert())
}

fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
