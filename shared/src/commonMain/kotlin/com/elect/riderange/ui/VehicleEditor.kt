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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Skateboarding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.elect.riderange.vehicle.LinkKind
import com.elect.riderange.vehicle.Vehicle
import com.elect.riderange.vehicle.VehicleClass
import com.elect.riderange.vehicle.VehicleType
import kotlinx.coroutines.launch
import java.util.Locale

/** Add / edit a vehicle. A new vehicle starts with the type list (presets), then the editable numbers. */
data class EditorState(val vehicle: Vehicle, val isNew: Boolean, val pickingType: Boolean)

fun VehicleClass.icon(): ImageVector = when (this) {
    VehicleClass.KICK_SCOOTER -> Icons.Filled.ElectricScooter
    VehicleClass.ONEWHEEL -> Icons.Filled.Skateboarding
    VehicleClass.E_SKATEBOARD -> Icons.Filled.Skateboarding
    VehicleClass.E_BIKE -> Icons.Filled.ElectricBike
    VehicleClass.OTHER -> Icons.Filled.Settings
}

@Composable
fun VehicleTypePicker(selected: VehicleType?, units: Units, onPick: (VehicleType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VehicleClass.entries.forEach { cls ->
            Text(cls.label, style = MaterialTheme.typography.titleMedium, color = RideColors.OneWay, modifier = Modifier.padding(top = 6.dp))
            VehicleType.entries.filter { it.vehicleClass == cls }.forEach { t ->
                val p = t.preset
                Card(
                    Modifier.fillMaxWidth().clickable { onPick(t) }, shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = if (t == selected) androidx.compose.ui.graphics.Color(0xFF12324A) else MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(cls.icon(), null, tint = if (t == selected) RideColors.RoundTrip else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("%.0f Wh · %s · %s".format(p.packWh, units.weight(p.weightKg), units.speedOf(p.topSpeedMph)) +
                                when (t.link) {
                                    LinkKind.NINEBOT -> " · live data"
                                    LinkKind.VESC -> " · live data"
                                    LinkKind.FUTURE_MOTION -> if (t == VehicleType.ONEWHEEL_PLUS) " · live data on pre-2018 firmware" else " · manual mode*"
                                    LinkKind.NONE -> " · manual"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Muted("* Future Motion's current firmware only shares live data with its own app. RideRange doesn't get around that, so those boards use GPS speed and a battery slider. Battery sizes are the published/retail figures; edit them to match your board.")
    }
}

/** Numeric text field that keeps what's typed and reports parsed values. */
@Composable
private fun NumField(label: String, value: Double?, suffix: String, digits: Int = 0, modifier: Modifier = Modifier, onValue: (Double?) -> Unit) {
    var text by remember { mutableStateOf(value?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; onValue(t.replace(',', '.').toDoubleOrNull()) },
        label = { Text(label) }, suffix = { Text(suffix) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = text.isNotBlank() && text.replace(',', '.').toDoubleOrNull() == null,
        modifier = modifier,
    )
}

@Composable
fun VehicleEditor(vm: MainViewModel, e: EditorState) {
    val units by vm.s.ride.units.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var v by remember(e.vehicle.id, e.vehicle.type) { mutableStateOf(e.vehicle) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (e.pickingType || e.isNew.not()) vm.closeEditor() else vm.editingVehicle.value = e.copy(pickingType = true) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(if (e.isNew) (if (e.pickingType) "Add a vehicle: type" else "Add a vehicle") else "Edit ${e.vehicle.name}",
                style = MaterialTheme.typography.titleLarge)
        }
        if (e.pickingType) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                item {
                    VehicleTypePicker(if (e.isNew) null else v.type, units) { t ->
                        val fresh = Vehicle.create(t, v.id, if (e.isNew || v.name == v.type.label) t.label else v.name, v.createdMs)
                        vm.editingVehicle.value = e.copy(vehicle = fresh.copy(bleAddress = null, bleName = null, model = if (t == v.type) v.model else null), pickingType = false)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            return
        }
        val metric = units.metric
        val lb = MassEstimator.LB_PER_KG
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(v.type.vehicleClass.icon(), null, tint = RideColors.OneWay)
                    Spacer(Modifier.width(8.dp))
                    Text(v.type.label, style = MaterialTheme.typography.titleMedium, color = RideColors.OneWay, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.editingVehicle.value = e.copy(vehicle = v, pickingType = true) }) { Text("Change type") }
                }
                Muted(linkLabel(v) + ". Starting values come from the published specs; edit anything that differs.")
            }
            item {
                OutlinedTextField(value = v.name, onValueChange = { v = v.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Battery", v.packWh, "Wh", modifier = Modifier.weight(1f)) { x -> x?.let { v = v.copy(packWh = it) } }
                    NumField("Usable", v.usableFraction * 100, "%", modifier = Modifier.weight(1f)) { x -> x?.let { v = v.copy(usableFraction = it / 100) } }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Vehicle weight", if (metric) v.weightKg else v.weightKg * lb, if (metric) "kg" else "lb", if (metric) 1 else 0, Modifier.weight(1f)) { x ->
                        x?.let { v = v.copy(weightKg = if (metric) it else it / lb) }
                    }
                    NumField("Top speed", if (metric) v.topSpeedMph * 1.609344 else v.topSpeedMph, if (metric) "km/h" else "mph", modifier = Modifier.weight(1f)) { x ->
                        x?.let { v = v.copy(topSpeedMph = if (metric) it / 1.609344 else it) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Rated range", if (metric) v.ratedRangeMi * 1.609344 else v.ratedRangeMi, if (metric) "km" else "mi", modifier = Modifier.weight(1f)) { x ->
                        x?.let { v = v.copy(ratedRangeMi = if (metric) it / 1.609344 else it) }
                    }
                    NumField("Starting use", v.defaultWhPerMi, "Wh/mi", 1, Modifier.weight(1f)) { x -> x?.let { v = v.copy(defaultWhPerMi = it) } }
                }
                Muted("Starting use is the flat-ground consumption before RideRange has learned from your rides.")
            }
            if (v.type.link == LinkKind.FUTURE_MOTION || v.type.link == LinkKind.VESC) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Wheel diameter", v.wheelDiameterMm, "mm", modifier = Modifier.weight(1f)) { x -> v = v.copy(wheelDiameterMm = x) }
                    if (v.type.link == LinkKind.VESC) NumField("Gear ratio", v.gearRatio, ":1", 2, Modifier.weight(1f)) { x -> v = v.copy(gearRatio = x) }
                }
            }
            if (v.type.link == LinkKind.VESC) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Cells in series", v.cellsSeries?.toDouble(), "s", modifier = Modifier.weight(1f)) { x -> v = v.copy(cellsSeries = x?.toInt()) }
                    NumField("Motor pole pairs", v.motorPolePairs?.toDouble(), "", modifier = Modifier.weight(1f)) { x -> v = v.copy(motorPolePairs = x?.toInt()) }
                }
                Muted("Used only when the controller doesn't report them itself: battery % from pack voltage ÷ cells (a VESC BMS or the controller's own battery level wins), speed and distance from electrical RPM ÷ pole pairs ÷ gear ratio and the wheel size (the controller's configured speed wins). Gear ratio = motor turns per wheel turn: 1 for a hub motor, wheel pulley ÷ motor pulley teeth for a belt drive.")
            }
            item {
                error?.let { Text(it, color = RideColors.Red) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val problem = com.elect.riderange.vehicle.Garage.validate(v)
                        if (problem != null) { error = problem; return@Button }
                        scope.launch {
                            vm.s.settings.saveVehicle(v.copy(name = v.name.trim().ifBlank { v.type.label }), makeActive = e.isNew)
                            vm.closeEditor()
                            vm.vehicleSection.value = 0
                        }
                    }, modifier = Modifier.height(52.dp)) { Text(if (e.isNew) "Add to garage" else "Save") }
                    OutlinedButton(onClick = { vm.closeEditor() }, modifier = Modifier.height(52.dp)) { Text("Cancel") }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

