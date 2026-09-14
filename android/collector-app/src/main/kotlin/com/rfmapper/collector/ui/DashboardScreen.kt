package com.rfmapper.collector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.collector.ui.components.Counter
import com.rfmapper.collector.ui.components.KeyValue
import com.rfmapper.collector.ui.components.SectionCard
import com.rfmapper.collector.ui.components.StatusRow
import com.rfmapper.collector.ui.components.formatCount
import com.rfmapper.collector.ui.components.formatDuration
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.CollectionStatus
import com.rfmapper.core.radio.Degradation
import com.rfmapper.core.radio.ScanProfile
import kotlinx.coroutines.delay

/**
 * The live session view.
 *
 * Its job is to answer one question in under a second: *is this session actually collecting?* That
 * is why the last-observation age is given as much prominence as the total count. A frozen total is
 * ambiguous — a quiet corridor looks the same as a dead scan loop — but an age that keeps climbing
 * is unambiguous, and it is the earliest signal an operator can act on while still on site.
 */
@Composable
fun DashboardScreen(
    viewModel: CollectorViewModel,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val total by viewModel.totalObservations.collectAsStateWithLifecycle()
    val sessions by viewModel.recentSessions.collectAsStateWithLifecycle()

    // A one-second tick so elapsed time and observation age advance even while nothing is written.
    // Without it a stalled session would appear merely quiet.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SessionCard(
                status = status,
                nowMillis = now,
                configured = settings.isConfigured,
                onStart = viewModel::startCollection,
                onStop = viewModel::stopCollection,
                onConfigure = onOpenOnboarding,
            )
        }

        item { HealthCard(status = status, nowMillis = now) }

        item {
            SectionCard(
                title = "Sensors",
                subtitle = "Resolved from this device now, not assumed from the API level",
            ) {
                for (sensor in ObserverCapability.entries) {
                    val capability = capabilities[sensor]
                    StatusRow(
                        label = sensor.label(),
                        detail = capability.describe(),
                        tint = capability.tint(),
                    )
                }
            }
        }

        item {
            SectionCard(title = "Scan profile", subtitle = settings.scanProfile.explain()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (profile in ScanProfile.entries) {
                        FilterChip(
                            selected = profile == settings.scanProfile,
                            onClick = { viewModel.setScanProfile(profile) },
                            // Changing the cadence mid-session would make the session's recorded
                            // profile a lie about part of its own data.
                            enabled = !status.isRunning,
                            label = { Text(profile.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Observer", subtitle = "Travels in observer.json with every package") {
                KeyValue("Observer id", settings.observerId ?: "not set")
                KeyValue("Name", settings.friendlyName.ifBlank { "—" })
                KeyValue("Building", settings.buildingId ?: "—")
                KeyValue("Default zone", settings.defaultZoneId ?: "—")
                KeyValue("Rows in database", formatCount(total))
            }
        }

        item { Text("Recent sessions", style = MaterialTheme.typography.titleMedium) }

        items(sessions, key = { it.sessionId }) { session ->
            SectionCard(
                title = session.startedAtUtc,
                subtitle = "${session.scanProfile} · ${session.sessionId.take(8)}",
            ) {
                KeyValue("Observations", formatCount(session.observationCount))
                KeyValue(
                    "Wi-Fi / BLE / RTT",
                    "${session.wifiCount} / ${session.bleCount} / ${session.rttCount}",
                )
                if (session.droppedSamples > 0) {
                    KeyValue("Dropped", formatCount(session.droppedSamples))
                }
                if (session.suspectedServiceKill) {
                    KeyValue("Warning", "suspected service interruption")
                }
                KeyValue("Ended", session.endedAtUtc ?: "still open")
                OutlinedButton(onClick = { viewModel.exportSession(session.sessionId) }) {
                    Text("Export session")
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    status: CollectionStatus,
    nowMillis: Long,
    configured: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConfigure: () -> Unit,
) {
    SectionCard(
        title = if (status.isRunning) "Session running" else "Idle",
        subtitle = status.sessionId?.let { "Session ${it.take(8)}" }
            ?: "No session. Nothing is being recorded.",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Counter("total", formatCount(status.counts.total))
            Counter("wi-fi", formatCount(status.counts.wifi))
            Counter("ble", formatCount(status.counts.ble))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Counter("elapsed", formatDuration(status.elapsedMillis(nowMillis)))
            Counter("last obs", formatDuration(status.lastObservationAgeMillis(nowMillis)))
            Counter("distinct", formatCount(status.counts.distinctIdentifiers.toLong()))
        }

        if (!configured) {
            Text(
                "An observer id is required before collecting: an observation that cannot be " +
                    "attributed cannot be calibrated or fused.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) {
                Text("Set up this observer")
            }
        } else if (status.isRunning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Stop session")
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start session")
            }
        }
    }
}

/**
 * The diagnostics that explain thin data.
 *
 * Shown always rather than only when something is wrong: an operator who has learned where to look
 * checks the same place every time, and a panel that appears only on failure is a panel nobody has
 * practised reading.
 */
@Composable
private fun HealthCard(status: CollectionStatus, nowMillis: Long) {
    val age = status.lastObservationAgeMillis(nowMillis)
    SectionCard(title = "Session health") {
        StatusRow(
            label = "Data flow",
            detail = when {
                !status.isRunning -> "no session"
                age == null -> "waiting for the first observation"
                age > STALE_MILLIS -> "nothing written for ${formatDuration(age)}"
                else -> "last observation ${formatDuration(age)} ago"
            },
            tint = when {
                !status.isRunning -> Color(0xFF9E9E9E)
                age == null || age > STALE_MILLIS -> MaterialTheme.colorScheme.error
                else -> Color(0xFF2E7D32)
            },
        )

        val throttle = status.throttle
        StatusRow(
            label = "Wi-Fi scan quota",
            detail = if (throttle == null) {
                "not yet measured"
            } else {
                "${throttle.requestsInWindow}/${throttle.maxRequestsInWindow} in window, " +
                    "${throttle.rejectedRequests} refused"
            },
            tint = if (throttle?.isThrottled == true) {
                MaterialTheme.colorScheme.tertiary
            } else {
                Color(0xFF2E7D32)
            },
        )

        StatusRow(
            label = "Buffered writes",
            detail = "${status.pendingWrites} pending, ${status.counts.dropped} dropped, " +
                "${status.counts.rejected} rejected",
            tint = if (status.counts.dropped > 0) {
                MaterialTheme.colorScheme.tertiary
            } else {
                Color(0xFF2E7D32)
            },
        )

        if (status.suspectedGap) {
            StatusRow(
                label = "Suspected service interruption",
                detail = "The session is running but nothing has been written for a long time. " +
                    "A vendor battery manager may have suspended the service.",
                tint = MaterialTheme.colorScheme.error,
            )
        }

        if (status.degradation.isDegraded) {
            StatusRow(
                label = "Degraded collection",
                detail = buildString {
                    if (status.degradation.missing.isNotEmpty()) {
                        append("missing ${status.degradation.missing.sorted().joinToString(", ")}")
                    }
                    if (status.degradation.radiosOff.isNotEmpty()) {
                        if (isNotEmpty()) append("; ")
                        append("off: ${status.degradation.radiosOff.sorted().joinToString(", ")}")
                    }
                },
                tint = MaterialTheme.colorScheme.error,
            )
        }

        if (status.isSurveying) {
            StatusRow(
                label = "Survey capture active",
                detail = "Rows are being tagged GROUND_TRUTH at ${status.surveyPointId}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val STALE_MILLIS = 5 * 60 * 1000L

private fun ObserverCapability.label() = when (this) {
    ObserverCapability.WIFI_SCAN -> "Wi-Fi scanning"
    ObserverCapability.WIFI_ASSOCIATION -> "Wi-Fi association"
    ObserverCapability.BLE -> "Bluetooth LE"
    ObserverCapability.RTT -> "Wi-Fi RTT ranging"
    ObserverCapability.GPS -> "GNSS"
}

@Composable
private fun Capability?.tint(): Color = when {
    this == null -> Color(0xFF9E9E9E)
    live && degradations.isEmpty() -> Color(0xFF2E7D32)
    live -> MaterialTheme.colorScheme.tertiary
    !supported -> Color(0xFF9E9E9E)
    else -> MaterialTheme.colorScheme.error
}

/**
 * Turns a capability into a sentence an operator can act on.
 *
 * The three unavailable cases read differently because they need different remedies, and collapsing
 * them into "unavailable" would hide the only actionable part of the message.
 */
private fun Capability?.describe(): String {
    if (this == null) return "not yet checked"
    if (!supported) return "this device has no such radio — recorded as unsupported"

    val notes = buildList {
        if (!permitted) {
            add("permission needed: ${missingPermissions.joinToString(", ") { it.substringAfterLast('.') }}")
        }
        if (Degradation.RADIO_OFF in degradations) add("radio switched off")
        if (Degradation.LOCATION_SERVICES_OFF in degradations) {
            add("device location is off — scans return empty with no error")
        }
        if (Degradation.BACKGROUND_PERMISSION_DENIED in degradations) {
            add("foreground only; expect gaps once the screen locks")
        }
        if (Degradation.THROTTLED in degradations) add("rate-limited by the platform")
    }

    return if (notes.isEmpty()) "collecting" else notes.joinToString("; ")
}

private fun ScanProfile.explain(): String = when (this) {
    ScanProfile.AGGRESSIVE ->
        "Highest rate the platform permits. Bench tests, walk tests and surveys."
    ScanProfile.BALANCED ->
        "A full working day on a mid-range phone with the screen off."
    ScanProfile.ENDURANCE ->
        "Multi-day unattended deployment. RTT is off and Wi-Fi is sparse."
}
