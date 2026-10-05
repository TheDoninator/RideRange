package com.elect.riderange.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.elect.riderange.core.LatLon
import com.elect.riderange.map.IosMapBridge
import com.elect.riderange.map.IosMapSurface
import com.elect.riderange.map.MapSurface
import com.elect.riderange.map.NativeMapListener
import com.elect.riderange.map.NativeMapView
import platform.UIKit.UIApplication

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun MapHost(
    modifier: Modifier,
    start: LatLon,
    styleUrl: String,
    onReady: (MapSurface) -> Unit,
    onLongPress: (LatLon) -> Unit,
    onParkingTap: (String) -> Unit,
    onCameraIdle: () -> Unit,
    onUserGesture: () -> Unit,
) {
    val longPress = rememberUpdatedState(onLongPress)
    val tap = rememberUpdatedState(onParkingTap)
    val idle = rememberUpdatedState(onCameraIdle)
    val gesture = rememberUpdatedState(onUserGesture)
    val ready = rememberUpdatedState(onReady)

    val map: NativeMapView = remember {
        val factory = requireNotNull(IosMapBridge.factory) { "MainViewController sets the map factory" }
        lateinit var view: NativeMapView
        view = factory.create(styleUrl, start.lat, start.lon, 12.5, object : NativeMapListener {
            override fun onReady() = ready.value(IosMapSurface(view))
            override fun onLongPress(lat: Double, lon: Double) = longPress.value(LatLon(lat, lon))
            override fun onParkingTap(parkingId: String) = tap.value(parkingId)
            override fun onCameraIdle() = idle.value()
            override fun onUserGesture() = gesture.value()
        })
        view
    }

    UIKitView(
        factory = { map.view() },
        modifier = modifier,
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = false),
    )
}

@Composable
actual fun KeepScreenOn(on: Boolean) {
    DisposableEffect(on) {
        UIApplication.sharedApplication.idleTimerDisabled = on
        onDispose { if (on) UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}
