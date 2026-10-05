package com.elect.riderange.ui

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.elect.riderange.core.LatLon
import com.elect.riderange.map.MapController
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** The one MapLibre map (kept alive across tabs). Reports long-presses, taps on parking pins and camera moves. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapHost(
    modifier: Modifier,
    start: LatLon,
    onReady: (MapController) -> Unit,
    onLongPress: (LatLon) -> Unit,
    onParkingTap: (String) -> Unit,
    onCameraIdle: () -> Unit,
    onUserGesture: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val longPress = rememberUpdatedState(onLongPress)
    val tap = rememberUpdatedState(onParkingTap)
    val idle = rememberUpdatedState(onCameraIdle)
    val gesture = rememberUpdatedState(onUserGesture)
    val ready = rememberUpdatedState(onReady)

    val mapView = remember {
        val opts = MapLibreMapOptions.createFromAttributes(context)
            .camera(CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(12.5).build())
            .attributionEnabled(true)
            .logoEnabled(false)
            .compassEnabled(true)
        MapView(context, opts).apply {
            onCreate(null)
            getMapAsync { map ->
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = false
                map.setStyle(Style.Builder().fromUri(com.elect.riderange.App.services.settingsState.value.urls.mapStyleUrl)) { style ->
                    val c = MapController(map, style)
                    map.addOnMapLongClickListener { p -> longPress.value(LatLon(p.latitude, p.longitude)); true }
                    map.addOnMapClickListener { p ->
                        val sp = map.projection.toScreenLocation(p)
                        val id = c.parkingAt(sp.x, sp.y)
                        if (id != null) { tap.value(id); true } else false
                    }
                    map.addOnCameraIdleListener { idle.value() }
                    map.addOnMoveListener(object : org.maplibre.android.maps.MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) { gesture.value() }
                        override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                        override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                    })
                    ready.value(c)
                }
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
