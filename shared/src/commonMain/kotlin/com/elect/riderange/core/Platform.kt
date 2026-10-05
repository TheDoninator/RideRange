package com.elect.riderange.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.elect.riderange.Services
import com.elect.riderange.data.RideDb
import com.elect.riderange.location.LocationSource
import com.elect.riderange.search.Region
import com.elect.riderange.vehicle.link.BlePlatform

/** Build facts the shared code needs (Android: BuildConfig; iOS: Info.plist). */
data class AppInfo(
    val versionName: String,
    val debug: Boolean,
    /** "Android" or "iOS". */
    val os: String,
    /** File extension of this platform's download on a GitHub release (".apk" / ".ipa"). */
    val releaseAsset: String,
)

/** Text to speech (TextToSpeech / AVSpeechSynthesizer). Utterances queue up; [stop] clears the queue. */
interface Speech {
    /** Starts the engine early so the first utterance isn't delayed. */
    fun warmUp() {}
    fun speak(text: String)
    fun stop()
}

/** Vibration with an Android-style waveform: off/on durations in ms, starting with "off". */
fun interface Haptics {
    fun vibrate(pattern: LongArray)
}

/** Keeps the GitHub token unreadable to other apps (Android Keystore AES-GCM / iOS Keychain). */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decryptOrNull(stored: String): String?
}

/** Small persistent integers (one-time data fixes). */
interface KeyValueFlags {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

/** Queues the GitHub trip upload (WorkManager on Android; an in-app retry loop on iOS). */
fun interface UploadScheduler {
    fun schedule(wifiOnly: Boolean)
}

/**
 * Keeps GPS, the vehicle link, trip logging and navigation running with the screen off while [needed]
 * (Android: the foreground service; iOS: background location updates).
 */
fun interface RideKeeper {
    fun update(needed: Boolean)
}

/** Hands a text file to the system share sheet (export GPX/CSV/JSON). */
fun interface Sharer {
    fun share(fileName: String, mime: String, content: String)
}

/** Platform reverse geocoder (Android Geocoder / CLGeocoder); null when it can't tell. */
fun interface Geocoder {
    suspend fun region(p: LatLon): Region?
}

/** Everything platform-specific the shared app needs, created once by the Android Application / iOS app delegate. */
interface Platform {
    val info: AppInfo
    val http: Http
    val dataStore: DataStore<Preferences>
    val db: RideDb
    val secrets: SecretCipher
    val flags: KeyValueFlags
    val location: LocationSource
    val ble: BlePlatform
    val haptics: Haptics
    val geocoder: Geocoder
    val uploads: UploadScheduler
    val keeper: RideKeeper
    val sharer: Sharer

    /** A separate speech channel (navigation and ride alerts each have one, like 1.x). */
    fun newSpeech(): Speech

    /** A bundled text asset ("regulations.json", "brouter/scooter-trails.brf"), or null. */
    fun readAsset(path: String): String?

    /** Called once after [Services] exist. */
    fun onStart(services: Services) {}
}
