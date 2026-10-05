package com.elect.riderange.upload

import com.elect.riderange.core.Http
import com.elect.riderange.core.HttpResponse
import kotlinx.coroutines.test.runTest
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertFalse
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test
import java.io.IOException
import java.util.Base64
import kotlinx.datetime.TimeZone
import java.util.zip.GZIPInputStream

/** The GitHub uploader against a fake HTTP layer (no real token, nothing leaves the machine). */
class UploadTest {
    private val fakeToken = "github_pat_FAKE0000000000000000000000000000"

    private class FakeHttp(val replies: MutableList<HttpResponse>) : Http {
        val puts = ArrayList<Triple<String, String, Map<String, String>>>()
        var fail = false
        override suspend fun get(url: String, headers: Map<String, String>) = error("unused")
        override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) = error("unused")
        override suspend fun put(url: String, body: String, contentType: String, headers: Map<String, String>): HttpResponse {
            if (fail) throw IOException("Unable to resolve host api.github.com")
            puts += Triple(url, body, headers)
            return replies.removeAt(0)
        }
    }

    @Test
    fun paths() {
        val utc = TimeZone.UTC
        // 2026-10-04 15:30:05 UTC
        val ms = 1791127805000L
        assertEquals("trips/2026-10-04/153005-N4GTEST000001.json", UploadPaths.tripPath(ms, "N4GTEST000001", false, utc))
        assertEquals("trips/2026-10-04/153005-noscooter.json.gz", UploadPaths.tripPath(ms, null, true, utc))
        assertEquals("model/2026-10-04-model.json", UploadPaths.modelPath(ms, utc))
        assertEquals("example-owner" to "riderange-trips", UploadPaths.parseRepo("example-owner/riderange-trips"))
        assertEquals("a" to "b", UploadPaths.parseRepo("https://github.com/a/b.git"))
        assertNull(UploadPaths.parseRepo("not a repo"))
    }

    @Test
    fun uploadsTripWithAuthHeaderOnly() = runTest {
        val http = FakeHttp(mutableListOf(HttpResponse(201, "{\"content\":{}}")))
        val up = GitHubUploader(http, "example-owner", "riderange-trips", fakeToken)
        val r = up.uploadTrip(1791127805000L, "SER1", "{\"a\":1}", "x")
        assertTrue(r.ok)
        val (url, body, headers) = http.puts.single()
        assertTrue(url.startsWith("https://api.github.com/repos/example-owner/riderange-trips/contents/trips/"))
        assertEquals("Bearer $fakeToken", headers["Authorization"])
        assertFalse(body.contains(fakeToken))
        val content = JSONObject(body).getString("content")
        assertEquals("{\"a\":1}", String(Base64.getDecoder().decode(content)))
    }

    @Test
    fun existingFileGetsSuffix() = runTest {
        val http = FakeHttp(mutableListOf(HttpResponse(422, "{\"message\":\"sha wasn't supplied\"}"), HttpResponse(201, "{}")))
        val r = GitHubUploader(http, "o", "r", fakeToken).uploadTrip(1791127805000L, null, "{}", "abc")
        assertTrue(r.ok)
        assertTrue(r.path!!.endsWith("-noscooter-abc.json"))
        assertEquals(2, http.puts.size)
    }

    @Test
    fun errorsAreFriendlyAndRedacted() = runTest {
        val http = FakeHttp(mutableListOf(HttpResponse(401, "{\"message\":\"Bad credentials $fakeToken\"}")))
        val r = GitHubUploader(http, "o", "r", fakeToken).uploadTrip(0, null, "{}", "x")
        assertFalse(r.ok); assertFalse(r.retryable)
        assertTrue(r.message.startsWith("401"))
        assertFalse(r.message.contains(fakeToken))
        val h2 = FakeHttp(mutableListOf(HttpResponse(503, "oops")))
        assertTrue(GitHubUploader(h2, "o", "r", fakeToken).uploadTrip(0, null, "{}", "x").retryable)
        val h3 = FakeHttp(mutableListOf()).apply { fail = true }
        val r3 = GitHubUploader(h3, "o", "r", fakeToken).uploadTrip(0, null, "{}", "x")
        assertEquals(-1, r3.code); assertTrue(r3.retryable)
    }

    @Test
    fun noTokenMeansNoRequest() = runTest {
        val http = FakeHttp(mutableListOf())
        val r = GitHubUploader(http, "o", "r", null).uploadTrip(0, null, "{}", "x")
        assertTrue(r.needsToken)
        assertTrue(http.puts.isEmpty())
    }

    @Test
    fun bigTripsAreGzipped() {
        val big = "{\"x\":\"" + "a".repeat(1_200_000) + "\"}"
        val e = Payload.encode(big)
        assertTrue(e.gzip)
        assertTrue(e.bytes.size < 100_000)
        assertEquals(big, GZIPInputStream(e.bytes.inputStream()).bufferedReader().readText())
        assertFalse(Payload.encode("{}").gzip)
    }

    @Test
    fun redaction() {
        assertEquals("key [REDACTED] here", Redact.apply("key $fakeToken here"))
        assertEquals("Bearer [REDACTED]", Redact.apply("Bearer abcdefghijklmnop"))
        assertEquals("x [REDACTED] y", Redact.apply("x mysecret1 y", listOf("mysecret1")))
    }
}
