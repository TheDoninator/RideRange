package com.elect.riderange

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.elect.riderange.core.APP_VERSION_NAME
import com.elect.riderange.core.AppInfo
import com.elect.riderange.core.IosFlags
import com.elect.riderange.core.IosGeocoder
import com.elect.riderange.core.IosHaptics
import com.elect.riderange.core.IosHttp
import com.elect.riderange.core.IosSharer
import com.elect.riderange.core.IosSpeech
import com.elect.riderange.core.IosUploads
import com.elect.riderange.core.KeychainCipher
import com.elect.riderange.core.Platform
import com.elect.riderange.core.RideKeeper
import com.elect.riderange.core.Speech
import com.elect.riderange.data.RideDb
import com.elect.riderange.data.SettingsStore
import com.elect.riderange.location.IosLocationSource
import com.elect.riderange.scooter.ble.IosUartLink
import com.elect.riderange.scooter.ble.UartLink
import com.elect.riderange.scooter.ble.bluetoothDenied
import com.elect.riderange.upload.TripUploads
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.link.BlePlatform
import com.elect.riderange.vehicle.link.IosFmManager
import com.elect.riderange.vehicle.link.VehicleLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile

private fun documentsDir(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, true, null) as NSURL?
    return requireNotNull(url?.path) { "no Documents directory" }
}

class IosBle : BlePlatform {
    override fun permitted(): Boolean = !bluetoothDenied()
    override fun permissionMessage(what: String) = "Allow Bluetooth for RideRange (Settings > RideRange) so the app can reach the $what."
    override fun uartLink(listener: UartLink.Listener): UartLink = IosUartLink(listener)
    override fun onewheel(settings: SettingsStore, scope: CoroutineScope, vehicle: StateFlow<Vehicle?>): VehicleLink =
        IosFmManager(settings, scope, vehicle)
}

/** The iPhone side of [Platform]: Documents/ holds the database and settings; assets are bundled from the Android app. */
class IosPlatform : Platform {
    private val scope = MainScope()
    private val bundleVersion = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    override val info = AppInfo(bundleVersion ?: APP_VERSION_NAME, kotlin.native.Platform.isDebugBinary, "iOS", ".ipa")
    override val http = IosHttp()
    override val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(produceFile = { (documentsDir() + "/riderange.preferences_pb").toPath() })
    override val db: RideDb = Room.databaseBuilder<RideDb>(name = documentsDir() + "/" + RideDb.FILE_NAME)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    override val secrets = KeychainCipher
    override val flags = IosFlags()
    override val location = IosLocationSource()
    override val ble = IosBle()
    override val haptics = IosHaptics(scope)
    override val geocoder = IosGeocoder()
    override val uploads = IosUploads(scope) { TripUploads.drain() }
    override val keeper = RideKeeper { needed -> location.setBackground(needed) }
    override val sharer = IosSharer()

    override fun newSpeech(): Speech = IosSpeech()

    override fun readAsset(path: String): String? {
        val file = NSBundle.mainBundle.resourcePath + "/assets/" + path
        return NSString.stringWithContentsOfFile(file, NSUTF8StringEncoding, null)
    }

    override fun onStart(services: Services) {
        if (location.hasPermission()) location.start()
        // Uploads left from an earlier run (no background scheduler on iOS).
        uploads.schedule(false)
    }
}
