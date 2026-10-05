package com.elect.riderange.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elect.riderange.core.Geo
import com.elect.riderange.data.UploadState
import com.elect.riderange.trips.Sample
import com.elect.riderange.trips.TripExport
import com.elect.riderange.ui.theme.RideColors
import com.elect.riderange.upload.TripUploads
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A line chart of [ys] against [xs] (nulls are gaps). */
@Composable
fun LineChart(title: String, xs: List<Double>, ys: List<Double?>, color: Color, unit: String, fill: Boolean = false, zeroLine: Boolean = false) {
    val vals = ys.filterNotNull()
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (vals.isNotEmpty()) Text("%.0f – %.0f %s".format(vals.min(), vals.max(), unit), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vals.size < 2) {
            Text("No data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 4.dp)) {
            val x0 = xs.first(); val x1 = maxOf(xs.last(), x0 + 1e-6)
            var lo = vals.min(); var hi = vals.max()
            if (zeroLine) { lo = minOf(lo, 0.0); hi = maxOf(hi, 0.0) }
            if (hi - lo < 1e-6) { hi += 1; lo -= 1 }
            fun px(x: Double) = ((x - x0) / (x1 - x0) * size.width).toFloat()
            fun py(y: Double) = (size.height - (y - lo) / (hi - lo) * size.height).toFloat()
            if (zeroLine) drawLine(Color(0x55FFFFFF), Offset(0f, py(0.0)), Offset(size.width, py(0.0)))
            val path = Path()
            var started = false
            xs.indices.forEach { i ->
                val y = ys.getOrNull(i)
                if (y == null) { started = false; return@forEach }
                if (!started) { path.moveTo(px(xs[i]), py(y)); started = true } else path.lineTo(px(xs[i]), py(y))
            }
            if (fill) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(px(xs.last()), size.height); lineTo(px(xs.first()), size.height); close()
                }
                drawPath(area, color.copy(alpha = 0.25f))
            }
            drawPath(path, color, style = Stroke(width = 3f))
        }
    }
}

@Composable
fun TripDetail(vm: MainViewModel, id: Long) {
    val s = vm.s
    val trip by s.trips.observe(id).collectAsStateWithLifecycle(initialValue = null)
    val units by s.ride.units.collectAsStateWithLifecycle()
    val byPower by vm.trackByPower.collectAsStateWithLifecycle()
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(id) { samples = s.trips.samples(id) }
    val t = trip ?: return

    // Distance axis for the charts.
    val xs = remember(samples) {
        var acc = 0.0
        samples.mapIndexed { i, smp -> if (i > 0) acc += com.elect.riderange.trips.TripMath.stepDistance(samples[i - 1], smp); acc / if (units.metric) 1000.0 else Geo.M_PER_MI }
    }

    fun share(name: String, mime: String, text: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export trip"))
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.36f))      // the map (with the coloured track) shows through here
        LazyColumn(
            Modifier.weight(0.64f).fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.tripDetail.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.US).format(Date(t.startMs)), style = MaterialTheme.typography.titleLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byPower, onClick = { vm.trackByPower.value = false }, label = { Text("Colour by speed") })
                    FilterChip(selected = byPower, onClick = { vm.trackByPower.value = true }, label = { Text("Colour by power") })
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("Distance", units.range(t.distanceM)); Stat("Time", duration(t.durationS))
                            Stat("Avg", "${units.speed(t.avgSpeed)} ${units.speedUnit}"); Stat("Max", "${units.speed(t.maxSpeed)}")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("Climb", units.height(t.climbM)); Stat("Descent", units.height(t.descentM))
                            Stat("Energy", t.whUsed?.let { "%.0f Wh".format(it) } ?: "-")
                            Stat("Regen", t.regenWh?.let { "%.1f Wh".format(it) } ?: "-")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("Use", t.stats.whPerMi?.let { units.consumption(it) } ?: "-")
                            Stat("Battery", if (t.batteryStart != null && t.batteryEnd != null) "%.0f→%.0f%%".format(t.batteryStart, t.batteryEnd) else "-")
                            Stat("Temp", (t.avgTempC ?: t.weatherTempC)?.let { "%.0f°C".format(it) } ?: "-")
                        }
                        Text(
                            when {
                                t.predictedWh == null -> "No prediction recorded."
                                t.whUsed == null -> "Predicted %.0f Wh (connect the scooter to measure the actual energy).".format(t.predictedWh)
                                else -> "Predicted %.0f Wh, actual %.0f Wh (%+.0f%%) · %s".format(t.predictedWh, t.whUsed, t.errorPct ?: 0.0, t.modelLabel ?: "")
                            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (t.demCorrected) Text("Elevation corrected with terrain data (Open-Meteo).", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val dist = if (units.metric) "km" else "mi"
                        LineChart("Elevation (by $dist)", xs, samples.map { (it.ele ?: it.gpsAlt)?.let { e -> if (units.metric) e else e / Geo.M_PER_FT } }, RideColors.Amber,
                            if (units.metric) "m" else "ft", fill = true)
                        LineChart("Speed", xs, samples.map { it.speed?.let { v -> if (units.metric) v * 3.6 else v / Geo.MPS_PER_MPH } }, RideColors.OneWay, units.speedUnit)
                        LineChart("Power", xs, samples.map { it.powerW }, RideColors.Red, "W", zeroLine = true)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UploadIcon(t.uploadState)
                            Text("  " + when (t.uploadState) {
                                UploadState.UPLOADED -> "Uploaded" + (t.uploadPath?.let { ": $it" } ?: "")
                                UploadState.QUEUED -> "Queued for upload"
                                UploadState.FAILED -> "Upload failed: ${t.uploadMessage ?: ""}"
                                UploadState.OFF -> t.uploadMessage ?: "Not uploaded (add a GitHub token in Settings to enable)"
                            }, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (t.uploadState != UploadState.UPLOADED) TextButton(onClick = {
                                scope.launch {
                                    s.trips.markUpload(t.id, UploadState.QUEUED, null, null)
                                    TripUploads.schedule(s.context, s.ride.settings.value.uploadWifiOnly)
                                }
                            }) { Text(if (t.uploadState == UploadState.FAILED) "Retry upload" else "Upload") }
                            OutlinedButton(onClick = { scope.launch { s.trips.record(id)?.let { share("riderange-trip-$id.gpx", "application/gpx+xml", TripExport.gpx(it)) } } }) { Text("GPX") }
                            OutlinedButton(onClick = { scope.launch { s.trips.record(id)?.let { share("riderange-trip-$id.csv", "text/csv", TripExport.csv(it)) } } }) { Text("CSV") }
                            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = RideColors.Red) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this trip?") },
        text = { Text("Its samples are removed from the phone. Copies already uploaded to GitHub stay there.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.tripDetail.value = null; scope.launch { s.trips.delete(id); s.trips.refit() } }) { Text("Delete", color = RideColors.Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
