package com.elect.riderange.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.ui.theme.RideColors
import kotlinx.coroutines.launch

@Composable
fun RideOverlay(vm: MainViewModel) {
    val s = vm.s
    val units by s.ride.units.collectAsStateWithLifecycle()
    val speed by s.ride.speed.collectAsStateWithLifecycle()
    val fromScooter by s.ride.speedFromScooter.collectAsStateWithLifecycle()
    val battery by s.ride.battery.collectAsStateWithLifecycle()
    val range by s.ride.range.collectAsStateWithLifecycle()
    val model by s.ride.model.collectAsStateWithLifecycle()
    val scooter by s.scooter.state.collectAsStateWithLifecycle()
    val rec by s.ride.recording.collectAsStateWithLifecycle()
    val settings by s.ride.settings.collectAsStateWithLifecycle()
    val follow by vm.follow.collectAsStateWithLifecycle()
    val fix by s.location.fix.collectAsStateWithLifecycle()
    val vehicle by s.ride.vehicle.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(speed?.let { units.speed(it) } ?: "--", style = MaterialTheme.typography.displayLarge)
                            Spacer(Modifier.width(6.dp))
                            Text(units.speedUnit, fontSize = 22.sp, modifier = Modifier.padding(bottom = 10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            when {
                                fromScooter -> "${vehicle?.type?.vehicleClass?.let { if (it == com.elect.riderange.vehicle.VehicleClass.ONEWHEEL) "Board" else "Scooter" } ?: "Vehicle"} speed"
                                fix == null -> "Waiting for GPS…"
                                else -> "GPS speed"
                            },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("%.0f%%".format(battery.pct), fontSize = 40.sp, fontWeight = FontWeight.Black, color = RideColors.battery(battery.pct))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(when (scooter.phase) {
                                ScooterPhase.CONNECTED -> RideColors.RoundTrip
                                ScooterPhase.CONNECTING, ScooterPhase.HANDSHAKE, ScooterPhase.PRESS_BUTTON, ScooterPhase.SCANNING -> RideColors.Amber
                                else -> Color(0xFF6B7C8A)
                            }, 10)
                            Spacer(Modifier.width(6.dp))
                            Text(if (battery.fromScooter) "live" else if (scooter.manualMode || vehicle?.type?.link == com.elect.riderange.vehicle.LinkKind.NONE) "manual mode" else "set manually",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Panel(Modifier.fillMaxWidth()) {
                vehicle?.let { v ->
                    Row(Modifier.fillMaxWidth().clickable { vm.setTab(Tab.SCOOTER) }.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(v.type.vehicleClass.icon(), null, tint = RideColors.OneWay, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(v.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("%.0f Wh".format(v.packWh), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        LegendRow(RideColors.OneWay, "One-way ${units.range(range.oneWayRadiusM)}")
                        Spacer(Modifier.height(4.dp))
                        LegendRow(RideColors.RoundTrip, "Round trip ${units.range(range.roundTripRadiusM)}")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Road range ${units.range(range.roadRangeM)}", style = MaterialTheme.typography.bodyMedium)
                        Text(units.consumption(range.whPerMi), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(model.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!battery.fromScooter) {
                    var v by remember(settings.manualBattery) { mutableFloatStateOf(settings.manualBattery.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Battery", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = v, onValueChange = { v = it }, valueRange = 0f..100f,
                            onValueChangeFinished = { scope.launch { s.settings.update { it.copy(manualBattery = v.toDouble().let { x -> Math.round(x).toDouble() }) } } },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalIconButton(onClick = vm::recenter, modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (follow) RideColors.OneWay else RideColors.PanelSolid)) {
                Icon(Icons.Filled.MyLocation, "Follow me", tint = if (follow) Color.White else MaterialTheme.colorScheme.onSurface)
            }
            FilledTonalIconButton(
                onClick = { scope.launch { s.settings.update { it.copy(showParkingOnRide = !it.showParkingOnRide) } }; vm.onCameraIdle() },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (settings.showParkingOnRide) Color(0xFF1565C0) else RideColors.PanelSolid),
            ) { Icon(Icons.Filled.LocalParking, "Show bike parking") }
        }

        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (rec.active) {
                Button(onClick = { s.ride.manualStop() }, colors = ButtonDefaults.buttonColors(containerColor = RideColors.Red),
                    modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Recording · ${units.range(rec.distanceM)} · Stop", fontSize = 17.sp)
                }
            } else {
                Button(onClick = { s.ride.manualStart() }, colors = ButtonDefaults.buttonColors(containerColor = RideColors.PanelSolid, contentColor = Color.White),
                    modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Filled.FiberManualRecord, null, tint = RideColors.Red)
                    Spacer(Modifier.width(8.dp))
                    Text(if (settings.autoTrips) "Trips record automatically · Start now" else "Start trip", fontSize = 15.sp)
                }
            }
        }
    }
}
