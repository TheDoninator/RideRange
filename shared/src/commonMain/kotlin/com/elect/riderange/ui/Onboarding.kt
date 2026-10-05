package com.elect.riderange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.core.Units
import com.elect.riderange.range.MassEstimator
import com.elect.riderange.ui.theme.RideColors
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.VehicleType
import kotlinx.coroutines.launch
import com.elect.riderange.core.currentTimeMillis

/**
 * First run: units, rider weight, first vehicle, permissions explained. Upgraded installs (migrated garage) skip it;
 * Settings → About → Intro shows it again.
 */
@Composable
fun Onboarding(vm: MainViewModel, requestLocation: () -> Unit, requestNotifications: () -> Unit) {
    val s = vm.s
    val st by s.ride.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(0) }
    var weightText by rememberSaveable { mutableStateOf("") }
    var type by remember { mutableStateOf<VehicleType?>(st.vehicle?.type) }
    var name by rememberSaveable { mutableStateOf("") }
    val units = Units(st.metric)
    val steps = 4

    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        LinearProgressIndicator(progress = { (step + 1f) / steps }, modifier = Modifier.fillMaxWidth(), color = RideColors.RoundTrip)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                0 -> item {
                    Text("Welcome to RideRange", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("See how far your battery takes you, plan bike-friendly routes with battery estimates, find bike parking and check the local rules for e-scooters, e-bikes and one-wheel boards.",
                        style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(20.dp))
                    Text("Units", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !st.metric, onClick = { scope.launch { s.settings.update { it.copy(metric = false) } } },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("mph · miles · lb") }
                        SegmentedButton(selected = st.metric, onClick = { scope.launch { s.settings.update { it.copy(metric = true) } } },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("km/h · km · kg") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Muted("Everything stays on this phone: no account, no ads, no tracking. Maps, routes and search use public OpenStreetMap services (details under Settings → About → Privacy).")
                }
                1 -> item {
                    Text("Your weight", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Weight changes how much energy each mile takes, so it makes the range circles and route battery estimates more accurate. Once you've ridden with live battery data, RideRange can estimate it from your rides.",
                        style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = weightText, onValueChange = { weightText = it }, singleLine = true,
                        label = { Text("Rider weight") }, suffix = { Text(if (st.metric) "kg" else "lb") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                        isError = weightText.isNotBlank() && parseWeight(weightText, st.metric) == null)
                    Muted("You can skip this; until you enter it, an average adult (165 lb / 75 kg) is used.")
                }
                2 -> item {
                    Text("Your vehicle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Muted("Pick the closest match; you can add more vehicles and edit every number later in the Garage.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name (optional)") },
                        placeholder = { Text(type?.label ?: "My scooter") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    VehicleTypePicker(type, units) { type = it }
                }
                else -> item {
                    Text("Permissions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    PermissionCard(Icons.Filled.LocationOn, "Location",
                        "Centres the map on you, draws the range circles, gives turn-by-turn directions, records trips and finds the rules for your state. Used while the app is open, and during a recorded ride or navigation with a visible notification.") {
                        OutlinedButton(onClick = requestLocation) { Text("Allow location") }
                    }
                    PermissionCard(Icons.Filled.Notifications, "Notifications",
                        "Shows the ride notification that keeps GPS and the vehicle link running while the screen is off.") {
                        OutlinedButton(onClick = requestNotifications) { Text("Allow notifications") }
                    }
                    PermissionCard(Icons.Filled.Bluetooth, "Nearby devices",
                        "Only asked when you tap Scan on the Vehicle tab, to read live speed and battery from your vehicle (read-only).") {}
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (step == 1) TextButton(onClick = { step++ }) { Text("Skip") }
            Button(
                enabled = when (step) { 1 -> parseWeight(weightText, st.metric) != null; 2 -> type != null; else -> true },
                onClick = {
                    when (step) {
                        1 -> { val w = parseWeight(weightText, st.metric); scope.launch { s.settings.update { it.copy(riderLb = w) } }; step++ }
                        2 -> {
                            val t = type!!
                            scope.launch {
                                val existing = st.vehicle
                                if (existing == null || existing.type != t) {
                                    val v = Vehicle.create(t, kotlin.uuid.Uuid.random().toString(), name.trim().ifBlank { t.label }, currentTimeMillis())
                                    s.settings.saveVehicle(v, makeActive = true)
                                } else if (name.isNotBlank()) s.settings.updateVehicle(existing.id) { it.copy(name = name.trim()) }
                            }
                            step++
                        }
                        3 -> scope.launch { s.settings.update { it.copy(onboarded = true) } }
                        else -> step++
                    }
                },
                modifier = Modifier.height(52.dp),
            ) { Text(if (step == 3) "Start riding" else "Next") }
        }
    }
}
}

/** Rider weight typed in lb or kg -> lb, or null if not a plausible number. */
fun parseWeight(text: String, metric: Boolean): Double? {
    val x = text.replace(',', '.').trim().toDoubleOrNull() ?: return null
    val lb = if (metric) x * MassEstimator.LB_PER_KG else x
    return lb.takeIf { it in 40.0..450.0 }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, text: String, action: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = RideColors.OneWay)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                action()
            }
        }
    }
}
