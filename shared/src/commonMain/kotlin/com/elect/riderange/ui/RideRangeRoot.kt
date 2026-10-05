package com.elect.riderange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.core.LatLon
import com.elect.riderange.core.Units
import com.elect.riderange.location.Fix
import com.elect.riderange.range.RangeResult
import com.elect.riderange.map.MapSurface
import kotlinx.coroutines.flow.combine

private class MapInputs(val fix: Fix?, val range: RangeResult, val tab: Tab, val units: Units, val navigating: Boolean)

/**
 * The whole app UI (Android MainActivity and the iPhone app both show this). The callbacks ask for platform permissions;
 * [requestLocationOnce] asks for location at most once per launch (upgraded installs skip the intro).
 */
@Composable
fun RideRangeRoot(
    vm: MainViewModel,
    requestBluetooth: () -> Unit,
    requestLocation: () -> Unit,
    requestNotifications: () -> Unit,
    requestLocationOnce: () -> Unit,
) {
    val s = vm.s
    val ready by s.ready.collectAsStateWithLifecycle()
    val settings by s.ride.settings.collectAsStateWithLifecycle()
    LaunchedEffect(ready, settings.onboarded) {
        if (ready && settings.onboarded) requestLocationOnce()
    }
    if (ready && !settings.onboarded) {
        Onboarding(vm, requestLocation, requestNotifications)
        return
    }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val tripDetail by vm.tripDetail.collectAsStateWithLifecycle()
    val navigating by s.nav.navigating.collectAsStateWithLifecycle()
    var controller by remember { mutableStateOf<MapSurface?>(null) }
    KeepScreenOn(navigating)
    LaunchedEffect(navigating) {
        if (!navigating) controller?.resetBearing()
    }

    // ---- keep the map's overlays in sync with the ride state ----
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        vm.map = c
        vm.applyPadding()
        s.location.fix.value?.let { c.moveTo(it.pos, zoom = 13.0, animate = false) }
        var framed = false
        var lastFollowed: LatLon? = null
        combine(s.location.fix, s.ride.range, vm.tab, s.ride.units, s.nav.navigating) { f, r, t, u, n -> MapInputs(f, r, t, u, n) }
            .collect { m ->
                val fix = m.fix
                c.setMe(fix?.pos)
                val showRange = (m.tab == Tab.RIDE || m.tab == Tab.ROUTE) && !m.navigating
                c.setRange(if (showRange) fix?.pos else null, m.range, m.units::range)
                if (fix != null && !framed && m.tab == Tab.RIDE) { framed = true; vm.showRange() }
                if (fix != null) {
                    if (vm.follow.value && m.tab in setOf(Tab.RIDE, Tab.ROUTE)) {
                        val nav = s.nav.nav.value
                        if (m.navigating) c.moveTo(nav?.snapped ?: fix.pos, zoom = 17.0, bearing = nav?.bearing ?: fix.bearing)
                        else if (m.tab == Tab.RIDE && (lastFollowed == null || com.elect.riderange.core.Geo.distance(lastFollowed!!, fix.pos) > 15)) {
                            // Only pan when the rider actually moved, so the range framing isn't undone.
                            if (lastFollowed != null) c.moveTo(fix.pos)
                            lastFollowed = fix.pos
                        }
                    }
                }
            }
    }
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        s.nav.plan.collect { p ->
            c.setRoute(p.summary?.route)
            c.setDestination(p.destination?.pos)
            val r = p.summary?.route
            if (r != null && !s.nav.navigating.value) c.fit(r.points + listOfNotNull(s.location.fix.value?.pos), 70, top = 520, bottom = 1150)
        }
    }
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        combine(s.parking.state, vm.tab, s.ride.settings) { p, t, st -> Triple(p, t, st) }.collect { (p, t, st) ->
            c.setParking(if (t == Tab.PARKING || (st.showParkingOnRide && t != Tab.SCOOTER)) p.spots else emptyList())
        }
    }
    LaunchedEffect(controller, tripDetail) {
        val c = controller ?: return@LaunchedEffect
        val id = tripDetail
        if (id == null) { c.setTrack(emptyList(), emptyList()); return@LaunchedEffect }
        val samples = s.trips.samples(id)
        vm.trackByPower.collect { byPower ->
            val pts = samples.map { LatLon(it.lat, it.lon) }
            c.setTrack(pts, TripColors.colors(samples, byPower))
            c.fit(pts, 60, top = 60, bottom = 1500)
        }
    }

    Scaffold(
        bottomBar = {
            if (!navigating) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { vm.setTab(t) },
                        icon = {
                            Icon(when (t) {
                                Tab.RIDE -> Icons.Filled.Speed
                                Tab.ROUTE -> Icons.Filled.Directions
                                Tab.PARKING -> Icons.Filled.LocalParking
                                Tab.RULES -> Icons.Filled.Gavel
                                Tab.SCOOTER -> (settings.vehicle?.type?.vehicleClass ?: com.elect.riderange.vehicle.VehicleClass.KICK_SCOOTER).icon()
                            }, contentDescription = t.label)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            // Before the first GPS fix: the middle of the contiguous US (no personal default location).
            val start = s.location.fix.value?.pos ?: LatLon(39.83, -98.58)
            MapHost(
                modifier = Modifier.fillMaxSize(),
                start = start,
                styleUrl = settings.urls.mapStyleUrl,
                onReady = { controller = it },
                onLongPress = vm::onLongPress,
                onParkingTap = vm::onParkingTap,
                onCameraIdle = vm::onCameraIdle,
                onUserGesture = { vm.follow.value = false },
            )
            when (tab) {
                Tab.RIDE -> RideOverlay(vm)
                Tab.ROUTE -> RouteOverlay(vm)
                Tab.PARKING -> ParkingOverlay(vm)
                Tab.RULES -> RulesScreen(vm)
                Tab.SCOOTER -> ScooterScreen(vm, requestBluetooth)
            }
        }
    }
}
