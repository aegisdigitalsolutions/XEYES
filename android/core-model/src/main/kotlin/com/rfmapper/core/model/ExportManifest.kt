package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PackageType {
    OBSERVATIONS,
    DERIVED,
}

@Serializable
enum class ExportKind {
    DAY,
    SESSION,
    RANGE,
}

@Serializable
data class DateRange(
    @SerialName("from") val from: String,
    @SerialName("to") val to: String,
)

@Serializable
data class Generator(
    @SerialName("name") val name: String,
    @SerialName("version") val version: String,
)

/**
 * `manifest.json` for an observation package.
 *
 * [countsBySensorType] and [observationCount] let the Master's import preview summarise a package
 * without parsing 18,000 rows, and let a mismatch against the actual content be detected as
 * `MANIFEST_COUNT_MISMATCH` rather than silently accepted.
 */
@Serializable
data class ExportManifest(
    @SerialName("schema_version") val schemaVersion: String = SchemaVersion.CURRENT,
    @SerialName("package_type") val packageType: PackageType = PackageType.OBSERVATIONS,
    @SerialName("export_id") val exportId: String,
    @SerialName("observer_id") val observerId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("export_kind") val exportKind: ExportKind,
    @SerialName("date_range") val dateRange: DateRange? = null,
    @SerialName("observation_count") val observationCount: Long,
    @SerialName("first_observation") val firstObservation: String? = null,
    @SerialName("last_observation") val lastObservation: String? = null,
    @SerialName("app_version") val appVersion: String,
    @SerialName("platform") val platform: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("installation_id") val installationId: String? = null,
    @SerialName("session_ids") val sessionIds: List<String> = emptyList(),
    @SerialName("counts_by_sensor_type") val countsBySensorType: Map<String, Long> = emptyMap(),
    @SerialName("ground_truth_count") val groundTruthCount: Long = 0,
    @SerialName("generator") val generator: Generator,
) {
    init {
        require(observerId.isNotBlank()) { "observer_id must not be blank" }
        require(observationCount >= 0) { "observation_count must not be negative" }
        if (observationCount > 0) {
            require(firstObservation != null && lastObservation != null) {
                "a non-empty package must declare first_observation and last_observation"
            }
        }
    }
}

/** `manifest.json` for a derived package produced by the Positioning Lab. */
@Serializable
data class DerivedManifest(
    @SerialName("schema_version") val schemaVersion: String = SchemaVersion.CURRENT,
    @SerialName("package_type") val packageType: PackageType = PackageType.DERIVED,
    @SerialName("export_id") val exportId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("date_range") val dateRange: DateRange? = null,
    @SerialName("algorithm_version") val algorithmVersion: String,
    @SerialName("engine_versions") val engineVersions: Map<String, String>,
    @SerialName("parameter_set_sha256") val parameterSetSha256: String? = null,
    @SerialName("random_seed") val randomSeed: Int? = null,

    /**
     * The import batches this output was computed from. The Master rejects a derived package that
     * references data it has never seen, which is what keeps the derived layer traceable back to
     * raw evidence.
     */
    @SerialName("source_dataset_ids") val sourceDatasetIds: List<String>,
    @SerialName("reference_model_id") val referenceModelId: String? = null,
    @SerialName("calibration_set_id") val calibrationSetId: String? = null,

    /** SYNTHETIC results prove pipeline correctness only; they must never be shown as site accuracy. */
    @SerialName("dataset_kind") val datasetKind: DatasetKind = DatasetKind.REAL,
    @SerialName("counts") val counts: Map<String, Long> = emptyMap(),
    @SerialName("generator") val generator: Generator,
)

@Serializable
enum class DatasetKind {
    REAL,
    SYNTHETIC,
    MIXED,
}
