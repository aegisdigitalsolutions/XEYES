package com.rfmapper.data.room

import com.rfmapper.core.model.DeviceRegistry
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SchemaVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes the managed-device registry as one `devices.json` document.
 *
 * This is the file that makes the Lab's attribution step possible. Attribution is registry-driven
 * (`docs/17` §4): the Lab decides a row belongs to a managed device by looking the row's radio
 * identifier up in this registry, and it has no other mechanism — no identifier-similarity
 * heuristic, no co-observation clustering. A Lab run without this file therefore does not degrade
 * gracefully; it attributes nothing and emits no position estimates at all.
 *
 * Kept separate from `site_model.json` deliberately. The two documents change for unrelated reasons
 * — geometry when a wall moves, the registry when somebody is issued a phone — and merging them
 * would force a new `reference_model_id` on every enrollment, invalidating the citation that every
 * historical estimate carries.
 *
 * @see <a href="../../../../../../../../docs/17-identity-and-attribution-policy.md">docs/17</a>
 */
class DeviceRegistryIo(
    private val reference: ReferenceRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Preview(
        val registry: DeviceRegistry?,
        val parseError: String?,
        val issues: List<String>,
        val newDeviceIds: List<String>,
        val updatedDeviceIds: List<String>,
    ) {
        /**
         * A registry with a contested identifier is shown but not applied, for the same reason a
         * site model with dangling references is: applying it would leave attribution resolving to
         * whichever row happened to be written last, and nothing downstream could detect it.
         */
        val canApply: Boolean get() = registry != null && issues.isEmpty()
    }

    suspend fun preview(stream: InputStream): Preview = withContext(Dispatchers.IO) {
        val text = stream.use { it.readBytes().decodeToString() }
        val decoded = runCatching {
            RfMapperJson.compact.decodeFromString(DeviceRegistry.serializer(), text)
        }
        val parsed = decoded.getOrNull()
            ?: return@withContext Preview(
                registry = null,
                parseError = decoded.exceptionOrNull()?.message ?: "unreadable device registry",
                issues = emptyList(),
                newDeviceIds = emptyList(),
                updatedDeviceIds = emptyList(),
            )

        val issues = buildList {
            if (!SchemaVersion.isReadable(parsed.schemaVersion)) {
                add(
                    "schema_version ${parsed.schemaVersion} is not readable by this build " +
                        "(expects major ${SchemaVersion.SUPPORTED_MAJOR})",
                )
            }
            addAll(parsed.referentialIssues())
        }

        val known = reference.devices().mapTo(HashSet()) { it.deviceId }
        val incoming = parsed.managedDevices.map { it.deviceId }
        Preview(
            registry = parsed,
            parseError = null,
            issues = issues,
            newDeviceIds = incoming.filterNot { it in known }.sorted(),
            updatedDeviceIds = incoming.filter { it in known }.sorted(),
        )
    }

    /**
     * Applies a previewed registry.
     *
     * Upsert rather than replace, matching [SiteModelIo.apply]: a device the file omits is not
     * evidence that the device was retired, and deleting it would orphan every estimate already
     * attributed to it. Removing a device stays an explicit act.
     */
    suspend fun apply(registry: DeviceRegistry, addedBy: String?): AppliedCounts =
        withContext(Dispatchers.IO) {
            require(registry.referentialIssues().isEmpty()) {
                "refusing to apply a device registry with contested identifiers"
            }
            val known = reference.devices().mapTo(HashSet()) { it.deviceId }
            var created = 0
            var updated = 0
            val rejected = mutableListOf<String>()

            for (device in registry.managedDevices) {
                // Through the repository rather than the DAO, so an imported enrollment goes
                // through the same normalization and identifier-replacement path as one typed into
                // the Master by hand. Two ways to enroll a device would eventually disagree.
                rejected += reference.saveDevice(device, addedBy)
                if (device.deviceId in known) updated++ else created++
            }

            AppliedCounts(created = created, updated = updated, rejectedIdentifiers = rejected)
        }

    data class AppliedCounts(
        val created: Int,
        val updated: Int,
        val rejectedIdentifiers: List<String>,
    )

    /**
     * Snapshots the current registry.
     *
     * Every device regardless of status, not only the `AUTHORIZED` ones. A `BLOCKED` device is
     * still a device the Lab must be able to recognise: filtering it out here would make its rows
     * indistinguishable from environmental RF, which is the opposite of what blocking means.
     */
    suspend fun export(registryId: String, notes: String? = null): DeviceRegistry =
        withContext(Dispatchers.IO) {
            DeviceRegistry(
                registryId = registryId,
                createdAt = Iso8601.format(nowMillis()),
                notes = notes,
                managedDevices = reference.devices(),
            )
        }

    suspend fun writeTo(registry: DeviceRegistry, out: OutputStream) = withContext(Dispatchers.IO) {
        out.use {
            it.write(
                RfMapperJson.pretty
                    .encodeToString(DeviceRegistry.serializer(), registry)
                    .encodeToByteArray(),
            )
        }
    }
}
