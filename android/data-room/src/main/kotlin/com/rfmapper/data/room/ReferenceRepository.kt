package com.rfmapper.data.room

import com.rfmapper.core.model.Building
import com.rfmapper.core.model.FingerprintPoint
import com.rfmapper.core.model.InfrastructureNode
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.ManagedDevice
import com.rfmapper.core.model.ObserverCalibration
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.RadioIdentifierNormalizer
import com.rfmapper.core.model.SurveyPoint
import com.rfmapper.core.model.Zone
import com.rfmapper.core.model.ZoneEdge
import com.rfmapper.data.room.reference.BuildingEntity
import com.rfmapper.data.room.reference.DeviceIdentifierEntity
import com.rfmapper.data.room.reference.FingerprintDao
import com.rfmapper.data.room.reference.FingerprintEntity
import com.rfmapper.data.room.reference.FingerprintEntryEntity
import com.rfmapper.data.room.reference.InfrastructureDao
import com.rfmapper.data.room.reference.InfrastructureNodeEntity
import com.rfmapper.data.room.reference.ManagedDeviceDao
import com.rfmapper.data.room.reference.ManagedDeviceEntity
import com.rfmapper.data.room.reference.ObserverCalibrationDao
import com.rfmapper.data.room.reference.ObserverCalibrationEntity
import com.rfmapper.data.room.reference.ObserverDao
import com.rfmapper.data.room.reference.ObserverEntity
import com.rfmapper.data.room.reference.SiteModelDao
import com.rfmapper.data.room.reference.SurveyPointEntity
import com.rfmapper.data.room.reference.ZoneEdgeEntity
import com.rfmapper.data.room.reference.ZoneEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The REFERENCE layer's write path: everything an administrator curates by hand.
 *
 * Enrolment and promotion live here rather than in the UI because both are *decisions* with
 * consequences for the raw and derived layers — an unenrolled observer's package is refused, and a
 * promoted fingerprint becomes ground truth for every subsequent estimate. Keeping them in one
 * place means there is exactly one code path that can make either change.
 */
