package com.elect.riderange.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.elect.riderange.core.LatLon
import com.elect.riderange.map.MapSurface

/** The one MapLibre map (kept alive across tabs). Reports long-presses, taps on parking pins and camera moves. */
@Composable
expect fun MapHost(
    modifier: Modifier,
    start: LatLon,
    styleUrl: String,
    onReady: (MapSurface) -> Unit,
    onLongPress: (LatLon) -> Unit,
    onParkingTap: (String) -> Unit,
    onCameraIdle: () -> Unit,
    onUserGesture: () -> Unit,
)

/** Keeps the display on while [on] (turn-by-turn navigation). */
@Composable
expect fun KeepScreenOn(on: Boolean)
