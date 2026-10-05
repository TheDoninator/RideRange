package com.elect.riderange.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object RideColors {
    val OneWay = Color(0xFF4FA3FF)
    val RoundTrip = Color(0xFF22C58B)
    val Amber = Color(0xFFFFB020)
    val Red = Color(0xFFFF5A5F)
    val Panel = Color(0xF0101B24)
    val PanelSolid = Color(0xFF101B24)
    val Route = Color(0xFF9B6BFF)

    fun battery(pct: Double): Color = when {
        pct < 20 -> Red
        pct < 40 -> Amber
        else -> RoundTrip
    }
}

/** Dark, high-contrast ride UI (dynamic colour off so the map overlays stay readable). */
private val Scheme = darkColorScheme(
    primary = Color(0xFF4FA3FF),
    onPrimary = Color(0xFF00172E),
    secondary = Color(0xFF22C58B),
    onSecondary = Color(0xFF002417),
    tertiary = Color(0xFFFFB020),
    background = Color(0xFF0E1A24),
    surface = Color(0xFF101B24),
    surfaceVariant = Color(0xFF1C2A36),
    surfaceContainer = Color(0xFF15222D),
    surfaceContainerHigh = Color(0xFF1C2A36),
    onSurface = Color(0xFFF2F6FA),
    onSurfaceVariant = Color(0xFFB8C6D2),
    error = Color(0xFFFF5A5F),
    outline = Color(0xFF3A4B59),
)

private val Base = Typography()
private val Type = Base.copy(
    displayLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 72.sp, lineHeight = 72.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 48.sp, lineHeight = 50.sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 17.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
