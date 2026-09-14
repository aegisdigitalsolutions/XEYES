package com.rfmapper.master.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.core.model.Point
import com.rfmapper.core.model.PositionEstimate
import com.rfmapper.core.model.Zone
import com.rfmapper.data.room.reference.FingerprintEntity
import com.rfmapper.data.room.reference.InfrastructureNodeEntity
import com.rfmapper.master.ui.components.Chip
import com.rfmapper.master.ui.components.KeyValue
import com.rfmapper.master.ui.components.SectionCard
import com.rfmapper.master.ui.components.tierColour
import kotlin.math.max
import kotlin.math.min

/**
 * The site as geometry: zones, anchors, survey points and wherever the active generation says
 * devices are.
 *
 * Estimates are drawn as uncertainty discs, never as dots. A dot asserts a point; a disc sized by
 * `horizontal_uncertainty_m` shows what the evidence actually supports, and a zone-only estimate
 * appears as a shaded zone rather than as a false centre.
 */
@Composable
fun SiteScreen(viewModel: MasterViewModel, modifier: Modifier = Modifier) {
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val nodes by viewModel.infrastructure.collectAsStateWithLifecycle()
    val estimates by viewModel.latestEstimates.collectAsStateWithLifecycle()
    val occupancy by viewModel.zoneOccupancy.collectAsStateWithLifecycle()
    val fingerprints by viewModel.fingerprints.collectAsStateWithLifecycle()
    val activeVersion by viewModel.activeVersion.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                "Site map",
                subtitle = activeVersion?.let { "Estimates from $it" } ?: "No derived generation imported",
            ) {
                if (zones.isEmpty()) {
                    Text(
                        "No site model yet. Import one from the Import tab to see geometry here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SiteMap(
                        zones = zones,
                        nodes = nodes,
                        estimates = estimates,
                        occupiedZoneIds = occupancy.associate { it.zoneId to it.deviceCount },
                    )
                    Text(
                        "Discs show horizontal uncertainty at true scale. A shaded zone with no " +
                            "disc is a zone-level estimate: the honest answer when the evidence " +
                            "cannot place a point.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (occupancy.isNotEmpty()) {
            item {
                SectionCard("Occupancy", subtitle = "Distinct devices by their newest estimate") {
                    occupancy.forEach { row ->
                        KeyValue(
                            zones.firstOrNull { it.zoneId == row.zoneId }?.name ?: row.zoneId,
                            "${row.deviceCount} device(s), mean confidence %.2f".format(row.meanConfidence),
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Calibration coverage",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item { CoverageCard(zones, fingerprints) }

        items(fingerprints, key = { it.fingerprintId }) { fingerprint ->
            FingerprintCard(
                fingerprint = fingerprint,
                zoneName = zones.firstOrNull { it.zoneId == fingerprint.zoneId }?.name,
                onPromote = { viewModel.promoteFingerprint(fingerprint.fingerprintId) },
                onRetire = { viewModel.retireFingerprint(fingerprint.fingerprintId) },
            )
        }
    }
}

@Composable
private fun CoverageCard(zones: List<Zone>, fingerprints: List<FingerprintEntity>) {
    val promoted = fingerprints.filter { it.status == "GROUND_TRUTH" }
    val covered = promoted.mapTo(HashSet()) { it.zoneId }
    val uncovered = zones.filterNot { it.zoneId in covered }

    SectionCard("Ground truth", subtitle = "${promoted.size} promoted fingerprints") {
        KeyValue("Zones covered", "${covered.size} of ${zones.size}")
        KeyValue("Candidates awaiting review", fingerprints.count { it.status == "CANDIDATE" }.toString())

        /*
         * Naming the uncovered zones rather than reporting a percentage: "82% covered" invites
         * the reader to feel finished, while a list of rooms nobody has surveyed is an
         * actionable instruction.
         */
        if (uncovered.isNotEmpty()) {
            Text(
                "Not yet surveyed: " + uncovered.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun FingerprintCard(
    fingerprint: FingerprintEntity,
    zoneName: String?,
    onPromote: () -> Unit,
    onRetire: () -> Unit,
) {
    val tint = when (fingerprint.status) {
        "GROUND_TRUTH" -> MaterialTheme.colorScheme.primary
        "RETIRED" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }

    SectionCard(zoneName ?: fingerprint.zoneId, subtitle = fingerprint.surveyPointId) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(fingerprint.status, tint)
            Chip("${fingerprint.sampleCount} samples", MaterialTheme.colorScheme.secondary)
            fingerprint.sessionCount?.let { Chip("$it sessions", MaterialTheme.colorScheme.secondary) }
        }
        fingerprint.observerId?.let { KeyValue("Surveyed by", it) }
        fingerprint.promotedBy?.let { KeyValue("Promoted by", it) }

        // Repeat visits on different days are what separate a fingerprint from a snapshot of one
        // afternoon's RF conditions.
        val sessions = fingerprint.sessionCount ?: 0
        if (fingerprint.status == "CANDIDATE" && sessions < MIN_SESSIONS) {
            Text(
                "Only $sessions survey session(s). $MIN_SESSIONS separate visits are recommended " +
                    "before this becomes ground truth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fingerprint.status == "CANDIDATE") {
                TextButton(onClick = onPromote) { Text("Promote to ground truth") }
            }
            if (fingerprint.status != "RETIRED") {
                TextButton(onClick = onRetire) { Text("Retire") }
            }
        }
    }
}

@Composable
private fun SiteMap(
    zones: List<Zone>,
    nodes: List<InfrastructureNodeEntity>,
    estimates: List<PositionEstimate>,
    occupiedZoneIds: Map<String, Int>,
) {
    val outline = MaterialTheme.colorScheme.outline
    val occupiedTint = MaterialTheme.colorScheme.primary
    val anchorTint = MaterialTheme.colorScheme.secondary

    val bounds = remember(zones, nodes, estimates) { computeBounds(zones, nodes, estimates) }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.35f)
            .padding(vertical = 4.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.35f)) {
            val projector = Projector(bounds, size)

            for (zone in zones) {
                val polygon = zone.polygon
                val occupancy = occupiedZoneIds[zone.zoneId] ?: 0
                if (polygon.size >= 3) {
                    val path = Path().apply {
                        polygon.forEachIndexed { index, point ->
                            val offset = projector.project(point.x, point.y)
                            if (index == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y)
                        }
                        close()
                    }
                    if (occupancy > 0) {
                        drawPath(path, occupiedTint.copy(alpha = 0.18f + 0.1f * min(occupancy, 5)))
                    }
                    drawPath(path, outline, style = Stroke(width = 2f))
                } else if (zone.centroidX != null && zone.centroidY != null && occupancy > 0) {
                    // A zone with no polygon still has to be visible when something is in it,
                    // otherwise the map silently omits a positive result.
                    drawCircle(
                        color = occupiedTint.copy(alpha = 0.25f),
                        radius = projector.scaleLength(zone.enclosingRadiusM ?: 5.0),
                        center = projector.project(zone.centroidX!!, zone.centroidY!!),
                    )
                }
            }

            for (node in nodes) {
                val x = node.x ?: continue
                val y = node.y ?: continue
                val centre = projector.project(x, y)
                drawCircle(anchorTint, radius = 4f, center = centre)
                if (node.rttCapable) {
                    drawCircle(anchorTint, radius = 8f, center = centre, style = Stroke(width = 1.5f))
                }
            }

            for (estimate in estimates) {
                val tint = tierColour(estimate.precisionTier)
                val x = estimate.x
                val y = estimate.y
                val uncertainty = estimate.horizontalUncertaintyM
                if (x != null && y != null && uncertainty != null) {
                    val centre = projector.project(x, y)
                    drawCircle(tint.copy(alpha = 0.22f), projector.scaleLength(uncertainty), centre)
                    drawCircle(tint, radius = 3.5f, center = centre)
                } else {
                    val zone = zones.firstOrNull { it.zoneId == estimate.zoneId } ?: continue
                    val cx = zone.centroidX ?: continue
                    val cy = zone.centroidY ?: continue
                    drawZoneOnlyMarker(projector, cx, cy, zone.enclosingRadiusM, tint)
                }
            }
        }
    }
}

/**
 * A zone-level estimate, drawn as a dashed ring over the whole zone.
 *
 * Dashed rather than solid, and sized by the zone's own enclosing radius, so it cannot be mistaken
 * for a measured position. This is the shape the specification calls the preferred result.
 */
private fun DrawScope.drawZoneOnlyMarker(
    projector: Projector,
    x: Double,
    y: Double,
    enclosingRadiusM: Double?,
    tint: Color,
) {
    val centre = projector.project(x, y)
    val radius = projector.scaleLength(enclosingRadiusM ?: DEFAULT_ZONE_RADIUS_M)
    drawCircle(tint.copy(alpha = 0.14f), radius, centre)
    drawCircle(
        tint,
        radius,
        centre,
        style = Stroke(
            width = 1.5f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        ),
    )
}

private data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = (maxX - minX).coerceAtLeast(1.0)
    val height: Double get() = (maxY - minY).coerceAtLeast(1.0)
}

/**
 * Site metres to canvas pixels, with one scale for both axes.
 *
 * Independent axis scales would stretch the frame and make an uncertainty disc an ellipse, which
 * would misstate the one quantity the map exists to communicate honestly.
 */
private class Projector(private val bounds: Bounds, size: Size) {
    private val scale = min(
        (size.width - 2 * PADDING_PX) / bounds.width,
        (size.height - 2 * PADDING_PX) / bounds.height,
    ).toFloat()

    private val offsetX = PADDING_PX + (size.width - 2 * PADDING_PX - bounds.width * scale) / 2
    private val offsetY = PADDING_PX + (size.height - 2 * PADDING_PX - bounds.height * scale) / 2

    fun project(x: Double, y: Double) = Offset(
        x = (offsetX + (x - bounds.minX) * scale).toFloat(),
        // Site y grows north; canvas y grows down.
        y = (offsetY + (bounds.maxY - y) * scale).toFloat(),
    )

    fun scaleLength(metres: Double): Float = (metres * scale).toFloat().coerceAtLeast(2f)
}

private fun computeBounds(
    zones: List<Zone>,
    nodes: List<InfrastructureNodeEntity>,
    estimates: List<PositionEstimate>,
): Bounds {
    val points = buildList {
        zones.forEach { zone ->
            addAll(zone.polygon)
            if (zone.centroidX != null && zone.centroidY != null) {
                add(Point(zone.centroidX!!, zone.centroidY!!))
            }
        }
        nodes.forEach { node ->
            val x = node.x
            val y = node.y
            if (x != null && y != null) add(Point(x, y))
        }
        estimates.forEach { estimate ->
            val x = estimate.x
            val y = estimate.y
            if (x != null && y != null) add(Point(x, y))
        }
    }
    if (points.isEmpty()) return Bounds(0.0, 0.0, 10.0, 10.0)

    val margin = 2.0
    return Bounds(
        minX = points.minOf { it.x } - margin,
        minY = points.minOf { it.y } - margin,
        maxX = points.maxOf { it.x } + margin,
        maxY = points.maxOf { it.y } + margin,
    ).let { Bounds(it.minX, it.minY, max(it.maxX, it.minX + 1), max(it.maxY, it.minY + 1)) }
}

private const val PADDING_PX = 16f
private const val DEFAULT_ZONE_RADIUS_M = 6.0
private const val MIN_SESSIONS = 3
