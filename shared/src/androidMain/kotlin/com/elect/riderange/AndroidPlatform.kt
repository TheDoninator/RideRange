package com.elect.riderange

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elect.riderange.core.AppInfo
import com.elect.riderange.core.Haptics
import com.elect.riderange.core.KeyValueFlags
import com.elect.riderange.core.Platform
import com.elect.riderange.core.RideKeeper
import com.elect.riderange.core.Sharer
import com.elect.riderange.core.Speech
import com.elect.riderange.core.UploadScheduler
import com.elect.riderange.core.UrlHttp
import com.elect.riderange.data.DbMigrations
import com.elect.riderange.data.KeystoreCipher
import com.elect.riderange.data.RideDb
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.location.AndroidLocationSource
import com.elect.riderange.rules.AndroidGeocoder
import com.elect.riderange.scooter.ble.ScooterLink
import com.elect.riderange.scooter.ble.UartLink
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.link.BlePlatform
import com.elect.riderange.vehicle.link.FmManager
import com.elect.riderange.vehicle.link.VehicleLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.MapLibre
import java.io.File
import java.util.Locale

/** Same DataStore file as 1.x ("riderange"), so settings, garage and keys carry over. */
private val Context.store: DataStore<Preferences> by preferencesDataStore("riderange")

/** The 1.x Room database file and its 1 -> 2 migration (Android framework SQLite, as before). */
object RideDbs {
    /** 1.1: trips belong to a garage vehicle. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(DbMigrations.ADD_VEHICLE_ID)
        }
    }

    @Volatile private var instance: RideDb? = null

    fun get(context: Context): RideDb = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, RideDb::class.java, RideDb.FILE_NAME)
            .addMigrations(MIGRATION_1_2).build().also { instance = it }
    }
}

fun androidBluetoothPermitted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 31) {
    listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
} else ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

class AndroidBle(private val context: Context) : BlePlatform {
    override fun permitted(): Boolean = androidBluetoothPermitted(context)
    override fun permissionMessage(what: String) = "Allow \"Nearby devices\" so the app can reach the $what."
    override fun uartLink(listener: UartLink.Listener): UartLink = ScooterLink(context, listener)
    override fun onewheel(settings: SettingsStore, scope: CoroutineScope, vehicle: StateFlow<Vehicle?>): VehicleLink =
        FmManager(context, settings, scope, vehicle)
}

/** TextToSpeech in US English; utterances asked for before the engine is ready are spoken once it is. */
class AndroidSpeech(private val context: Context) : Speech {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<String>()

    override fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                synchronized(pending) { pending.toList().also { pending.clear() } }.forEach { say(it) }
            }
        }
    }

    private fun say(text: String) { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString()) }

    override fun speak(text: String) {
        if (ready) say(text) else { synchronized(pending) { pending += text }; warmUp() }
    }

    override fun stop() {
        synchronized(pending) { pending.clear() }
        tts?.stop()
    }
}

/**
 * The Android side of [Platform]. [startRideService] starts the foreground RideService (which stops itself when
 * nothing needs it); [scheduleUpload] enqueues the WorkManager upload. Both live in the app module.
 */
class AndroidPlatform(
    private val context: Context,
    override val info: AppInfo,
    private val startRideService: () -> Unit,
    private val scheduleUpload: (wifiOnly: Boolean) -> Unit,
) : Platform {
    override val http = UrlHttp()
    override val dataStore: DataStore<Preferences> = context.store
    override val db: RideDb = RideDbs.get(context)
    override val secrets = KeystoreCipher
    override val flags = object : KeyValueFlags {
        private val prefs = context.getSharedPreferences("trip_migrations", Context.MODE_PRIVATE)
        override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
        override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    }
    override val location = AndroidLocationSource(context)
    override val ble = AndroidBle(context)
    override val haptics = Haptics { pattern ->
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        if (v?.hasVibrator() == true) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
    override val geocoder = AndroidGeocoder(context)
    override val uploads = UploadScheduler { scheduleUpload(it) }
    override val keeper = RideKeeper { needed -> if (needed) startRideService() }
    override val sharer = Sharer { name, mime, text ->
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export trip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun newSpeech(): Speech = AndroidSpeech(context)

    override fun readAsset(path: String): String? =
        try { context.assets.open(path).bufferedReader().use { it.readText() } } catch (_: Exception) { null }

    override fun onStart(services: Services) {
        MapLibre.getInstance(context)
        watchConnectivity()
    }

    /**
     * MapLibre's own connectivity check can report "offline" when a VPN without an underlying network is the
     * default (e.g. a local-only VPN), and then never loads tiles. Tell it about any validated internet network.
     */
    private fun watchConnectivity() {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val valid = java.util.concurrent.ConcurrentHashMap.newKeySet<android.net.Network>()
        val req = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        try {
            cm.registerNetworkCallback(req, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { valid += network; MapLibre.setConnected(true) }
                override fun onLost(network: android.net.Network) { valid -= network; MapLibre.setConnected(valid.isNotEmpty()) }
            })
        } catch (_: Exception) {
        }
    }
}
