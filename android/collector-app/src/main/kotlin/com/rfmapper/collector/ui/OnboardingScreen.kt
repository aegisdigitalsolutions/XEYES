package com.rfmapper.collector.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.collector.ui.components.KeyValue
import com.rfmapper.collector.ui.components.SectionCard
import com.rfmapper.collector.ui.components.StatusRow
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.radio.android.RadioPermissions

/**
 * The sequenced permission flow from deliverable E, plus observer identity and the export folder.
 *
 * The sequence is not cosmetic. Three ordering rules are enforced because getting them wrong
 * produces *silence* rather than an error:
 *
 *  1. Foreground location is requested on its own. Bundling `ACCESS_BACKGROUND_LOCATION` into the
 *     same request makes some Android versions deny the whole thing.
 *  2. Background location is a separate, later step, and from API 30 it is a trip to Settings
 *     rather than a dialog.
 *  3. The flow ends with a live capability matrix. The most expensive failure in this system is
 *     discovering after a six-hour session that Bluetooth was switched off the whole time, and only
 *     a matrix read from the device at that moment can prevent it.
 */
@Composable
fun OnboardingScreen(viewModel: CollectorViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()

    var observerId by rememberSaveable { mutableStateOf("") }
    var friendlyName by rememberSaveable { mutableStateOf("") }
    var buildingId by rememberSaveable { mutableStateOf("") }
    var zoneId by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }

    // Prefills once from stored settings, without clobbering in-progress typing on recomposition.
    val prefilled = remember { mutableStateOf(false) }
    if (!prefilled.value && settings.observerId != null) {
        observerId = settings.observerId.orEmpty()
        friendlyName = settings.friendlyName
        buildingId = settings.buildingId.orEmpty()
        zoneId = settings.defaultZoneId.orEmpty()
        notes = settings.notes.orEmpty()
        prefilled.value = true
    }

    val foregroundPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshCapabilities() }

    val backgroundPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshCapabilities() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // The grant has to be persisted explicitly or it expires with the process, and a
            // forgotten folder means an export that fails at the end of a field session.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setExportFolder(uri.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "1 · Observer identity",
            subtitle = "Every observation is attributed to this id. It cannot be inferred later.",
        ) {
            OutlinedTextField(
                value = observerId,
                onValueChange = { observerId = it },
                label = { Text("Observer id (e.g. OBS-04)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = friendlyName,
                onValueChange = { friendlyName = it },
                label = { Text("Friendly name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = buildingId,
                onValueChange = { buildingId = it },
                label = { Text("Building id (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = zoneId,
                onValueChange = { zoneId = it },
                label = { Text("Default zone id (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    viewModel.saveIdentity(observerId, friendlyName, buildingId, zoneId, notes)
                },
                enabled = observerId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save identity")
            }
            if (settings.installationId.isNotBlank()) {
                KeyValue("Installation id", settings.installationId.take(8))
            }
        }

        SectionCard(
            title = "2 · Foreground permissions",
            subtitle = "Location, nearby devices and notifications. Requested together, without " +
                "background location — bundling it can cause the whole request to be denied.",
        ) {
            Text(
                RadioPermissions.foregroundOnboardingPermissions()
                    .joinToString("\n") { "· " + it.substringAfterLast('.') },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    foregroundPermissions.launch(
                        RadioPermissions.foregroundOnboardingPermissions().toTypedArray(),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant foreground permissions")
            }
        }

        SectionCard(
            title = "3 · Background location",
            subtitle = "Optional, and a separate request. Without it the session collects only " +
                "while the app is in the foreground, and a gap will appear when the screen locks.",
        ) {
            val background = RadioPermissions.backgroundLocationPermission()
            if (background == null) {
                Text(
                    "Not applicable on this Android version.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                OutlinedButton(
                    onClick = {
                        if (RadioPermissions.backgroundLocationRequiresSettings) {
                            // From API 30 the system shows no dialog for this; the only route is
                            // the app's own settings page.
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        } else {
                            backgroundPermission.launch(background)
                        }
                    },
                    enabled = RadioPermissions.isGranted(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (RadioPermissions.backgroundLocationRequiresSettings) {
                            "Open settings to allow all the time"
                        } else {
                            "Allow all the time"
                        },
                    )
                }
                if (!RadioPermissions.isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    Text(
                        "Grant foreground location first — the system will not offer this until then.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(
            title = "4 · Battery exemption",
            subtitle = "Recommended for sessions over an hour. Several vendor battery managers " +
                "suspend a non-exempt foreground service, which produces exactly the kind of " +
                "multi-hour gap the session summary has to flag.",
        ) {
            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open battery optimisation settings")
            }
        }

        SectionCard(
            title = "5 · Export folder",
            subtitle = "Export is manual and file-based by design. Choose a folder once; the " +
                "grant is persisted across reboots.",
        ) {
            KeyValue("Current", settings.exportTreeUri?.let { Uri.parse(it).lastPathSegment ?: it } ?: "not set")
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose export folder")
            }
        }

        SectionCard(
            title = "6 · Capability matrix",
            subtitle = "Read from this device now. This is the check that prevents discovering at " +
                "the end of a long session that a radio was off the whole time.",
        ) {
            for (sensor in ObserverCapability.entries) {
                val capability = capabilities[sensor]
                StatusRow(
                    label = sensor.name,
                    detail = when {
                        capability == null -> "not checked"
                        !capability.supported -> "unsupported hardware — declared in observer.json"
                        capability.live && capability.degradations.isEmpty() -> "ready"
                        capability.live -> "ready, with limitations"
                        else -> "blocked: " + capability.degradations.joinToString(", ") { it.name }
                    },
                    tint = when {
                        capability == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        !capability.supported -> MaterialTheme.colorScheme.onSurfaceVariant
                        capability.live -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
            OutlinedButton(
                onClick = viewModel::refreshCapabilities,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Re-check")
            }
        }
    }
}
