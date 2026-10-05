package com.elect.riderange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elect.riderange.trips.Sample
import com.elect.riderange.ui.theme.RideColors
import kotlin.math.max

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = RideColors.Panel, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
fun Dot(color: Color, size: Int = 12) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun LegendRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Colours for a trip track: speed (blue→green→amber→red) or power (green regen → red high draw). */
object TripColors {
    private val ramp = listOf("#2F80ED", "#16B888", "#F2C94C", "#F2994A", "#EB5757")

    fun colors(samples: List<Sample>, byPower: Boolean): List<String> {
        if (byPower) {
            val maxP = max(200.0, samples.mapNotNull { it.powerW }.maxOrNull() ?: 0.0)
            return samples.map { s ->
                val p = s.powerW ?: return@map "#8A99A6"
                if (p < 0) "#00C2A8" else ramp[((p / maxP) * (ramp.size - 1)).toInt().coerceIn(0, ramp.size - 1)]
            }
        }
        val maxV = max(5.0, samples.mapNotNull { it.speed }.maxOrNull() ?: 0.0)
        return samples.map { s -> ramp[(((s.speed ?: 0.0) / maxV) * (ramp.size - 1)).toInt().coerceIn(0, ramp.size - 1)] }
    }
}
