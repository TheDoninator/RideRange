package com.elect.riderange

actual fun readTextFile(path: String): String = java.io.File(path).readText(Charsets.UTF_8)
