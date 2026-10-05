package com.elect.riderange.core

import com.elect.riderange.search.Region
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLPlacemark
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** NSURLSession (gzip is decoded by the system). Network failures become [IOException], like HttpURLConnection's. */
class IosHttp(private val userAgent: String = Net.userAgent("iOS")) : Http {
    private suspend fun send(method: String, url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        suspendCancellableCoroutine { cont ->
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                cont.resumeWithException(IOException("Bad URL"))
                return@suspendCancellableCoroutine
            }
            val req = NSMutableURLRequest.requestWithURL(nsUrl)
            req.HTTPMethod = method
            req.timeoutInterval = 45.0
            req.setValue(userAgent, forHTTPHeaderField = "User-Agent")
            headers.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
            if (body != null) {
                req.setValue(contentType ?: "text/plain", forHTTPHeaderField = "Content-Type")
                req.HTTPBody = body.encodeToByteArray().toNSData()
            }
            val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
                if (error != null) {
                    cont.resumeWithException(IOException(error.localizedDescription))
                } else {
                    val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: -1
                    cont.resume(HttpResponse(code, data?.toByteArray()?.decodeToString() ?: ""))
                }
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }

    override suspend fun get(url: String, headers: Map<String, String>) = send("GET", url, null, null, headers)
    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>) =
        send("POST", url, body, contentType, headers)
    override suspend fun put(url: String, body: String, contentType: String, headers: Map<String, String>) =
        send("PUT", url, body, contentType, headers)
}

/**
 * The GitHub token lives in the iOS Keychain (this device only, readable after first unlock so queued uploads work);
 * the settings store only keeps a marker. If the Keychain refuses (unusual signing setups), the token is kept in the
 * app's own sandboxed settings instead, which no other app can read.
 */
object KeychainCipher : SecretCipher {
    private const val MARKER = "keychain:v1"
    private const val SANDBOX = "sandbox:"
    private const val SERVICE = "io.github.thedoninator.riderange"
    private const val ACCOUNT = "github-upload-token"

    override fun encrypt(plain: String): String = if (save(plain)) MARKER else SANDBOX + Text.base64(plain.encodeToByteArray())

    override fun decryptOrNull(stored: String): String? = when {
        stored == MARKER -> load()
        stored.startsWith(SANDBOX) -> try { Text.base64Decode(stored.removePrefix(SANDBOX)).decodeToString() } catch (_: Exception) { null }
        else -> null
    }

    private fun baseQuery(): platform.CoreFoundation.CFMutableDictionaryRef? {
        val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain(SERVICE as NSString))
        CFDictionaryAddValue(q, kSecAttrAccount, CFBridgingRetain(ACCOUNT as NSString))
        return q
    }

    private fun save(value: String): Boolean {
        val del = baseQuery()
        SecItemDelete(del)
        CFRelease(del)
        val add = baseQuery()
        val data: NSData = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        CFDictionaryAddValue(add, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(add, null)
        CFRelease(add)
        return status == errSecSuccess
    }

    private fun load(): String? = memScoped {
        val q = baseQuery()
        CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(q, result.ptr)
        CFRelease(q)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }
}

class IosFlags : KeyValueFlags {
    private val d = NSUserDefaults.standardUserDefaults
    override fun getInt(key: String, default: Int): Int =
        if (d.objectForKey(key) == null) default else d.integerForKey(key).toInt()
    override fun putInt(key: String, value: Int) = d.setInteger(value.toLong(), forKey = key)
}

/** AVSpeechSynthesizer in US English, mixed over (and ducking) music so prompts are heard while riding. */
class IosSpeech : Speech {
    private val synth by lazy { AVSpeechSynthesizer() }

    override fun warmUp() { synth }

    override fun speak(text: String) {
        try {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback,
                withOptions = AVAudioSessionCategoryOptionDuckOthers or AVAudioSessionCategoryOptionMixWithOthers, error = null)
            AVAudioSession.sharedInstance().setActive(true, error = null)
        } catch (_: Throwable) {
        }
        val u = AVSpeechUtterance.speechUtteranceWithString(text)
        u.voice = AVSpeechSynthesisVoice.voiceWithLanguage("en-US")
        synth.speakUtterance(u)
    }

    override fun stop() {
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}

/**
 * iPhones have no public API for custom vibration patterns from the vibration motor without Core Haptics; the
 * classic system vibration is played once per "on" step of the Android-style pattern instead.
 */
class IosHaptics(private val scope: CoroutineScope) : Haptics {
    override fun vibrate(pattern: LongArray) {
        scope.launch {
            var i = 0
            while (i < pattern.size) {
                delay(pattern[i])                       // off
                if (i + 1 < pattern.size) {
                    AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                    delay(maxOf(pattern[i + 1], 400))   // the system vibration lasts ~0.4 s
                }
                i += 2
            }
        }
    }
}

/** CLGeocoder: the state comes back as its postal code in the US ("UT"). */
class IosGeocoder : Geocoder {
    override suspend fun region(p: LatLon): Region? = suspendCancellableCoroutine { cont ->
        val g = CLGeocoder()
        g.reverseGeocodeLocation(CLLocation(latitude = p.lat, longitude = p.lon)) { marks, _ ->
            val pm = marks?.firstOrNull() as? CLPlacemark
            if (cont.isActive) cont.resume(pm?.administrativeArea?.let { Region(pm.ISOcountryCode, null, it, pm.locality ?: pm.subAdministrativeArea) })
        }
        cont.invokeOnCancellation { g.cancelGeocode() }
    }
}

/** Writes the file to the temp directory and opens the share sheet. */
class IosSharer : Sharer {
    override fun share(fileName: String, mime: String, content: String) {
        val path = NSTemporaryDirectory() + fileName
        (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        val url = NSURL.fileURLWithPath(path)
        val top = topViewController() ?: return
        val vc = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        vc.popoverPresentationController?.sourceView = top.view
        top.presentViewController(vc, animated = true, completion = null)
    }
}

@Suppress("DEPRECATION")
fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        ?: UIApplication.sharedApplication.windows.firstOrNull()?.let { (it as platform.UIKit.UIWindow).rootViewController }
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

/**
 * No WorkManager on iOS: uploads run in the app (retry with backoff while it is running) and again on the next start.
 * "Wi-Fi only" isn't enforced on iPhone.
 */
class IosUploads(private val scope: CoroutineScope, private val drain: suspend () -> Boolean) : UploadScheduler {
    private var job: Job? = null

    override fun schedule(wifiOnly: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 30_000L
            while (try { drain() } catch (_: Exception) { true }) {
                delay(backoff)
                backoff = minOf(backoff * 2, 30 * 60_000L)
            }
        }
    }
}
