package com.elect.riderange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.core.Legal
import com.elect.riderange.core.ServiceUrls
import com.elect.riderange.core.UpdateCheck
import com.elect.riderange.data.AppKeys
import com.elect.riderange.data.AppSettings
import com.elect.riderange.data.TripEntity
import com.elect.riderange.data.UploadState
import com.elect.riderange.range.Accuracy
import com.elect.riderange.range.LearnedModel
import com.elect.riderange.range.MassEstimator
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.ui.theme.RideColors
import com.elect.riderange.upload.TripUploads
import com.elect.riderange.upload.UploadPaths
import com.elect.riderange.vehicle.LinkKind
import com.elect.riderange.vehicle.Vehicle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.elect.riderange.vehicle.vesc.VescSimulator
import com.elect.riderange.core.format

/** The Vehicle tab: Garage · Connect · Trips · Settings. */
@Composable
fun ScooterScreen(vm: MainViewModel, requestBluetooth: () -> Unit) {
    val detail by vm.tripDetail.collectAsStateWithLifecycle()
    val section by vm.vehicleSection.collectAsStateWithLifecycle()
    val editing by vm.editingVehicle.collectAsStateWithLifecycle()
    detail?.let { TripDetail(vm, it); return }
    editing?.let { e -> VehicleEditor(vm, e); return }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
            val labels = listOf("Garage", "Connect", "Trips", "Settings")
            labels.forEachIndexed { i, l ->
                SegmentedButton(selected = section == i, onClick = { vm.vehicleSection.value = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) {
                    Text(l, maxLines = 1, fontSize = 13.sp)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                0 -> garageSection(vm)
                1 -> connectSection(vm, requestBluetooth)
                2 -> tripsSection(vm)
                else -> settingsSection(vm)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

fun linkLabel(v: Vehicle): String = when (v.type.link) {
    LinkKind.NINEBOT -> "Bluetooth: Ninebot (read-only)"
    LinkKind.FUTURE_MOTION -> "Bluetooth: Onewheel (live data on older firmware only)"
    LinkKind.VESC -> "Bluetooth: VESC (read-only)"
    LinkKind.NONE -> "Manual mode (GPS speed, battery slider)"
}

// ---------------------------------------------------------------- Garage

private fun LazyListScope.garageSection(vm: MainViewModel) {
    item {
        val st by vm.s.ride.settings.collectAsStateWithLifecycle()
        val units by vm.s.ride.units.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var deleting by remember { mutableStateOf<Vehicle?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Garage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Muted("Each vehicle has its own battery, range settings, Bluetooth link, learned consumption and trips. Tap one to ride it.")
            if (st.vehicles.isEmpty()) Text("No vehicles yet: add the one you ride.", style = MaterialTheme.typography.bodyLarge)
            st.vehicles.forEach { v ->
                val active = v.id == st.activeVehicleId
                Card(
                    Modifier.fillMaxWidth().clickable { scope.launch { vm.s.settings.setActiveVehicle(v.id) } },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFF12324A) else MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (active) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, if (active) "Active" else "Make active",
                            tint = if (active) RideColors.RoundTrip else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(v.type.label, style = MaterialTheme.typography.bodyMedium, color = RideColors.OneWay)
                            Text("%.0f Wh · %s · rated %s".format(v.packWh, units.weight(v.weightKg), units.range(v.ratedRangeMi * 1609.344)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(linkLabel(v) + (v.model?.let { " · learned from %.0f mi".format(it.miles) } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.editVehicle(v) }) { Icon(Icons.Filled.Edit, "Edit ${v.name}") }
                        IconButton(onClick = { deleting = v }) { Icon(Icons.Filled.Delete, "Delete ${v.name}", tint = RideColors.Red) }
                    }
                }
            }
            Button(onClick = { vm.addVehicle() }, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Filled.Add, null); Text("  Add vehicle")
            }
        }
        deleting?.let { v ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete ${v.name}?") },
                text = { Text("Its settings and learned model are removed. Its trips stay on the phone but aren't shown under another vehicle.") },
                confirmButton = { TextButton(onClick = { scope.launch { vm.s.settings.deleteVehicle(v.id) }; deleting = null }) { Text("Delete", color = RideColors.Red) } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
    }
}

// ---------------------------------------------------------------- Connect

private fun LazyListScope.connectSection(vm: MainViewModel, requestBluetooth: () -> Unit) {
    item {
        val s = vm.s
        val st by s.scooter.state.collectAsStateWithLifecycle()
        val found by s.scooter.found.collectAsStateWithLifecycle()
        val settings by s.ride.settings.collectAsStateWithLifecycle()
        val units by s.ride.units.collectAsStateWithLifecycle()
        val v = settings.vehicle
        if (v == null) { Text("Add a vehicle in the Garage first.", style = MaterialTheme.typography.bodyLarge); return@item }
        Section(v.name) {
            Text(v.type.label, color = RideColors.OneWay)
            Spacer(Modifier.height(6.dp))
            if (v.type.link == LinkKind.NONE) {
                Text("Manual mode", style = MaterialTheme.typography.titleMedium)
                Muted("This vehicle has no supported Bluetooth link. Speed comes from GPS and you set the battery % with the slider on the Ride tab.")
                return@Section
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(when {
                    st.phase == ScooterPhase.CONNECTED -> RideColors.RoundTrip
                    st.manualMode -> RideColors.Amber
                    st.phase == ScooterPhase.ERROR -> RideColors.Red
                    st.phase == ScooterPhase.DISCONNECTED -> Color(0xFF6B7C8A)
                    else -> RideColors.Amber
                }, 14)
                Spacer(Modifier.width(8.dp))
                Text(when (st.phase) {
                    ScooterPhase.DISCONNECTED -> if (st.manualMode) "Manual mode" else "Not connected"
                    ScooterPhase.SCANNING -> "Scanning…"
                    ScooterPhase.CONNECTING -> "Connecting to ${st.name ?: st.address}…"
                    ScooterPhase.HANDSHAKE -> if (v.type.link == LinkKind.NINEBOT) "Authenticating…" else "Checking the board…"
                    ScooterPhase.PRESS_BUTTON -> "Press the power button"
                    ScooterPhase.CONNECTED -> "Connected to ${st.name ?: v.name}" + (st.serial?.let { " ($it)" } ?: "")
                    ScooterPhase.ERROR -> if (st.manualMode) "Manual mode" else "Not connected"
                }, style = MaterialTheme.typography.titleMedium)
            }
            st.firmware?.let { Muted("Firmware $it") }
            st.message?.let { Text(it, color = if (st.phase == ScooterPhase.ERROR && !st.manualMode) RideColors.Red else MaterialTheme.colorScheme.onSurfaceVariant) }
            Muted(when (v.type.link) {
                LinkKind.NINEBOT -> "Read-only: RideRange never changes scooter settings. The scooter accepts one Bluetooth connection at a time, so close the Segway app and Ninebot Bridge first."
                LinkKind.FUTURE_MOTION -> "Read-only. Live data works only on boards whose firmware shares it with other apps (Onewheel V1 / Onewheel+ before the 2018 \"Gemini\" update). Newer firmware (Gemini, Pint, XR hardware 4210+, GT) needs Future Motion's own authentication, which RideRange doesn't get around: those boards run in manual mode (GPS speed, battery slider)."
                LinkKind.VESC -> "Read-only: only asks for values (firmware, live values, other controllers on CAN, a VESC BMS, Float package data), never motor, configuration, firmware or package commands. Close VESC Tool / Float Control first. Cells, pole pairs, gear ratio and wheel size in the vehicle's settings are used only where the controller doesn't report speed or battery itself."
                LinkKind.NONE -> ""
            })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.phase in setOf(ScooterPhase.CONNECTED, ScooterPhase.CONNECTING, ScooterPhase.HANDSHAKE, ScooterPhase.PRESS_BUTTON)) {
                    OutlinedButton(onClick = { s.scooter.disconnect() }, modifier = Modifier.height(52.dp)) { Text("Disconnect") }
                } else {
                    Button(onClick = requestBluetooth, modifier = Modifier.height(52.dp)) { Icon(Icons.Filled.BluetoothSearching, null); Text("  Scan") }
                    settings.lastScooterAddress?.let { addr ->
                        OutlinedButton(onClick = { s.scooter.connect(addr, settings.lastScooterName) }, modifier = Modifier.height(52.dp)) {
                            Text("Connect ${settings.lastScooterName ?: addr}", maxLines = 1)
                        }
                    }
                }
            }
            if (v.type.link == LinkKind.VESC && VescSimulator.available && st.phase != ScooterPhase.CONNECTED) {
                Spacer(Modifier.height(6.dp))
                Muted("Debug build only: a simulated VESC (fake bytes through the real decoder), for testing without a board.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { s.scooter.connect(VescSimulator.FLOAT_BOARD, "Simulated Float board") }) { Text("Sim: Float board", maxLines = 1) }
                    OutlinedButton(onClick = { s.scooter.connect(VescSimulator.DUAL_MOTOR, "Simulated dual motor") }) { Text("Sim: dual motor", maxLines = 1) }
                }
            }
            if (st.phase != ScooterPhase.CONNECTED) found.filter { it.ninebot || it.name != null }.take(8).forEach { f ->
                Row(Modifier.fillMaxWidth().clickable { s.scooter.connect(f.address, f.name) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, null, tint = if (f.ninebot) RideColors.OneWay else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name ?: f.address, fontWeight = FontWeight.SemiBold)
                        Text("${f.address} · ${f.rssi} dBm" + if (f.ninebot) " · likely match" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Connect", color = RideColors.OneWay)
                }
            }
            st.telemetry?.let { t ->
                Spacer(Modifier.height(10.dp)); HorizontalDivider(); Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Battery", t.batteryPct?.let { "$it%" } ?: "-", color = t.batteryPct?.let { RideColors.battery(it.toDouble()) } ?: Color.White)
                    Stat("Voltage", t.voltage?.let { "%.1f V".format(it) } ?: "-")
                    Stat("Power", t.powerW?.let { "%.0f W".format(it) } ?: "-")
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Speed", t.speedMps?.let { "${units.speed(it)} ${units.speedUnit}" } ?: "-")
                    Stat("Temp", listOfNotNull(t.scooterTempC?.let { "%.0f°".format(it) }, t.batteryTempC?.let { "batt $it°" }).joinToString(" / ").ifEmpty { "-" })
                    Stat("Odometer", t.odometerM?.let { units.range(it.toDouble()) } ?: "-")
                }
            }
        }
    }
    item { VescDetails(vm) }
    item {
        val s = vm.s
        val settings by s.ride.settings.collectAsStateWithLifecycle()
        if (settings.vehicle?.type?.link != LinkKind.NINEBOT) return@item
        val scope = rememberCoroutineScope()
        var key by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf<String?>(null) }
        Section("Pairing key") {
            Muted("Already paired in Ninebot Bridge? Paste its key (32 hex characters) to skip pressing the power button.")
            OutlinedTextField(value = key, onValueChange = { key = it; msg = null }, label = { Text("Key (hex)") }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (AppKeys.parse(key) == null) msg = "That isn't 32 hex characters."
                    else scope.launch { s.settings.setPastedKey(key.filter { it.isLetterOrDigit() }); key = ""; msg = "Saved. It will be used on the next connection." }
                }) { Text("Use this key") }
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ---------------------------------------------------------------- Trips

private fun LazyListScope.tripsSection(vm: MainViewModel) {
    item {
        val info by vm.s.trips.info.collectAsStateWithLifecycle()
        val model by vm.s.ride.model.collectAsStateWithLifecycle()
        val units by vm.s.ride.units.collectAsStateWithLifecycle()
        val vehicle by vm.s.ride.vehicle.collectAsStateWithLifecycle()
        Section("Range model" + (vehicle?.let { " · ${it.name}" } ?: "")) {
            Text(model.label, style = MaterialTheme.typography.titleMedium, color = RideColors.RoundTrip)
            Text(Accuracy.summary(info.accuracyErrors), style = MaterialTheme.typography.bodyLarge)
            Muted("Measured riding: ${units.range(info.measuredMiles * 1609.344)}" +
                (info.measuredAvgWhPerMi?.let { " · average ${units.consumption(it)}" } ?: "") +
                if (model !is LearnedModel) " · learns after ~20 mi with live battery data" else "")
            (model as? LearnedModel)?.let { m ->
                Text("Wh/mi = %.1f + %.3f·v² + %.2f·climb%% − %.2f·descent%% + %.2f·cold°C (±%.1f)".format(m.coef[0], m.coef[1], m.coef[2], m.coef[3], m.coef[4], m.rmse),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
    item {
        val s = vm.s
        val settings by s.ride.settings.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        if (settings.hasToken && settings.uploadRepo.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Uploads to ${settings.uploadRepo}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { s.trips.queueAllPending(); s.platform.uploads.schedule(settings.uploadWifiOnly) } }) { Text("Upload all pending") }
        }
    }
    item {
        val vehicle by vm.s.ride.vehicle.collectAsStateWithLifecycle()
        val flow = remember(vehicle?.id) { vehicle?.id?.let { vm.s.trips.tripsFor(it) } ?: flowOf(emptyList()) }
        val trips by flow.collectAsStateWithLifecycle(initialValue = emptyList())
        val units by vm.s.ride.units.collectAsStateWithLifecycle()
        if (trips.isEmpty()) Text("No trips on ${vehicle?.name ?: "this vehicle"} yet. Trips record automatically when you ride faster than 3 mph for 30 s, or start one on the Ride tab.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            trips.forEach { t -> TripRow(t, units) { vm.tripDetail.value = t.id } }
        }
    }
}


@Composable
private fun TripRow(t: TripEntity, units: com.elect.riderange.core.Units, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(com.elect.riderange.core.DateFmt.format(t.startMs, "EEE d MMM, h:mm a"), style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(units.range(t.distanceM), duration(t.durationS), t.stats.whPerMi?.let { units.consumption(it) },
                    t.errorPct?.let { "model %+.0f%%".format(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UploadIcon(t.uploadState)
        }
    }
}

@Composable
fun UploadIcon(st: UploadState) {
    when (st) {
        UploadState.UPLOADED -> Icon(Icons.Filled.CloudDone, "Uploaded", tint = RideColors.RoundTrip)
        UploadState.QUEUED -> Icon(Icons.Filled.CloudQueue, "Queued", tint = RideColors.Amber)
        UploadState.FAILED -> Icon(Icons.Filled.ErrorOutline, "Upload failed", tint = RideColors.Red)
        UploadState.OFF -> Icon(Icons.Filled.CloudOff, "Not uploaded", tint = Color(0xFF6B7C8A))
    }
}

@Composable
fun NumberSetting(label: String, value: Double, suffix: String, step: Double, min: Double, max: Double, digits: Int = 0, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { onChange((value - step).coerceIn(min, max)) }) { Text("−", fontSize = 20.sp) }
        Text("%.${digits}f".format(value) + suffix, Modifier.width(118.dp).padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange((value + step).coerceIn(min, max)) }) { Text("+", fontSize = 20.sp) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- Settings

private fun LazyListScope.settingsSection(vm: MainViewModel) {
    item {
        val s = vm.s
        val st by s.ride.settings.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        fun upd(f: (AppSettings) -> AppSettings) = scope.launch { s.settings.update(f) }
        val v = st.vehicle ?: return@item
        Section("Range estimate · ${v.name}") {
            Muted("Usable energy = (battery % − reserve) × pack × usable share. Consumption is learned from your rides; this is the starting value.")
            NumberSetting("Battery pack", st.range.packWh, " Wh", 10.0, 50.0, 3000.0) { x -> upd { it.copy(range = it.range.copy(packWh = x)) } }
            NumberSetting("Usable share", st.range.usableFraction * 100, "%", 1.0, 70.0, 100.0) { x -> upd { it.copy(range = it.range.copy(usableFraction = x / 100)) } }
            NumberSetting("Reserve", st.range.reservePct, "%", 1.0, 0.0, 40.0) { x -> upd { it.copy(range = it.range.copy(reservePct = x)) } }
            NumberSetting("Consumption", st.range.defaultWhPerMi, " Wh/mi", 0.5, 6.0, 60.0, 1) { x -> upd { it.copy(range = it.range.copy(defaultWhPerMi = x)) } }
            NumberSetting("Detour factor", st.range.detourFactor, "×", 0.05, 1.0, 2.0, 2) { x -> upd { it.copy(range = it.range.copy(detourFactor = x)) } }
        }
    }
    item {
        val s = vm.s
        val st by s.ride.settings.collectAsStateWithLifecycle()
        val info by s.trips.info.collectAsStateWithLifecycle()
        val params by s.ride.paramsFlow.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        fun upd(f: (AppSettings) -> AppSettings) = scope.launch { s.settings.update(f) }
        val lb = MassEstimator.LB_PER_KG
        Section("Weight") {
            NumberSetting("Rider weight", st.riderLbOrDefault, " lb", 5.0, 60.0, 400.0) { x -> upd { it.copy(riderLb = x) } }
            if (st.riderLb == null) Text("Not entered yet: using 165 lb until you set it.", color = RideColors.Amber, style = MaterialTheme.typography.bodySmall)
            NumberSetting("Cargo", st.cargoKg * lb, " lb", 1.0, 0.0, 100.0) { x -> upd { it.copy(cargoKg = x / lb) } }
            Muted("${st.vehicle?.name ?: "Vehicle"} %.0f lb (%.1f kg) · total entered %.0f lb".format(st.vehicleKg * lb, st.vehicleKg, st.enteredMassKg * lb))
            Spacer(Modifier.height(8.dp))
            val m = info.mass?.takeIf { info.vehicleId == st.vehicle?.id }
            Text(
                if (m == null) "Estimated from rides: not yet (needs a ride with live power data that has some hills or speed-ups)"
                else "Estimated from rides: %.0f lb ±%.0f (%d trip%s)%s".format(m.massKg * lb, m.seKg * lb, m.trips, if (m.trips == 1) "" else "s",
                    if (m.confident) "" else " · not confident yet"),
                style = MaterialTheme.typography.bodyLarge,
                color = if (m?.confident == true) RideColors.RoundTrip else MaterialTheme.colorScheme.onSurface,
            )
            SwitchRow("Use estimated weight", st.useEstimatedMass) { x -> upd { it.copy(useEstimatedMass = x) } }
            Text("Range and route estimates use %.0f lb%s.".format(params.massKg * lb,
                if (st.useEstimatedMass && m?.confident == true) " (estimated)" else " (entered)"), style = MaterialTheme.typography.bodyMedium)
            Muted("The estimate is the whole rolling weight: rider, vehicle, bag or passenger. It assumes 80 % drivetrain efficiency; if that's off, the weight is off by about the same share, so it's shown with ± and you can switch it off.")
        }
    }
    item {
        val s = vm.s
        val st by s.ride.settings.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        Section("Riding") {
            SwitchRow("Metric units (km/h, km)", st.metric) { x -> scope.launch { s.settings.update { it.copy(metric = x) } } }
            SwitchRow("Record trips automatically", st.autoTrips) { x -> scope.launch { s.settings.update { it.copy(autoTrips = x) } } }
            SwitchRow("Voice directions", !st.voiceMuted) { x -> scope.launch { s.settings.update { it.copy(voiceMuted = !x) } } }
            SwitchRow("Bike parking on the Ride map", st.showParkingOnRide) { x -> scope.launch { s.settings.update { it.copy(showParkingOnRide = x) } } }
            if (st.vehicle?.type?.link == LinkKind.VESC) {
                SwitchRow("Pushback / duty alerts (vibrate + voice)", st.rideAlerts) { x -> scope.launch { s.settings.update { it.copy(rideAlerts = x) } } }
                NumberSetting("Duty alert at", st.dutyAlertPct.toDouble(), "%", 1.0, 50.0, 100.0) { x -> scope.launch { s.settings.update { it.copy(dutyAlertPct = x.toInt()) } } }
                Muted("VESC vehicles: the Float package's pushback (duty, voltage, temperature) and a duty cycle above this level vibrate the phone and are spoken, at most every few seconds. The mute button on the Ride tab's Float panel switches this off too.")
            }
        }
    }
    item { ServersSection(vm) }
    item { UploadSection(vm) }
    item { AboutSection(vm) }
}

@Composable
private fun UrlField(label: String, value: String, hint: String, set: (String) -> Unit) =
    OutlinedTextField(value = value, onValueChange = set, label = { Text(label) }, placeholder = { Text(hint, maxLines = 1) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), isError = value.isNotBlank() && !ServiceUrls.valid(value.trim().split(',').first().trim()))

@Composable
private fun ServersSection(vm: MainViewModel) {
    val s = vm.s
    val st by s.ride.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var u by remember(st.urls) { mutableStateOf(st.urls) }
    var msg by remember { mutableStateOf<String?>(null) }
    Section("Servers") {
        Muted("Maps, routing, search and parking come from free public OpenStreetMap servers. They're fair-use only, so a public build should point these at its own or paid hosts. Leave blank for the defaults.")
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide server addresses" else "Change server addresses") }
        if (open) {
            UrlField("Routing (BRouter)", u.brouter, ServiceUrls.DEFAULT_BROUTER) { u = u.copy(brouter = it) }
            UrlField("Place search (Nominatim)", u.nominatim, ServiceUrls.DEFAULT_NOMINATIM) { u = u.copy(nominatim = it) }
            UrlField("Bike parking (Overpass, comma separated)", u.overpass, ServiceUrls.DEFAULT_OVERPASS.first()) { u = u.copy(overpass = it) }
            UrlField("Elevation & weather (Open-Meteo)", u.openMeteo, ServiceUrls.DEFAULT_OPEN_METEO) { u = u.copy(openMeteo = it) }
            UrlField("Map style URL", u.mapStyle, ServiceUrls.DEFAULT_MAP_STYLE) { u = u.copy(mapStyle = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val bad = u.invalid()
                    if (bad.isNotEmpty()) msg = "Not a web address: " + bad.joinToString()
                    else scope.launch { s.settings.update { it.copy(urls = u) }; msg = "Saved. The map style applies after restarting the app." }
                }) { Text("Save") }
                OutlinedButton(onClick = { u = ServiceUrls() }) { Text("Defaults") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun UploadSection(vm: MainViewModel) {
    val s = vm.s
    val st by s.ride.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var repo by remember(st.uploadRepo) { mutableStateOf(st.uploadRepo) }
    var token by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Section("Advanced: upload trips to GitHub") {
        Muted("Optional and off by default. After each trip, the full log goes to a private GitHub repository of your own (useful for analysing rides). Needs a fine-grained token with Contents: Read and write on that repository only; it's stored encrypted on this phone.")
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else if (st.hasToken && st.uploadRepo.isNotBlank()) "On: ${st.uploadRepo}" else "Set up") }
        if (open) {
            OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("Your repository (owner/name)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = repo.isNotBlank() && UploadPaths.parseRepo(repo) == null)
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(if (st.hasToken) "Token saved (enter to replace)" else "GitHub token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            SwitchRow("Upload on Wi-Fi only", st.uploadWifiOnly) { x -> scope.launch { s.settings.update { it.copy(uploadWifiOnly = x) } } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        if (repo.isNotBlank() && UploadPaths.parseRepo(repo) == null) { msg = "Repository must look like owner/name"; return@launch }
                        s.settings.update { it.copy(uploadRepo = repo) }
                        if (token.isNotBlank()) s.settings.setToken(token)
                        token = ""
                        msg = "Saved."
                    }
                }) { Text("Save") }
                if (st.hasToken) OutlinedButton(onClick = { scope.launch { s.settings.setToken(null); msg = "Token removed: uploads are off." } }) { Text("Remove token") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AboutSection(vm: MainViewModel) {
    val s = vm.s
    val st by s.ride.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var show by remember { mutableStateOf<String?>(null) }
    var repo by remember(st.updateRepo) { mutableStateOf(st.updateRepo) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<UpdateCheck.Release?>(null) }
    Section("About RideRange ${s.info.versionName}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { show = "privacy" }) { Text("Privacy") }
            OutlinedButton(onClick = { show = "licences" }) { Text("Licences") }
            OutlinedButton(onClick = { vm.restartOnboarding() }) { Text("Intro") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Muted("RideRange is distributed on GitHub releases (an APK for Android, an IPA for iPhone). Enter the repository to check it for a newer version (only when you tap Check).")
        OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("Release repository (owner/name)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch {
                    val p = UploadPaths.parseRepo(repo) ?: run { updateMsg = "Repository must look like owner/name"; return@launch }
                    s.settings.update { it.copy(updateRepo = repo) }
                    updateMsg = "Checking…"
                    updateMsg = try {
                        val r = s.http.get(UpdateCheck.latestUrl(p.first, p.second), mapOf("Accept" to "application/vnd.github+json"))
                        val rel = if (r.code == 200) UpdateCheck.parse(r.body, s.info.releaseAsset) else null
                        release = rel?.takeIf { UpdateCheck.isNewer(it.tag, s.info.versionName) }
                        when {
                            r.code == 404 -> "No releases found in $repo."
                            rel == null -> "Couldn't read the latest release (HTTP ${r.code})."
                            release != null -> "Version ${rel.tag} is available."
                            else -> "You have the latest version (${rel.tag})."
                        }
                    } catch (e: Exception) { "No connection to GitHub." }
                }
            }) { Text("Check") }
            updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        release?.let { r -> TextButton(onClick = { try { uri.openUri(r.url) } catch (_: Exception) {} }) { Text("Open the ${r.tag} release page") } }
        Spacer(Modifier.height(4.dp))
        Muted(Legal.TRADEMARKS)
    }
    show?.let { which ->
        AlertDialog(
            onDismissRequest = { show = null },
            confirmButton = { TextButton(onClick = { show = null }) { Text("Close") } },
            title = { Text(if (which == "privacy") "Privacy" else "Licences & attribution") },
            text = {
                LazyColumn(Modifier.height(460.dp)) {
                    if (which == "privacy") item { Text(Legal.PRIVACY, style = MaterialTheme.typography.bodyMedium) }
                    else Legal.CREDITS.forEach { c ->
                        item {
                            Column(Modifier.fillMaxWidth().clickable { try { uri.openUri(c.url) } catch (_: Exception) {} }.padding(vertical = 8.dp)) {
                                Text(c.name, style = MaterialTheme.typography.titleSmall, color = RideColors.OneWay)
                                Text(c.use, style = MaterialTheme.typography.bodySmall)
                                Text("Licence: ${c.licence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
        )
    }
}
