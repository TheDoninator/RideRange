package com.elect.riderange.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class UrlHttp(private val userAgent: String = Net.userAgent("Android")) : Http {
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

