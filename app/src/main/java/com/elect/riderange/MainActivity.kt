package com.elect.riderange

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.elect.riderange.ui.MainViewModel
import com.elect.riderange.ui.RideRangeRoot
import com.elect.riderange.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        App.services.location.start()
        if (Build.VERSION.SDK_INT >= 33 && App.services.settingsState.value.onboarded) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) App.services.scooter.scan()
    }

    private var askedLocation = false

    /** Asked from onboarding, or at start once onboarding is done (upgraded installs). */
    fun requestLocation() {
        if (App.services.location.hasPermission()) { App.services.location.start(); return }
        askedLocation = true
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun requestLocationOnce() { if (!askedLocation) requestLocation() }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestBluetooth() {
        if (App.services.scooter.bluetoothPermitted()) { App.services.scooter.scan(); return }
        askBluetooth.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (App.services.location.hasPermission()) App.services.location.start()
        setContent { AppTheme { RideRangeRoot(vm, ::requestBluetooth, ::requestLocation, ::requestNotifications, ::requestLocationOnce) } }
    }

    override fun onStart() {
        super.onStart()
        App.services.location.start()
    }
}
