package com.rfmapper.master.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF16202A)
private val Chart = Color(0xFF2A6F97)
private val ChartLight = Color(0xFF8FC7E8)
private val Caution = Color(0xFF9A6700)
private val Alert = Color(0xFFB3261E)

/**
 * A fixed palette, unlike the Collector's dynamic colour.
 *
 * Confidence and precision tier are rendered as colour throughout this app, and a scheme that
 * changes with the user's wallpaper would make "amber means low confidence" mean something
 * different on every device an administrator picks up.
 */
private val LightScheme = lightColorScheme(
    primary = Chart,
    onPrimary = Color.White,
    secondary = Ink,
    tertiary = Caution,
    error = Alert,
)

private val DarkScheme = darkColorScheme(
    primary = ChartLight,
    onPrimary = Ink,
    secondary = Color(0xFFB8C4CF),
    tertiary = Color(0xFFE8B84B),
)

private val MasterTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

val MonoNumber = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 24.sp,
)

/** Tier colours. Deliberately cooler as the claim gets stronger, so a bold claim never looks safe. */
object TierColours {
    val sitePresence = Color(0xFF8C9BA5)
    val building = Color(0xFF6C8EA4)
    val zone = Color(0xFF2A6F97)
    val approximate = Color(0xFF9A6700)
    val precision = Color(0xFF7A3E9D)
}

@Composable
fun MasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = MasterTypography,
        content = content,
    )
}
