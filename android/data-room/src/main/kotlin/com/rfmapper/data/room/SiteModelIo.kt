package com.rfmapper.data.room

import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.RfMapperJson
import com.rfmapper.core.model.SchemaVersion
import com.rfmapper.core.model.SiteFrame
import com.rfmapper.core.model.SiteModel
import com.rfmapper.data.room.reference.BuildingEntity
import com.rfmapper.data.room.reference.FingerprintDao
import com.rfmapper.data.room.reference.FingerprintEntity
import com.rfmapper.data.room.reference.FingerprintEntryEntity
import com.rfmapper.data.room.reference.InfrastructureDao
import com.rfmapper.data.room.reference.InfrastructureNodeEntity
import com.rfmapper.data.room.reference.ObserverCalibrationDao
import com.rfmapper.data.room.reference.ObserverCalibrationEntity
import com.rfmapper.data.room.reference.ObserverDao
import com.rfmapper.data.room.reference.ObserverEntity
import com.rfmapper.data.room.reference.SiteModelDao
import com.rfmapper.data.room.reference.SurveyPointEntity
import com.rfmapper.data.room.reference.ZoneEdgeEntity
import com.rfmapper.data.room.reference.ZoneEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes the whole REFERENCE layer as one `site_model.json` document.
 *
 * A site model is authored on a laptop and carried to the Master by hand, in the same offline,
 * file-based way observations travel in the other direction. Whole-document rather than
 * field-by-field because a partially applied model — zones referencing a building that did not
 * arrive — is not a smaller model but a broken one.
 *
 * @see <a href="../../../../../../../../docs/18-site-model-specification.md">docs/18</a>
 */
class SiteModelIo(
    private val site: SiteModelDao,
    private val infrastructure: InfrastructureDao,
    private val observers: ObserverDao,
    private val fingerprints: FingerprintDao,
    private val calibration: ObserverCalibrationDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Preview(
        val model: SiteModel?,
        val parseError: String?,
        val issues: List<String>,
    ) {
        /**
         * A model with dangling references is shown but not applied. It can be fixed in the file
         * and re-imported; applying half of it would leave the database in a state no file
         * describes.
         */
        val canApply: Boolean get() = model != null && issues.isEmpty()

        val counts: Map<String, Int>
            get() = model?.let {
                linkedMapOf(
                    "buildings" to it.buildings.size,
                    "zones" to it.zones.size,
                    "zone edges" to it.zoneEdges.size,
                    "infrastructure" to it.infrastructureNodes.size,
                    "observers" to it.observers.size,
                    "survey points" to it.surveyPoints.size,
                    "fingerprints" to it.fingerprints.size,
                    "calibration entries" to it.observerCalibration.size,
                )
            }.orEmpty()
    }

    suspend fun preview(stream: InputStream): Preview = withContext(Dispatchers.IO) {
        val text = stream.use { it.readBytes().decodeToString() }
        val decoded = runCatching { RfMapperJson.compact.decodeFromString(SiteModel.serializer(), text) }
        val parsed = decoded.getOrNull()
            ?: return@withContext Preview(
                model = null,
                parseError = decoded.exceptionOrNull()?.message ?: "unreadable site model",
                issues = emptyList(),
            )

        val issues = buildList {
            if (!SchemaVersion.isReadable(parsed.schemaVersion)) {
                add("schema_version ${parsed.schemaVersion} is not readable by this build (expects major ${SchemaVersion.SUPPORTED_MAJOR})")
            }
            addAll(parsed.referentialIssues())
        }
        Preview(parsed, null, issues)
    }

    /**
     * Applies a previewed model.
     *
     * Upsert rather than replace: deleting zones that a file omits would silently orphan every
     * derived estimate that cites them, and an administrator who genuinely wants a zone gone can
     * say so explicitly. Fingerprint *status* is likewise preserved — an import cannot promote
     * anything to ground truth, because promotion is a human act.
     */
    suspend fun apply(model: SiteModel): AppliedCounts = withContext(Dispatchers.IO) {
        require(model.referentialIssues().isEmpty()) {
            "refusing to apply a site model with dangling references"
        }
        val now = Iso8601.format(nowMillis())

        site.upsertBuildings(model.buildings.map(BuildingEntity::from))
        site.upsertZones(model.zones.map(ZoneEntity::from))
        site.upsertEdges(model.zoneEdges.map(ZoneEdgeEntity::from))
        site.upsertSurveyPoints(model.surveyPoints.map(SurveyPointEntity::from))
        infrastructure.upsertAll(model.infrastructureNodes.map(InfrastructureNodeEntity::from))

        for (identity in model.observers) {
            val existing = observers.byId(identity.observerId)
            observers.upsert(
                ObserverEntity.from(
                    identity = identity,
                    enrolled = existing?.enrolled ?: false,
                    enrolledAtUtc = existing?.enrolledAtUtc,
                ),
            )
        }

        for (point in model.fingerprints) {
            val existing = fingerprints.byId(point.fingerprintId)
            val row = FingerprintEntity.from(point, now)
            fingerprints.replaceFingerprint(
                existing?.let { row.copy(status = it.status, promotedBy = it.promotedBy) } ?: row,
                point.entries.map { FingerprintEntryEntity.from(point.fingerprintId, it) },
            )
        }

        for (entry in model.observerCalibration) {
            calibration.upsert(ObserverCalibrationEntity.from(entry))
        }

        AppliedCounts(
            buildings = model.buildings.size,
            zones = model.zones.size,
            edges = model.zoneEdges.size,
            infrastructure = model.infrastructureNodes.size,
            observers = model.observers.size,
            surveyPoints = model.surveyPoints.size,
            fingerprints = model.fingerprints.size,
            calibrations = model.observerCalibration.size,
        )
    }

    data class AppliedCounts(
        val buildings: Int,
        val zones: Int,
        val edges: Int,
        val infrastructure: Int,
        val observers: Int,
        val surveyPoints: Int,
        val fingerprints: Int,
        val calibrations: Int,
    )

    /**
     * Snapshots the current reference layer.
     *
     * Only `GROUND_TRUTH` fingerprints are exported. A candidate is unreviewed survey data, and
     * shipping it inside a document called "the site model" is exactly how unverified data becomes
     * treated as verified.
     */
    suspend fun export(
        referenceModelId: String,
        frame: SiteFrame,
        notes: String? = null,
    ): SiteModel = withContext(Dispatchers.IO) {
        val promoted = fingerprints.groundTruthFingerprints()
        SiteModel(
            referenceModelId = referenceModelId,
            createdAt = Iso8601.format(nowMillis()),
            notes = notes,
            frame = frame,
            buildings = site.buildings().map { it.toModel() },
            zones = site.zones().map { it.toModel() },
            zoneEdges = site.edges().map { it.toModel() },
            infrastructureNodes = infrastructure.all().map { it.toModel() },
            observers = observers.all().map { it.toModel() },
            surveyPoints = site.surveyPoints().map { it.toModel() },
            fingerprints = promoted.map { it.toModel(fingerprints.entriesFor(it.fingerprintId)) },
            observerCalibration = calibration.all().map { it.toModel() },
        )
    }

    suspend fun writeTo(model: SiteModel, out: OutputStream) = withContext(Dispatchers.IO) {
        out.use { it.write(RfMapperJson.pretty.encodeToString(SiteModel.serializer(), model).encodeToByteArray()) }
    }
}
