package com.rfmapper.collector.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Slate = Color(0xFF1F2933)
private val Signal = Color(0xFF0B6E99)
private val SignalLight = Color(0xFF7FD3F7)
private val Amber = Color(0xFFB26B00)
private val Alert = Color(0xFFB3261E)

private val LightScheme = lightColorScheme(
    primary = Signal,
    onPrimary = Color.White,
    secondary = Slate,
    tertiary = Amber,
    error = Alert,
)

private val DarkScheme = darkColorScheme(
    primary = SignalLight,
    onPrimary = Slate,
    secondary = Color(0xFFB8C4CF),
    tertiary = Color(0xFFF5C26B),
)

/**
 * Counters are rendered in a monospace face throughout.
 *
 * Not decoration: the dashboard's numbers are watched for movement, and in a proportional face the
 * digits change width as they tick, so a rising count jitters horizontally and is measurably harder
 * to read at a glance.
 */
private val CollectorTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoNumber = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 28.sp,
)

val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
)

@Composable
fun RFMapperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = scheme, typography = CollectorTypography, content = content)
}
