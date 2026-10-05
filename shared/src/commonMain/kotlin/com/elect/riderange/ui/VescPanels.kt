package com.elect.riderange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.elect.riderange.scooter.ScooterPhase
import com.elect.riderange.ui.theme.RideColors
import com.elect.riderange.vehicle.vesc.VescController
import com.elect.riderange.vehicle.vesc.VescProtocol
import com.elect.riderange.vehicle.vesc.VescSnapshot
import kotlinx.coroutines.launch
import kotlin.math.abs

private val Pink = Color(0xFFC792EA)

private fun dutyColor(duty: Double, threshold: Double) = when {
    duty >= threshold -> RideColors.Red
    duty >= threshold - 0.15 -> RideColors.Amber
    else -> RideColors.RoundTrip
}

/** Footpad sensor box: lit when that side's ADC voltage is above ~2.5 V (the Float package's default threshold). */
@Composable
private fun Pad(label: String, volts: Double?) {
    val on = (volts ?: 0.0) > 2.5
    Box(
        Modifier.size(width = 50.dp, height = 46.dp).background(if (on) RideColors.RoundTrip.copy(alpha = 0.85f) else Color(0xFF2A3A47), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF00170D) else Color(0xFFB8C4CE))
            Text(volts?.let { "%.1f V".format(it) } ?: "-", fontSize = 11.sp, color = if (on) Color(0xFF00170D) else Color(0xFFB8C4CE))
        }
    }
}

/**
 * Ride-screen panel for VESC vehicles: Float package state (pitch, roll, footpads, setpoint / pushback), duty cycle,
 * per-motor numbers on dual-motor setups, and the warning banner with a mute button for the alerts.
 */