class ReferenceRepository(
    private val devices: ManagedDeviceDao,
    private val infrastructure: InfrastructureDao,
    private val observers: ObserverDao,
    private val site: SiteModelDao,
    private val fingerprints: FingerprintDao,
    private val calibration: ObserverCalibrationDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private fun nowUtc() = Iso8601.format(nowMillis())

    // -- managed devices ------------------------------------------------------------------------

    fun observeDevices(): Flow<List<ManagedDeviceEntity>> = devices.observeAll()

    fun observeDeviceCount(): Flow<Int> = devices.observeCount()

    suspend fun device(deviceId: String): ManagedDevice? = withContext(Dispatchers.IO) {
        devices.byId(deviceId)?.let { it.toModel(devices.identifiersFor(deviceId)) }
    }

    /**
     * Every enrolled device with its identifiers, in one pass rather than one query per device.
     *
     * Two queries and an in-memory join: a registry of a few hundred devices exported with a
     * per-device identifier lookup would be a few hundred round trips for data that fits in memory
     * comfortably.
     */
    suspend fun devices(): List<ManagedDevice> = withContext(Dispatchers.IO) {
        val identifiers = devices.allIdentifiers().groupBy { it.deviceId }
        devices.all().map { it.toModel(identifiers[it.deviceId].orEmpty()) }
    }

    /**
     * Enrols or updates a device together with the identifiers that attribute to it.
     *
     * Identifiers are normalized on the way in, so an administrator who types `AA-BB-CC-DD-EE-FF`
     * and an observer that reports `aa:bb:cc:dd:ee:ff` describe the same device rather than two.
     *
     * @return the identifiers that were rejected as unparseable, so the UI can say which.
     */
    suspend fun saveDevice(device: ManagedDevice, addedBy: String?): List<String> =
        withContext(Dispatchers.IO) {
            val now = nowUtc()
            val existing = devices.byId(device.deviceId)

            // A MAC-shaped field and a UUID-shaped field fail for different reasons, so each list
            // is checked with the normalizer that applies to it rather than with whichever one
            // happens to accept the string.
            val rejected = buildList {
                (device.knownWifiIdentifiers + device.knownBleIdentifiers)
                    .filterTo(this) { RadioIdentifierNormalizer.normalizeMac(it) == null }
                device.knownServiceUuids
                    .filterTo(this) { RadioIdentifierNormalizer.normalizeUuid(it) == null }
            }

            val rows = device.allIdentifiers.map { (identifier, type) ->
                DeviceIdentifierEntity(
                    identifier = identifier,
                    identifierType = type.name,
                    deviceId = device.deviceId,
                    addedBy = addedBy,
                    addedAtUtc = now,
                    notes = null,
                )
            }

            devices.replaceDevice(
                ManagedDeviceEntity.from(
                    device = device,
                    nowUtc = now,
                    createdAtUtc = existing?.createdAtUtc ?: now,
                ),
                rows.distinctBy { it.identifier to it.identifierType },
            )
            rejected
        }

    suspend fun deleteDevice(deviceId: String) = withContext(Dispatchers.IO) {
        devices.byId(deviceId)?.let { devices.delete(it) }
        Unit
    }

    /** Which identifiers are already claimed, so the UI can refuse a collision before writing it. */
    suspend fun identifierOwner(identifier: String, identifierType: String): String? =
        withContext(Dispatchers.IO) { devices.findDeviceIdFor(identifier, identifierType) }

    // -- observers ------------------------------------------------------------------------------

    fun observeObservers(): Flow<List<ObserverEntity>> = observers.observeAll()

    suspend fun saveObserver(identity: ObserverIdentity, enrolled: Boolean) =
        withContext(Dispatchers.IO) {
            val existing = observers.byId(identity.observerId)
            observers.upsert(
                ObserverEntity.from(
                    identity = identity,
                    enrolled = enrolled,
                    enrolledAtUtc = when {
                        !enrolled -> null
                        existing?.enrolled == true -> existing.enrolledAtUtc
                        else -> nowUtc()
                    },
                ),
            )
        }

    /**
     * The enrolment gate. An observer enrolled here is one whose packages the Master will accept;
     * un-enrolling does not remove data already imported, because raw history is not editable.
     */
    suspend fun setEnrolled(observerId: String, enrolled: Boolean) = withContext(Dispatchers.IO) {
        observers.setEnrolled(observerId, enrolled, if (enrolled) nowUtc() else null)
    }

    suspend fun enrolledObserverIds(): Set<String> =
        withContext(Dispatchers.IO) { observers.enrolledIds().toSet() }

    // -- infrastructure -------------------------------------------------------------------------

    fun observeInfrastructure(): Flow<List<InfrastructureNodeEntity>> = infrastructure.observeAll()

    suspend fun saveInfrastructure(node: InfrastructureNode) = withContext(Dispatchers.IO) {
        infrastructure.upsert(
            InfrastructureNodeEntity.from(
                node.copy(knownBssid = node.knownBssid?.let { RadioIdentifierNormalizer.normalizeMac(it) }),
            ),
        )
    }

    suspend fun deleteInfrastructure(nodeId: String) = withContext(Dispatchers.IO) {
        infrastructure.byId(nodeId)?.let { infrastructure.delete(it) }
        Unit
    }

    // -- site model -----------------------------------------------------------------------------

    fun observeBuildings(): Flow<List<Building>> =
        site.observeBuildings().map { rows -> rows.map { it.toModel() } }

    fun observeZones(): Flow<List<Zone>> = site.observeZones().map { rows -> rows.map { it.toModel() } }

    fun observeSurveyPoints(): Flow<List<SurveyPoint>> =
        site.observeSurveyPoints().map { rows -> rows.map { it.toModel() } }

    suspend fun zones(): List<Zone> = withContext(Dispatchers.IO) { site.zones().map { it.toModel() } }

    suspend fun saveBuilding(building: Building) = withContext(Dispatchers.IO) {
        site.upsertBuildings(listOf(BuildingEntity.from(building)))
    }

    suspend fun saveZone(zone: Zone) = withContext(Dispatchers.IO) {
        site.upsertZones(listOf(ZoneEntity.from(zone)))
    }

    suspend fun saveEdge(edge: ZoneEdge) = withContext(Dispatchers.IO) {
        site.upsertEdges(listOf(ZoneEdgeEntity.from(edge)))
    }

    suspend fun saveSurveyPoint(point: SurveyPoint) = withContext(Dispatchers.IO) {
        site.upsertSurveyPoints(listOf(SurveyPointEntity.from(point)))
    }

    suspend fun edges(): List<ZoneEdge> = withContext(Dispatchers.IO) { site.edges().map { it.toModel() } }

    // -- fingerprints ---------------------------------------------------------------------------

    fun observeFingerprints(): Flow<List<FingerprintEntity>> = fingerprints.observeAll()

    suspend fun fingerprint(fingerprintId: String): FingerprintPoint? = withContext(Dispatchers.IO) {
        fingerprints.byId(fingerprintId)?.toModel(fingerprints.entriesFor(fingerprintId))
    }

    suspend fun saveFingerprint(point: FingerprintPoint) = withContext(Dispatchers.IO) {
        fingerprints.replaceFingerprint(
            FingerprintEntity.from(point, nowUtc()),
            point.entries.map { FingerprintEntryEntity.from(point.fingerprintId, it) },
        )
    }

    /**
     * Promotes a candidate fingerprint to ground truth.
     *
     * [promotedBy] is required, not optional: "who decided this was ground truth, and when" is the
     * entire difference between calibration data and a guess, and an unattributed promotion would
     * make a later accuracy dispute unresolvable.
     *
     * @return true when a CANDIDATE row was promoted; false when there was nothing to promote.
     */
    suspend fun promoteFingerprint(fingerprintId: String, promotedBy: String): Boolean =
        withContext(Dispatchers.IO) {
            require(promotedBy.isNotBlank()) { "a promotion must record who performed it" }
            fingerprints.promote(fingerprintId, promotedBy, nowUtc()) > 0
        }

    suspend fun retireFingerprint(fingerprintId: String): Boolean =
        withContext(Dispatchers.IO) { fingerprints.retire(fingerprintId, nowUtc()) > 0 }

    suspend fun groundTruthFingerprints(): List<FingerprintPoint> = withContext(Dispatchers.IO) {
        fingerprints.groundTruthFingerprints().map { header ->
            header.toModel(fingerprints.entriesFor(header.fingerprintId))
        }
    }

    // -- calibration ----------------------------------------------------------------------------

    suspend fun calibrations(): List<ObserverCalibration> =
        withContext(Dispatchers.IO) { calibration.all().map { it.toModel() } }

    /**
     * Records a measured per-observer offset.
     *
     * A large [ObserverCalibration.spreadDb] means the measurement is antenna-pattern difference
     * rather than a scalar offset; it is stored anyway so the Lab can *decline* to apply it, which
     * it cannot do if the evidence never reaches the database.
     */
    suspend fun saveCalibration(entry: ObserverCalibration) = withContext(Dispatchers.IO) {
        calibration.upsert(ObserverCalibrationEntity.from(entry))
    }
}
