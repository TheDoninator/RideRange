package com.elect.riderange

import java.io.File

object Fixtures {
    fun read(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** A file from app/src/main (e.g. assets/regulations.json), found from the test working directory. */
    fun main(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "src/main/$path").exists() && !File(dir, "app/src/main/$path").exists()) dir = dir.parentFile
        requireNotNull(dir) { "src/main/$path not found" }
        val f = File(dir, "src/main/$path").takeIf { it.exists() } ?: File(dir, "app/src/main/$path")
        return f.readText(Charsets.UTF_8)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
