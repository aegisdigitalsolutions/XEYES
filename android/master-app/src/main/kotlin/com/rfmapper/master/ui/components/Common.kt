package com.rfmapper.master.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rfmapper.core.model.PrecisionTier
import com.rfmapper.master.ui.theme.MonoNumber
import com.rfmapper.master.ui.theme.MonoSmall
import com.rfmapper.master.ui.theme.TierColours

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(value, style = MonoSmall)
    }
}

@Composable
fun Counter(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MonoNumber, textAlign = TextAlign.Center)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Dot(tint: Color, modifier: Modifier = Modifier) =
    Surface(color = tint, shape = CircleShape, modifier = modifier.size(10.dp)) {}

@Composable
fun Chip(text: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        color = tint.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * A precision tier, always rendered with its name beside its colour.
 *
 * The label is not optional. The difference between "somewhere in this zone" and "at this point,
 * ±4 m" is the single most consequential distinction in the whole interface, and encoding it in
 * hue alone would hide it from a colour-blind administrator.
 */
@Composable
fun TierChip(tier: PrecisionTier, modifier: Modifier = Modifier) =
    Chip(tier.label, tierColour(tier), modifier)

fun tierColour(tier: PrecisionTier): Color = when (tier) {
    PrecisionTier.SITE_PRESENCE -> TierColours.sitePresence
    PrecisionTier.BUILDING -> TierColours.building
    PrecisionTier.ZONE -> TierColours.zone
    PrecisionTier.APPROXIMATE_POSITION -> TierColours.approximate
    PrecisionTier.PRECISION_RANGE -> TierColours.precision
}

val PrecisionTier.label: String
    get() = when (this) {
        PrecisionTier.SITE_PRESENCE -> "On site"
        PrecisionTier.BUILDING -> "Building"
        PrecisionTier.ZONE -> "Zone"
        PrecisionTier.APPROXIMATE_POSITION -> "Approximate"
        PrecisionTier.PRECISION_RANGE -> "Ranged"
    }

/**
 * A position, phrased as the evidence supports it.
 *
 * A coordinate is never shown without its uncertainty, because "12.4, 9.1" reads as a fact while
 * "12.4, 9.1 ±6 m" reads as a measurement, and only the second one is true.
 */
fun describePosition(
    tier: PrecisionTier,
    zoneName: String?,
    buildingName: String?,
    x: Double?,
    y: Double?,
    uncertaintyM: Double?,
): String = when {
    x != null && y != null && uncertaintyM != null ->
        "%.1f, %.1f  ±%.1f m".format(x, y, uncertaintyM)
    tier == PrecisionTier.ZONE && zoneName != null -> "in $zoneName"
    tier == PrecisionTier.BUILDING && buildingName != null -> "in $buildingName"
    tier == PrecisionTier.SITE_PRESENCE -> "on site, zone unknown"
    else -> zoneName ?: buildingName ?: "position unknown"
}

fun formatCount(value: Long): String =
    if (value < 10_000) value.toString() else "%,d".format(value).replace(',', ' ')

fun formatCount(value: Int): String = formatCount(value.toLong())

fun formatPercent(fraction: Double): String = "%.0f%%".format(fraction * 100)
