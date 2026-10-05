package com.elect.riderange.core

import kotlin.io.encoding.Base64

/** java.io.IOException on Android (so platform network errors are caught as before), a plain exception on iOS. */
expect open class IOException(message: String?) : Exception

/** A re-entrant lock (ReentrantLock on Android, NSRecursiveLock on iOS). */
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** android.util.Log / NSLog. */
expect object Log {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
}

/** Cryptographically secure random bytes (SecureRandom / arc4random_buf). */
expect fun secureRandomBytes(n: Int): ByteArray

/** GZIP (RFC 1952) compression of [data]. */
expect fun gzip(data: ByteArray): ByteArray

object Text {
    /** application/x-www-form-urlencoded, exactly like java.net.URLEncoder.encode(s, "UTF-8"). */
    fun urlEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF)
            val ch = c.toChar()
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> sb.append(ch)
                ch == ' ' -> sb.append('+')
                else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    fun base64(bytes: ByteArray): String = Base64.Default.encode(bytes)
    fun base64Decode(s: String): ByteArray = Base64.Default.decode(s)

    /** Bytes as ISO-8859-1 / ASCII characters (no UTF-8 decoding), like String(bytes, US_ASCII) for 7-bit data. */
    fun latin1(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String =
        CharArray(to - from) { (bytes[from + it].toInt() and 0xFF).toChar() }.concatToString()

    fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }
}
