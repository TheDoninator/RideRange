package com.elect.riderange.core

import com.elect.riderange.core.json.JSONObject

/**
 * Optional "is there a newer version?" check against a GitHub repository's latest release (the app is distributed
 * as GitHub release APK / IPA files). Only runs when the rider taps "Check now" with a repository set; nothing is sent
 * except the request itself.
 */
object UpdateCheck {
    data class Release(val tag: String, val name: String, val url: String, val apkUrl: String?)

    fun latestUrl(owner: String, repo: String) = "https://api.github.com/repos/$owner/$repo/releases/latest"

    /** [assetExt]: the download for this platform (".apk" on Android, ".ipa" on iPhone). */
    fun parse(json: String, assetExt: String = ".apk"): Release? = try {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets")
        val apk = assets?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(assetExt) } }
        Release(o.getString("tag_name"), o.optString("name"), o.optString("html_url"), apk?.optString("browser_download_url"))
    } catch (_: Exception) { null }

    /** "v1.2.0" / "1.2" / "RideRange-1.10.1" -> [1, 2, 0]. */
    fun numbers(v: String): List<Int> = Regex("""\d+(\.\d+)*""").find(v)?.value?.split('.')?.map { it.toInt() } ?: emptyList()

    fun isNewer(tag: String, current: String): Boolean {
        val a = numbers(tag); val b = numbers(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
