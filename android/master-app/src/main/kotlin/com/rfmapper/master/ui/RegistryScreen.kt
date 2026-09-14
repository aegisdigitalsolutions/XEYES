package com.rfmapper.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfmapper.core.model.DeviceStatus
import com.rfmapper.core.model.ManagedDevice
import com.rfmapper.data.room.reference.ManagedDeviceEntity
import com.rfmapper.data.room.reference.ObserverEntity
import com.rfmapper.master.ui.components.Chip
import com.rfmapper.master.ui.components.KeyValue
import com.rfmapper.master.ui.components.SectionCard

/**
 * The two registries that decide what the system will say anything about.
 *
 * Managed devices are the entire authorized scope: an identifier not enrolled here is
 * environmental RF context and is never attributed to anyone. Enrolled observers are whose data
 * the Master will accept. Both are deliberately small, explicit, hand-curated lists — the closed
 * scope is the privacy design, not an implementation detail.
 */
@Composable
fun RegistryScreen(viewModel: MasterViewModel, modifier: Modifier = Modifier) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val observers by viewModel.observers.collectAsStateWithLifecycle()
    val draft by viewModel.deviceDraft.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                "Managed devices (${devices.size})",
                subtitle = "The complete authorized scope. Anything not listed here is environment.",
            ) {
                Text(
                    "Enrol by service UUID where you can. It is the only identifier class that " +
                        "survives MAC randomization and works on both platforms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = viewModel::beginNewDevice) { Text("Enrol a device") }
            }
        }

        items(devices, key = { it.deviceId }) { device ->
            DeviceCard(
                device = device,
                onEdit = { viewModel.beginEditDevice(device.deviceId) },
                onStatus = { viewModel.setDeviceStatus(device, it) },
                onDelete = { viewModel.deleteDevice(device.deviceId) },
            )
        }

        item {
            Text(
                "Observers (${observers.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(observers, key = { it.observerId }) { observer ->
            ObserverCard(observer) { viewModel.setObserverEnrolled(observer.observerId, it) }
        }
    }

    draft?.let { current ->
        DeviceEditor(
            initial = current,
            onDismiss = viewModel::cancelDeviceEdit,
            onSave = viewModel::saveDevice,
        )
    }
}

@Composable
private fun DeviceCard(
    device: ManagedDeviceEntity,
    onEdit: () -> Unit,
    onStatus: (DeviceStatus) -> Unit,
    onDelete: () -> Unit,
) {
    val status = DeviceStatus.entries.firstOrNull { it.name == device.status } ?: DeviceStatus.UNKNOWN
    val tint = when (status) {
        DeviceStatus.AUTHORIZED -> MaterialTheme.colorScheme.primary
        DeviceStatus.BLOCKED -> MaterialTheme.colorScheme.error
        DeviceStatus.INFRASTRUCTURE -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }

    SectionCard(device.friendlyName, subtitle = device.deviceId) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(status.name, tint)
            Chip(device.deviceType, MaterialTheme.colorScheme.secondary)
        }
        device.lastSeenUtc?.let { KeyValue("Last seen", it) }
            ?: KeyValue("Last seen", "never observed")
        device.notes?.takeIf { it.isNotBlank() }?.let { KeyValue("Notes", it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(
                onClick = {
                    onStatus(
                        if (status == DeviceStatus.BLOCKED) DeviceStatus.AUTHORIZED else DeviceStatus.BLOCKED,
                    )
                },
            ) { Text(if (status == DeviceStatus.BLOCKED) "Unblock" else "Block") }
            TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
private fun ObserverCard(observer: ObserverEntity, onEnrolled: (Boolean) -> Unit) {
    SectionCard(observer.friendlyName, subtitle = observer.observerId) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    if (observer.enrolled) "Enrolled" else "Not enrolled",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Packages from an unenrolled observer are refused",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = observer.enrolled, onCheckedChange = onEnrolled)
        }
        KeyValue("Platform", "${observer.platform} ${observer.osVersion.orEmpty()}".trim())
        observer.deviceModel?.let { KeyValue("Model", it) }
        KeyValue("Can see", observer.capabilities.joinToString().ifBlank { "nothing declared" })

        /*
         * Listing what an observer *cannot* see is as important as what it can. An iOS collector
         * reporting no Wi-Fi means "this observer cannot see Wi-Fi", not "no access points were
         * present", and conflating the two drives every fingerprint's visibility probability
         * toward zero.
         */
        if (observer.unsupported.isNotEmpty()) {
            KeyValue("Cannot see", observer.unsupported.joinToString())
        }
        if (observer.fixedObserver) {
            KeyValue("Fixed at", "%.1f, %.1f".format(observer.xCoordinate, observer.yCoordinate))
        }
    }
}

@Composable
private fun DeviceEditor(
    initial: MasterViewModel.DeviceDraft,
    onDismiss: () -> Unit,
    onSave: (MasterViewModel.DeviceDraft) -> Unit,
) {
    var deviceId by remember { mutableStateOf(initial.deviceId) }
    var name by remember { mutableStateOf(initial.friendlyName) }
    var type by remember { mutableStateOf(initial.deviceType) }
    var wifi by remember { mutableStateOf(initial.wifi.joinToString("\n")) }
    var ble by remember { mutableStateOf(initial.ble.joinToString("\n")) }
    var uuids by remember { mutableStateOf(initial.serviceUuids.joinToString("\n")) }
    var status by remember { mutableStateOf(initial.status) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isNew) "Enrol a device" else "Edit device") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        label = { Text("Device id") },
                        singleLine = true,
                        enabled = initial.isNew,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Friendly name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text("Device type") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeviceStatus.entries.filter { it != DeviceStatus.UNKNOWN }.forEach { candidate ->
                            FilterChip(
                                selected = status == candidate,
                                onClick = { status = candidate },
                                label = { Text(candidate.name.lowercase()) },
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = uuids,
                        onValueChange = { uuids = it },
                        label = { Text("Service UUIDs, one per line") },
                        supportingText = { Text("Preferred: survives MAC randomization") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = wifi,
                        onValueChange = { wifi = it },
                        label = { Text("Wi-Fi BSSIDs, one per line") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = ble,
                        onValueChange = { ble = it },
                        label = { Text("BLE addresses, one per line") },
                        supportingText = { Text("A randomized address will not attribute") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = deviceId.isNotBlank() && name.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            deviceId = deviceId.trim(),
                            friendlyName = name.trim(),
                            deviceType = type.trim(),
                            status = status,
                            wifi = wifi.lines().filter { it.isNotBlank() }.map { it.trim() },
                            ble = ble.lines().filter { it.isNotBlank() }.map { it.trim() },
                            serviceUuids = uuids.lines().filter { it.isNotBlank() }.map { it.trim() },
                            notes = notes.ifBlank { null },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