@Composable
fun VescRidePanel(vm: MainViewModel) {
    val sc by vm.s.scooter.state.collectAsStateWithLifecycle()
    val settings by vm.s.ride.settings.collectAsStateWithLifecycle()
    val warnings by vm.s.alerts.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snap = sc.vesc ?: return
    if (sc.phase != ScooterPhase.CONNECTED) return
    val now = System.currentTimeMillis()
    val rt = snap.freshFloat(now)
    val ctrls = snap.controllers(now)
    if (rt == null && ctrls.size < 2 && warnings.isEmpty()) return
    val threshold = settings.dutyAlertPct / 100.0
    Spacer(Modifier.height(8.dp))
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (rt != null) "Float · ${rt.state?.label ?: "state ?"}" else "${ctrls.size} motors",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Pink, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { vm.s.settings.update { it.copy(rideAlerts = !it.rideAlerts) } } }, modifier = Modifier.size(40.dp)) {
                Icon(if (settings.rideAlerts) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    if (settings.rideAlerts) "Mute duty and pushback alerts" else "Unmute duty and pushback alerts",
                    tint = if (settings.rideAlerts) MaterialTheme.colorScheme.onSurface else RideColors.Amber)
            }
        }
        warnings.firstOrNull()?.let { w ->
            Row(
                Modifier.fillMaxWidth().background(RideColors.Red, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(w.label.uppercase() + (if (warnings.size > 1) " +${warnings.size - 1}" else ""), color = Color.White,
                    fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (!settings.rideAlerts) Text("muted", color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val duty = snap.maxDuty(now)
            Stat("Duty", duty?.let { "%.0f%%".format(it * 100) } ?: "-", color = duty?.let { dutyColor(it, threshold) } ?: Color.White)
            if (rt != null) {
                Stat("Pitch", rt.pitch?.let { "%.1f°".format(it) } ?: "-")
                Stat("Roll", rt.roll?.let { "%.1f°".format(it) } ?: "-")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Pad("L", rt.adc1); Pad("R", rt.adc2) }
            } else {
                snap.totalPowerW(now)?.let { Stat("Power", "%.0f W".format(it)) }
            }
        }
        rt?.setpointAdjust?.let { sp ->
            Text("Setpoint: ${sp.label}" + (rt.footpad?.let { " · footpads: ${it.label.lowercase()}" } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = if (sp.pushback) RideColors.Red else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ctrls.size >= 2) {
            Spacer(Modifier.height(4.dp))
            Text(ctrls.mapIndexed { i, c -> "M${i + 1} %.0f° %.1f A".format(c.values.tempMosfetC, c.values.inputCurrentA) }.joinToString("  ·  ") +
                (snap.totalPowerW(now)?.let { "  ·  %.0f W".format(it) } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ControllerRow(index: Int, c: VescController) {
    val v = c.values
    Text("Motor ${index + 1}" + (c.id?.let { " · controller $it" } ?: "") + if (c.local) " (Bluetooth)" else " (via CAN)",
        style = MaterialTheme.typography.titleSmall, color = RideColors.OneWay)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("FET", "%.0f°".format(v.tempMosfetC))
        Stat("Motor", "%.0f°".format(v.tempMotorC))
        Stat("Phase", "%.1f A".format(v.motorCurrentA))
        Stat("Battery", "%.1f A".format(v.inputCurrentA))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Power", "%.0f W".format(v.inputPowerW))
        Stat("Duty", "%.0f%%".format(abs(v.dutyCycle) * 100))
        Stat("ERPM", "%.0f".format(v.erpm))
        Stat("Fault", if (v.faultCode == 0) "none" else "#${v.faultCode}", color = if (v.faultCode == 0) MaterialTheme.colorScheme.onSurface else RideColors.Red)
    }
}

@Composable
private fun CellGrid(b: VescProtocol.Bms) {
    val mean = b.cellV.average()
    b.cellV.withIndex().chunked(5).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { (i, v) ->
                val dev = v - mean
                val bg = when {
                    dev > 0.010 -> Color(0xFF1F4D3A)
                    dev < -0.010 -> Color(0xFF4D2A1F)
                    else -> Color(0xFF1C2A36)
                }
                val balancing = b.balancing.getOrNull(i) == true
                Column(
                    Modifier.weight(1f).background(bg, RoundedCornerShape(6.dp))
                        .then(if (balancing) Modifier.border(2.dp, RideColors.Amber, RoundedCornerShape(6.dp)) else Modifier)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${i + 1}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.3f".format(v), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Connect page details for VESC vehicles: every controller (local + CAN), totals, the VESC BMS and the Float package. */
@Composable
fun VescDetails(vm: MainViewModel) {
    val sc by vm.s.scooter.state.collectAsStateWithLifecycle()
    val units by vm.s.ride.units.collectAsStateWithLifecycle()
    val snap: VescSnapshot = sc.vesc ?: return
    if (sc.phase != ScooterPhase.CONNECTED) return
    val now = System.currentTimeMillis()
    val ctrls = snap.controllers(now)
    val setup = snap.freshSetup(now)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(if (ctrls.size > 1) "Motor controllers (${ctrls.size})" else "Motor controller") {
            ctrls.forEachIndexed { i, c ->
                if (i > 0) { Spacer(Modifier.height(6.dp)); HorizontalDivider(); Spacer(Modifier.height(6.dp)) }
                ControllerRow(i, c)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Total power", snap.totalPowerW(now)?.let { "%.0f W".format(it) } ?: "-", color = RideColors.RoundTrip)
                Stat("Battery current", snap.totalInputCurrent(now)?.let { "%.1f A".format(it) } ?: "-")
                Stat("Voltage", snap.voltage(now)?.let { "%.1f V".format(it) } ?: "-")
            }
            Muted(when {
                snap.summedOverCan(now) -> "Total = sum of the ${ctrls.size} controllers, used for range, weight and the learned model."
                setup?.numVescs != null -> "Total reported by the controller for ${setup.numVescs} VESC${if (setup.numVescs == 1) "" else "s"} on its CAN bus."
                else -> "Total from the controller's own reading."
            })
            val silent = snap.canIds.filter { it !in snap.can.keys }
            Muted(when {
                !snap.canPinged -> "Looking for other controllers on CAN…"
                snap.canIds.isEmpty() -> "No other devices on CAN."
                else -> "CAN ids found: ${snap.canIds.joinToString()}" +
                    if (silent.isNotEmpty()) " (${silent.joinToString()}: no motor values, probably a BMS or Bluetooth module)" else ""
            })
            setup?.let { st ->
                Spacer(Modifier.height(6.dp))
                Muted(listOfNotNull(
                    st.batteryLevel?.let { "controller battery level %.0f %%".format(it * 100) },
                    st.whBatteryLeft?.let { "%.0f Wh left".format(it) },
                    "speed %s %s".format(units.speed(abs(st.speedMps)), units.speedUnit),
                    st.odometerM?.let { "odometer ${units.range(it.toDouble())}" },
                ).joinToString(" · ") + " (from the controller's own wheel/battery settings)")
            }
        }
        snap.freshBms(now)?.let { b ->
            Section("Battery (VESC BMS)") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Charge", b.soc?.let { "%.0f%%".format(it * 100) } ?: "-", color = b.soc?.let { RideColors.battery(it * 100) } ?: Color.White)
                    Stat("Pack", "%.2f V".format(b.packV))
                    Stat("Current", "%.1f A".format(b.currentA))
                    Stat("Health", b.soh?.let { "%.0f%%".format(it * 100) } ?: "-")
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Lowest cell", b.cellMin?.let { "%.3f".format(it) } ?: "-")
                    Stat("Highest", b.cellMax?.let { "%.3f".format(it) } ?: "-")
                    Stat("Spread", b.cellDelta?.let { "%.0f mV".format(it * 1000) } ?: "-",
                        color = if ((b.cellDelta ?: 0.0) > 0.05) RideColors.Amber else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(8.dp))
                Text("${b.cellV.size} cells" + if (b.balancingCount > 0) " · ${b.balancingCount} balancing (outlined)" else "",
                    style = MaterialTheme.typography.labelLarge)
                CellGrid(b)
                if (b.tempsC.isNotEmpty()) Muted("Temperatures: " + b.tempsC.joinToString(" / ") { "%.1f°".format(it) } +
                    (b.tempMaxCellC?.let { " · hottest cell %.1f°".format(it) } ?: ""))
                Muted("Battery % on the Ride tab comes from this BMS while it answers.")
            }
        }
        val rt = snap.freshFloat(now)
        if (rt != null || snap.floatInfo != null) Section("Float package") {
            Muted("Version " + (snap.floatInfo?.label ?: "unknown") + " · read-only (real-time data only, no tuning)")
            rt?.let { r ->
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("State", r.state?.label ?: "?", color = if (r.state?.fault == true) RideColors.Amber else MaterialTheme.colorScheme.onSurface)
                    Stat("Footpads", r.footpad?.label ?: "?")
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Pitch", r.pitch?.let { "%.1f°".format(it) } ?: "-")
                    Stat("True pitch", r.truePitch?.let { "%.1f°".format(it) } ?: "-")
                    Stat("Roll", r.roll?.let { "%.1f°".format(it) } ?: "-")
                }
                Spacer(Modifier.height(6.dp))
                Text("Setpoint ${r.setpoint?.let { "%.1f°".format(it) } ?: "-"} · ${r.setpointAdjust?.label ?: "?"}",
                    color = if (r.pushback) RideColors.Red else MaterialTheme.colorScheme.onSurface)
                Muted(listOfNotNull(
                    r.atr?.let { "ATR %.1f°".format(it) }, r.torqueTilt?.let { "torque tilt %.1f°".format(it) },
                    r.turnTilt?.let { "turn tilt %.1f°".format(it) }, r.motorCurrent?.let { "motor %.1f A".format(it) },
                ).joinToString(" · ").ifEmpty { "Older package: only the basic values." })
                if (r.state == null || r.setpointAdjust == null) Muted("Some values from this package version weren't recognised and are hidden.")
            }
        }
    }
}
