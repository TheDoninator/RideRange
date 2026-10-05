package com.elect.riderange.upload

import com.elect.riderange.core.Http
import com.elect.riderange.core.json.JSONObject
import com.elect.riderange.core.DateFmt
import com.elect.riderange.core.IOException
import com.elect.riderange.core.Text
import com.elect.riderange.core.gzip
import kotlinx.datetime.TimeZone

// Adapted (copied, not linked) from ninebot-bridge's upload/UploadCore.kt + Sinks.kt.

/** Paths in the trips repo: trips/{yyyy-MM-dd}/{HHmmss}-{serial|noscooter}.json and model/{date}-model.json */
object UploadPaths {
    private fun clean(s: String?, fallback: String): String {
        val c = (s ?: "").replace(Regex("[^A-Za-z0-9_-]"), "")
        return c.ifEmpty { fallback }.take(40)
    }

    private fun fmt(p: String, ms: Long, tz: TimeZone) = DateFmt.format(ms, p, tz)

    fun tripPath(startMs: Long, serial: String?, gzip: Boolean, tz: TimeZone = TimeZone.currentSystemDefault()): String =
        "trips/${fmt("yyyy-MM-dd", startMs, tz)}/${fmt("HHmmss", startMs, tz)}-${clean(serial, "noscooter")}.json" + if (gzip) ".gz" else ""

    fun modelPath(nowMs: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String = "model/${fmt("yyyy-MM-dd", nowMs, tz)}-model.json"

    /** Owner/repo as typed: "owner/riderange-trips" (also accepts a github.com URL). */
    fun parseRepo(text: String): Pair<String, String>? {
        val t = text.trim().removePrefix("https://").removePrefix("http://").removePrefix("github.com/")
            .removeSuffix("/").removeSuffix(".git")
        val parts = t.split('/')
        if (parts.size != 2) return null
        val ok = Regex("^[A-Za-z0-9_.-]+$")
        return if (parts.all { ok.matches(it) }) parts[0] to parts[1] else null
    }
}

/** The bytes that go to GitHub: JSON, gzipped when it is over ~1 MB. */
object Payload {
    const val GZIP_OVER_BYTES = 900 * 1024

    class Encoded(val bytes: ByteArray, val gzip: Boolean) {
        val base64: String get() = Text.base64(bytes)
    }

    fun encode(json: String, gzipOver: Int = GZIP_OVER_BYTES): Encoded {
        val raw = json.encodeToByteArray()
        if (raw.size <= gzipOver) return Encoded(raw, false)
        return Encoded(gzip(raw), true)
    }
}

/** Keeps tokens out of logs and error text. */
object Redact {
    private val TOKEN_PATTERNS = listOf(
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("(?i)(Bearer|token)\\s+[A-Za-z0-9_.\\-]{12,}"),
    )

    fun apply(text: String, secrets: Collection<String?> = emptyList()): String {
        var t = text
        for (s in secrets) if (s != null && s.length >= 6) t = t.replace(s, "[REDACTED]")
        for (p in TOKEN_PATTERNS) t = p.replace(t) { m ->
            val v = m.value
            if (v.startsWith("Bearer", true) || v.startsWith("token", true)) v.substringBefore(' ') + " [REDACTED]" else "[REDACTED]"
        }
        return t
    }
}

object GitHubMessages {
    fun describe(code: Int, githubMessage: String?, path: String?): String = when (code) {
        200, 201 -> "Uploaded ${path ?: ""}".trim()
        401 -> "401: token is wrong or expired"
        403 -> if (githubMessage?.contains("rate limit", true) == true) "403: GitHub rate limit reached; will retry"
        else "403: token can't write here (needs Contents: Read and write on this repo)"
        404 -> "404: token can't see this repo (pick it when creating the token)"
        409 -> "409: repo busy; will retry"
        422 -> "422: GitHub refused the file${githubMessage?.let { " ($it)" } ?: ""}"
        in 500..599 -> "$code: GitHub trouble; will retry"
        -1 -> "No connection; will retry"
        else -> "$code: ${githubMessage ?: "unexpected reply"}"
    }

    fun retryable(code: Int, githubMessage: String?): Boolean =
        code == -1 || code == 408 || code == 409 || code == 429 || code >= 500 ||
            (code == 403 && githubMessage?.contains("rate limit", true) == true)
}

data class UploadResult(val ok: Boolean, val code: Int, val message: String, val path: String?, val retryable: Boolean, val needsToken: Boolean = false)

/**
 * GitHub Contents API: PUT {api}/repos/{owner}/{repo}/contents/{path} with {"message","content": base64}.
 * The token only goes in the Authorization header and is redacted from every message.
 */
class GitHubUploader(
    private val http: Http,
    private val owner: String,
    private val repo: String,
    private val token: String?,
    private val apiBase: String = "https://api.github.com",
) {
    class Put(val code: Int, val githubMessage: String?)

    suspend fun put(path: String, message: String, encoded: Payload.Encoded): Put {
        val url = apiBase.trimEnd('/') + "/repos/$owner/$repo/contents/" + path
        val body = JSONObject().put("message", message).put("content", encoded.base64).toString()
        return try {
            val r = http.put(url, body, "application/json", mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            ))
            val msg = try { JSONObject(r.body).optString("message").ifBlank { null } } catch (_: Exception) { null }
            Put(r.code, msg?.let { Redact.apply(it, listOf(token)) })
        } catch (e: IOException) {
            Put(-1, Redact.apply(e.message ?: e::class.simpleName ?: "error", listOf(token)))
        }
    }

    suspend fun uploadTrip(startMs: Long, serial: String?, json: String, uniqueSuffix: String): UploadResult {
        if (token.isNullOrBlank()) return UploadResult(false, 0, "Add a GitHub token in Settings to upload", null, false, needsToken = true)
        val enc = Payload.encode(json)
        var path = UploadPaths.tripPath(startMs, serial, enc.gzip)
        var r = put(path, "trip ${serial ?: "noscooter"}", enc)
        if (r.code == 422) {
            // Same-second file already there (e.g. re-upload): add a suffix once.
            path = path.replace(Regex("(\\.json(\\.gz)?)$"), "-$uniqueSuffix$1")
            r = put(path, "trip ${serial ?: "noscooter"}", enc)
        }
        return result(r, path)
    }

    suspend fun uploadModel(nowMs: Long, json: String): UploadResult {
        if (token.isNullOrBlank()) return UploadResult(false, 0, "No token", null, false, needsToken = true)
        var path = UploadPaths.modelPath(nowMs)
        var r = put(path, "model snapshot", Payload.encode(json))
        if (r.code == 422) {
            path = path.removeSuffix(".json") + "-" + (nowMs / 1000 % 100000) + ".json"
            r = put(path, "model snapshot", Payload.encode(json))
        }
        return result(r, path)
    }

    private fun result(r: Put, path: String): UploadResult {
        val ok = r.code == 200 || r.code == 201
        return UploadResult(ok, r.code, GitHubMessages.describe(r.code, r.githubMessage, path), if (ok) path else null,
            retryable = !ok && GitHubMessages.retryable(r.code, r.githubMessage))
    }
}
