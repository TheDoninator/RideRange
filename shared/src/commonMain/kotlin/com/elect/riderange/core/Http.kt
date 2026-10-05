package com.elect.riderange.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HttpResponse(val code: Int, val body: String)

/** Minimal HTTP so services can be faked in unit tests. */
interface Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun put(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        throw UnsupportedOperationException("PUT")
}

object Net {
    /** Identifies the app to the free OSM services (Nominatim/Overpass usage policies require it). */
    fun userAgent(os: String): String =
        "RideRange/$APP_VERSION_NAME ($os; " + (if (os == "Android") "com.elect.riderange" else "io.github.thedoninator.riderange") + ")"
}

/** Fair-use spacing between requests to one free server (e.g. Nominatim: max 1 per second). */
class RateLimiter(private val minIntervalMs: Long, private val clock: () -> Long = ::currentTimeMillis) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = last + minIntervalMs - clock()
        if (wait > 0) delay(wait)
        try { block() } finally { last = clock() }
    }
}

class ServiceException(message: String) : IOException(message)
