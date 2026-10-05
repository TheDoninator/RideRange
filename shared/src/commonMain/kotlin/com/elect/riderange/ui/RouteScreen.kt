@file:Suppress("DEPRECATION")

package com.elect.riderange.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.routing.RouteMode
import com.elect.riderange.routing.Turn
import com.elect.riderange.ui.theme.RideColors
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

fun turnIcon(t: Turn?): ImageVector = when (t) {
    Turn.LEFT -> Icons.Filled.TurnLeft
    Turn.RIGHT -> Icons.Filled.TurnRight
    Turn.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    Turn.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    Turn.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    Turn.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Turn.KEEP_LEFT, Turn.EXIT_LEFT -> Icons.Filled.ForkLeft
    Turn.KEEP_RIGHT, Turn.EXIT_RIGHT -> Icons.Filled.ForkRight
    Turn.U_TURN, Turn.U_TURN_LEFT, Turn.U_TURN_RIGHT -> Icons.Filled.UTurnLeft
    Turn.ROUNDABOUT, Turn.ROUNDABOUT_LEFT -> Icons.Filled.RoundaboutRight
    Turn.ARRIVE -> Icons.Filled.Flag
    else -> Icons.Filled.Straight
}

fun duration(seconds: Double): String {
    val m = (seconds / 60).roundToInt()
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

@Composable
fun RouteOverlay(vm: MainViewModel) {
    val navigating by vm.s.nav.navigating.collectAsStateWithLifecycle()
    if (navigating) NavOverlay(vm) else PlanOverlay(vm)
}

@Composable
private fun PlanOverlay(vm: MainViewModel) {
    val s = vm.s
    val plan by s.nav.plan.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val units by s.ride.units.collectAsStateWithLifecycle()
    val battery by s.ride.battery.collectAsStateWithLifecycle()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Surface(shape = RoundedCornerShape(16.dp), color = RideColors.Panel, shadowElevation = 6.dp) {
                Column {
                    TextField(
                        value = search.query.ifEmpty { if (plan.destination != null && search.query.isEmpty()) "" else search.query },
                        onValueChange = vm::search,
                        placeholder = { Text(plan.destination?.name ?: "Where to? (or long-press the map)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (search.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else if (search.query.isNotEmpty() || plan.destination != null) IconButton(onClick = { vm.search(""); s.nav.clear() }) {
                                Icon(Icons.Filled.Close, "Clear")
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (search.results.isNotEmpty() || search.error != null) {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            search.error?.let { e -> item { Text(e, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                            items(search.results) { p ->
                                Row(Modifier.fillMaxWidth().clickable { focus.clearFocus(); vm.choose(p) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Place, null, tint = RideColors.Red)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                                        Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteMode.entries.forEach { m ->
                    FilterChip(
                        selected = plan.mode == m, onClick = { s.nav.setMode(m) },
                        label = { Text(m.label, fontSize = 15.sp) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = RideColors.PanelSolid,
                            selectedContainerColor = RideColors.OneWay, selectedLabelColor = Color.White),
                        modifier = Modifier.height(44.dp),
                    )
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            when {
                plan.loading -> Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(if (plan.mode == RouteMode.BATTERY) "Comparing routes for battery use…" else "Finding a ${plan.mode.label.lowercase()} route…")
                    }
                }
                plan.summary != null -> SummaryCard(vm)
                plan.error != null -> Panel(Modifier.fillMaxWidth()) { Text(plan.error!!, color = MaterialTheme.colorScheme.error) }
                plan.destination == null -> Panel(Modifier.fillMaxWidth()) {
                    Text("Search for a place or long-press the map to set a destination.", style = MaterialTheme.typography.bodyLarge)
                    Text("Battery now %.0f%% · ${units.range(s.ride.range.value.oneWayRadiusM)} one-way".format(battery.pct),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(vm: MainViewModel) {
    val s = vm.s
    val plan by s.nav.plan.collectAsStateWithLifecycle()
    val units by s.ride.units.collectAsStateWithLifecycle()
    val sum = plan.summary ?: return
    val r = sum.route
    Panel(Modifier.fillMaxWidth()) {
        Text(plan.destination?.name ?: "Route", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (plan.fromCache) Text(plan.error ?: "Saved route", color = RideColors.Amber, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Distance", units.range(r.lengthM))
            Stat("Time", duration(r.timeS))
            Stat("Climb", units.height(r.ascendM))
            Stat("Battery", "%.0f Wh".format(sum.wh))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Uses", "%.0f%%".format(sum.pctUsed))
            Stat("On arrival", "%.0f%%".format(max(0.0, sum.pctOnArrival)), color = RideColors.battery(sum.pctOnArrival))
            Stat("Bike paths", "%.0f%%".format(sum.bikeShare * 100))
        }
        Spacer(Modifier.height(6.dp))
        if (!sum.reachable) Text("Outside your one-way range: the battery won't last (keeping the reserve).",
            color = RideColors.Red, fontWeight = FontWeight.Bold)
        else Text(if (sum.roundTripPossible) "Round trip possible on this charge." else "One way only: you can't make it back on this charge.",
            color = if (sum.roundTripPossible) RideColors.RoundTrip else RideColors.Amber, fontWeight = FontWeight.SemiBold)
        sum.savesWh?.let { if (it > 0.5) Text("Saves %.0f Wh vs the fastest route".format(it), color = RideColors.RoundTrip) else Text("The fastest route is also the most efficient here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { s.nav.start(); vm.follow.value = true }, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RideColors.RoundTrip)) {
            Icon(Icons.Filled.Navigation, null)
            Spacer(Modifier.width(8.dp))
            Text("Start", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NavOverlay(vm: MainViewModel) {
    val s = vm.s
    val nav by s.nav.nav.collectAsStateWithLifecycle()
    val plan by s.nav.plan.collectAsStateWithLifecycle()
    val units by s.ride.units.collectAsStateWithLifecycle()
    val speed by s.ride.speed.collectAsStateWithLifecycle()
    val battery by s.ride.battery.collectAsStateWithLifecycle()
    val settings by s.ride.settings.collectAsStateWithLifecycle()
    val rerouting by s.nav.rerouting.collectAsStateWithLifecycle()
    val info by s.trips.info.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val n = nav
    val sum = plan.summary

    Box(Modifier.fillMaxSize().padding(10.dp)) {
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color(0xF0124D36), shadowElevation = 8.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(turnIcon(n?.next?.turn), null, Modifier.size(64.dp), tint = Color.White)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(n?.let { units.distance(it.distToNextM) } ?: "--", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
                        Text(when {
                            rerouting -> "Rerouting…"
                            n?.offRoute == true -> "Off route"
                            n?.arrived == true -> "You have arrived"
                            else -> n?.next?.text ?: "Follow the route"
                        }, fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                n?.following?.let { f ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Then", color = Color(0xFFB9E6D3))
                        Spacer(Modifier.width(6.dp))
                        Icon(turnIcon(f.turn), null, Modifier.size(22.dp), tint = Color(0xFFB9E6D3))
                        Spacer(Modifier.width(4.dp))
                        Text(f.text, color = Color(0xFFB9E6D3))
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        Text(speed?.let { units.speed(it) } ?: "--", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.width(4.dp))
                        Text(units.speedUnit, Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { s.settings.update { it.copy(voiceMuted = !it.voiceMuted) } } }, modifier = Modifier.size(56.dp)) {
                        Icon(if (settings.voiceMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute voice", Modifier.size(32.dp))
                    }
                }
                if (n != null && sum != null) {
                    val remaining = n.remainingM
                    val v = max(speed ?: 0.0, info.typicalSpeed ?: 6.5)
                    val eta = System.currentTimeMillis() + (remaining / v * 1000).toLong()
                    val whLeft = sum.wh * (remaining / max(1.0, sum.route.lengthM))
                    val arrive = s.ride.estimator().pctAfter(battery.pct, whLeft)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("ETA", android.text.format.DateFormat.format("h:mm a", eta).toString())
                        Stat("Left", units.range(remaining))
                        Stat("Battery at end", "%.0f%%".format(max(0.0, arrive)), color = RideColors.battery(arrive))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { s.nav.stop() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("End navigation", fontSize = 17.sp)
                }
            }
        }
    }
}
