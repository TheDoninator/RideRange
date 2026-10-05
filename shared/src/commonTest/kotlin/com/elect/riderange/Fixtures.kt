package com.elect.riderange

import com.elect.riderange.core.format

/** Reads a file from disk (JVM: java.io; iOS simulator: Foundation, which sees the host's checkout). */
expect fun readTextFile(path: String): String

object Fixtures {
    /** A file under shared/src/commonTest/resources (recorded server replies, NbCrypto vectors). */
    fun read(name: String): String = readTextFile(TestPaths.RESOURCES + "/" + name)

    /**
     * A file from the app: "assets/regulations.json" (app/src/main/assets) or, as in 1.x, "java/com/elect/riderange/..."
     * for shared source files (now shared/src/commonMain/kotlin).
     */
    fun main(path: String): String =
        if (path.startsWith("java/")) readTextFile(TestPaths.SOURCES + "/" + path.removePrefix("java/"))
        else readTextFile(TestPaths.ASSETS + "/" + path.removePrefix("assets/"))
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
