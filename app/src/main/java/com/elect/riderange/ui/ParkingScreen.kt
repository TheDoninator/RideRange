package com.elect.riderange.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.core.Geo
import com.elect.riderange.parking.ParkingSpot
import com.elect.riderange.parking.SpotKind
import com.elect.riderange.ui.theme.RideColors
import kotlinx.coroutines.launch

private fun kindColor(k: SpotKind) = when (k) {
    SpotKind.PARKING -> Color(0xFF4F8DFF)
    SpotKind.REPAIR -> Color(0xFFF2994A)
    SpotKind.CHARGING -> Color(0xFFEB5757)
}

private fun details(s: ParkingSpot): String = listOfNotNull(
    s.capacity?.let { "$it spaces" },
    s.covered?.let { if (it) "covered" else "not covered" },
    s.fee?.let { if (it == "no") "free" else if (it == "yes") "fee" else "fee: $it" },
    s.access?.takeIf { it != "yes" }?.let { "access: $it" },
).joinToString(" · ").ifEmpty { "No details mapped" }

@Composable
fun ParkingOverlay(vm: MainViewModel) {
    val s = vm.s
    val state by s.parking.state.collectAsStateWithLifecycle()
    val fix by s.location.fix.collectAsStateWithLifecycle()
    val units by s.ride.units.collectAsStateWithLifecycle()
    val settings by s.ride.settings.collectAsStateWithLifecycle()
    val selected by vm.selectedSpot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val here = fix?.pos
    val sorted = state.spots.sortedBy { sp -> here?.let { Geo.distance(it, sp.pos) } ?: 0.0 }

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("Parking") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = kindColor(SpotKind.PARKING), selectedLabelColor = Color.White))
            FilterChip(selected = settings.showRepair, onClick = { scope.launch { s.settings.update { it.copy(showRepair = !it.showRepair) }; vm.onCameraIdle() } },
                label = { Text("Repair stations") }, colors = FilterChipDefaults.filterChipColors(containerColor = RideColors.PanelSolid,
                    selectedContainerColor = kindColor(SpotKind.REPAIR), selectedLabelColor = Color.White))
            FilterChip(selected = settings.showCharging, onClick = { scope.launch { s.settings.update { it.copy(showCharging = !it.showCharging) }; vm.onCameraIdle() } },
                label = { Text("Charging") }, colors = FilterChipDefaults.filterChipColors(containerColor = RideColors.PanelSolid,
                    selectedContainerColor = kindColor(SpotKind.CHARGING), selectedLabelColor = Color.White))
        }

        Panel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (selected != null) 0.36f else 0.42f)) {
            val sel = selected
            if (sel != null) {
                Text(sel.name ?: sel.typeLabel, style = MaterialTheme.typography.titleLarge)
                Text(sel.typeLabel + (here?.let { " · " + units.distance(Geo.distance(it, sel.pos)) + " away" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(details(sel), style = MaterialTheme.typography.bodyLarge)
                sel.tags.forEach { (k, v) -> Text("${k.replace('_', ' ')}: $v", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.navigateTo(sel) }, modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RideColors.RoundTrip)) {
                        Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(6.dp)); Text("Navigate here", fontSize = 17.sp)
                    }
                    TextButton(onClick = { vm.selectedSpot.value = null }, modifier = Modifier.height(52.dp)) { Text("Back") }
                }
                return@Panel
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bike parking nearby", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (sorted.isEmpty() && !state.loading) Text("None mapped in this area yet. Zoom in or move the map.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(sorted.take(200), key = { it.id }) { sp ->
                    Row(Modifier.fillMaxWidth().clickable {
                        vm.selectedSpot.value = sp
                        vm.follow.value = false
                        vm.map?.moveTo(sp.pos, zoom = 17.0)
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(kindColor(sp.kind), 14)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sp.name ?: sp.typeLabel, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(details(sp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        here?.let { Text(units.distance(Geo.distance(it, sp.pos)), style = MaterialTheme.typography.titleMedium) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
