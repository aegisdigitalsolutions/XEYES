package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed set of enrolled devices, exchanged as its own versioned document.
 *
 * Separate from [SiteModel] rather than folded into it, because the two change for unrelated
 * reasons and on unrelated schedules: geometry changes when a wall moves, the registry changes when
 * somebody is issued a phone. Versioning them together would force a new `reference_model_id` — and
 * therefore a reprocess of everything that cites it — every time a device was enrolled.
 *
 * The document is load-bearing for the Lab rather than informational. Attribution is registry-driven
 * by design (`docs/17` §4): an identifier that is not in this file attributes to nothing, and a
 * device that is not in this file gets no position estimate at all. A Lab run without it does not
 * produce worse results, it produces empty ones.
 */
@Serializable
data class DeviceRegistry(
    @SerialName("schema_version") val schemaVersion: String = SchemaVersion.CURRENT,

    /**
     * Stamps the snapshot so a derived run can say which registry it attributed against. Attribution
     * is the step that decides whether a row is a managed device or environmental context, so
     * "which registry was in force" is part of reproducing a result.
     */
    @SerialName("registry_id") val registryId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("notes") val notes: String? = null,
    @SerialName("managed_devices") val managedDevices: List<ManagedDevice> = emptyList(),
) {
    init {
        require(registryId.isNotBlank()) { "registry_id must not be blank" }
    }

    /**
     * Problems that would make this registry attribute incorrectly, as a list rather than an
     * exception, for the same reason [SiteModel.referentialIssues] is a list: a human fixing a
     * hand-edited file needs to see everything at once.
     *
     * A duplicated identifier is the serious one. `ref_device_identifier` maps one identifier to at
     * most one device, so a file claiming otherwise does not enroll two devices — it silently
     * resolves to whichever row was written last, and every subsequent estimate is attributed to
     * an arbitrary one of the two.
     */
    fun referentialIssues(): List<String> = buildList {
        duplicates(managedDevices.map { it.deviceId })
            .forEach { add("duplicate device_id '$it'") }

        val owners = LinkedHashMap<Pair<String, IdentifierType>, MutableList<String>>()
        for (device in managedDevices) {
            for (identifier in device.allIdentifiers) {
                owners.getOrPut(identifier) { mutableListOf() } += device.deviceId
            }
        }
        owners.filterValues { it.size > 1 }.forEach { (identifier, claimants) ->
            add(
                "identifier '${identifier.first}' (${identifier.second}) is claimed by " +
                    claimants.sorted().joinToString(", "),
            )
        }

        // An enrolled device with nothing to match on can never be observed. That is almost always
        // an authoring mistake, and it is invisible afterwards: the device simply never appears.
        managedDevices.filter { it.allIdentifiers.isEmpty() }
            .forEach { add("device '${it.deviceId}' has no usable identifier and can never be attributed") }
    }

    private fun duplicates(ids: List<String>): List<String> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
}
