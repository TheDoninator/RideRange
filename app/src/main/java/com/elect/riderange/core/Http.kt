package com.elect.riderange.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
    val USER_AGENT = "RideRange/${com.elect.riderange.BuildConfig.VERSION_NAME} (Android; com.elect.riderange)"
}

class UrlHttp(private val userAgent: String = Net.USER_AGENT) : Http {
    private suspend fun send(method: String, url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 45_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "text/plain")
                }
            }
            try {
                if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = (if (code in 200..299) c.inputStream else c.errorStream)
                val input = if (stream != null && c.contentEncoding == "gzip") java.util.zip.GZIPInputStream(stream) else stream
                val text = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                HttpResponse(code, text)
            } finally {
                c.disconnect()
            }
        }

    override suspend fun get(url: String, headers: Map<String, String>) = send("GET", url, null, null, headers)
    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) =
        send("POST", url, body, contentType, headers)
    override suspend fun put(url: String, body: String, contentType: String, headers: Map<String, String>) =
        send("PUT", url, body, contentType, headers)
}

/** Fair-use spacing between requests to one free server (e.g. Nominatim: max 1 per second). */
class RateLimiter(private val minIntervalMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = last + minIntervalMs - clock()
        if (wait > 0) delay(wait)
        try { block() } finally { last = clock() }
    }
}

class ServiceException(message: String) : IOException(message)
