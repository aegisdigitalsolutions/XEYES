package com.rfmapper.core.radio

import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.RadioIdentifierNormalizer
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SensorType

/**
 * Turns a [RadioSample] into a canonical [Observation]. The only place in the system that does so.
 *
 * Everything a sample cannot know is applied here, uniformly: the observation id, the observer
 * identity and its device type, the session, the platform provenance, the clock pair, and the
 * survey labelling. Centralising it means a new sensor or a second platform inherits correct
 * stamping rather than reimplementing it.
 *
 * Two rules are enforced rather than trusted:
 *
 * 1. **Identifiers are normalised on the way in.** Deduplication and fingerprinting both key on
 *    `radio_identifier`, so `AA:BB:CC:11:22:33` and `aa-bb-cc-11-22-33` must not become two
 *    identities. A sample whose identifier cannot be normalised is rejected, not guessed at.
 * 2. **Ground truth cannot be claimed casually.** `sample_kind=GROUND_TRUTH` is written only when a
 *    [SurveyContext] is active, which carries the survey point and session that make the claim
 *    verifiable. This is the collection-side half of the safeguard the import validator enforces
 *    again on the Master.
 */
class ObservationFactory(
    private val observer: ObserverIdentity,
    private val clock: Clock = Clock.SYSTEM,
    private val idGenerator: IdGenerator = IdGenerator.RANDOM,
) {

    /**
     * @param session the collection session this sample belongs to.
     * @param survey set only while Survey Mode is capturing at a labelled point.
     * @param degradation permissions or radios that are limiting this observer right now.
     */
    fun create(
        sample: RadioSample,
        session: SessionContext,
        survey: SurveyContext? = null,
        degradation: DegradationContext = DegradationContext.NONE,
        context: Map<String, String> = emptyMap(),
    ): Observation {
        val identifier = normalise(sample)
            ?: throw IllegalArgumentException(
                "identifier '${sample.identifier}' is not normalisable for ${sample.identifierType}",
            )

        val metadata = LinkedHashMap<String, String>()

        // Provenance. Written on every row because a package can outlive the app version that wrote
        // it, and a per-device quirk can only be traced later if the row says which device it was.
        metadata[MetadataKeys.SESSION_ID] = session.sessionId
        metadata[MetadataKeys.PLATFORM] = observer.platform.name.lowercase()
        observer.osVersion?.let { metadata[MetadataKeys.OS_VERSION] = it }
        observer.deviceModel?.let { metadata[MetadataKeys.DEVICE_MODEL] = it }
        metadata[MetadataKeys.APP_VERSION] = observer.appVersion
        observer.installationId?.let { metadata[MetadataKeys.INSTALLATION_ID] = it }

        // The clock pair. `clock_boot_utc` lets the Lab convert any observer's monotonic clock into
        // a common frame and estimate inter-observer offset, which is what makes fusion windows
        // defensible instead of hopeful.
        metadata[MetadataKeys.CLOCK_ELAPSED_REALTIME_MS] = sample.monotonicElapsedMillis.toString()
        metadata[MetadataKeys.CLOCK_BOOT_UTC] =
            Iso8601.format(sample.wallClockMillis - sample.monotonicElapsedMillis)

        if (sample.freshness != ResultFreshness.UNKNOWN) {
            metadata[MetadataKeys.RESULT_FRESHNESS] = sample.freshness.name
        }
        sample.resultAgeMillis?.let { metadata[MetadataKeys.SCAN_RESULT_AGE_MS] = it.toString() }

        metadata += sample.metadata
        metadata += context

        if (survey != null) {
            metadata[MetadataKeys.SAMPLE_KIND] = SampleKind.GROUND_TRUTH.name
            metadata[MetadataKeys.SURVEY_POINT_ID] = survey.surveyPointId
            metadata[MetadataKeys.SURVEY_SESSION_ID] = survey.surveySessionId
            survey.operator?.let { metadata[MetadataKeys.SURVEY_OPERATOR] = it }
            survey.conditions?.let { metadata[MetadataKeys.SURVEY_CONDITIONS] = it }
        }

        if (degradation.isDegraded) {
            metadata[MetadataKeys.PERMISSION_DEGRADED] = "true"
            if (degradation.missing.isNotEmpty()) {
                metadata[MetadataKeys.MISSING_PERMISSIONS] = degradation.missing.sorted().joinToString(";")
            }
        }

        // Where the observer was. Survey capture overrides the session default, because during a
        // survey the operator is standing at a known point rather than wherever the session began.
        val place = survey?.place ?: session.place

        return Observation(
            observationId = idGenerator.newId(),
            timestampUtc = Iso8601.format(sample.wallClockMillis),
            observerId = observer.observerId,
            observerDeviceType = observer.observerDeviceType,
            sensorType = sample.sensorType,
            radioIdentifier = identifier,
            identifierType = sample.identifierType,
            ssid = RadioIdentifierNormalizer.normalizeSsid(sample.ssid),
            bssid = RadioIdentifierNormalizer.normalizeMac(sample.bssid),
            bleServiceUuid = RadioIdentifierNormalizer.normalizeUuid(sample.bleServiceUuid),
            manufacturerData = RadioIdentifierNormalizer.normalizeHex(sample.manufacturerData),
            rssi = sample.rssi,
            txPower = sample.txPower,
            frequency = sample.frequencyMhz,
            channel = sample.channel ?: RadioIdentifierNormalizer.channelForFrequency(sample.frequencyMhz),
            rttDistanceMm = sample.rttDistanceMm,
            rttStddevMm = sample.rttStddevMm,
            latitude = sample.latitude,
            longitude = sample.longitude,
            horizontalAccuracy = sample.horizontalAccuracyMetres,
            buildingId = place.buildingId,
            zoneId = place.zoneId,
            xCoordinate = place.xCoordinate,
            yCoordinate = place.yCoordinate,
            confidence = SampleQuality.of(sample, degradation),
            metadata = metadata,
        )
    }

    /** Timestamp the factory would stamp right now, for callers that need it before a sample exists. */
    fun nowMillis(): Long = clock.wallClockMillis()

    private fun normalise(sample: RadioSample): String? = when (sample.identifierType) {
        IdentifierType.WIFI_BSSID,
        IdentifierType.BLE_MAC_PUBLIC,
        IdentifierType.BLE_MAC_RANDOM,
        -> RadioIdentifierNormalizer.normalizeMac(sample.identifier)

        IdentifierType.BLE_SERVICE_UUID,
        IdentifierType.BLE_IBEACON,
        -> RadioIdentifierNormalizer.normalizeUuid(sample.identifier)

        IdentifierType.WIFI_SSID -> RadioIdentifierNormalizer.normalizeSsid(sample.identifier)

        IdentifierType.GNSS_FIX,
        IdentifierType.OBSERVER_SELF,
        IdentifierType.OTHER,
        -> sample.identifier.trim().ifBlank { null }
    }
}

/** Where the observer is, as the observer itself believes. Never a claim about an observed device. */
data class ObserverPlace(
    val buildingId: String? = null,
    val zoneId: String? = null,
    val xCoordinate: Double? = null,
    val yCoordinate: Double? = null,
) {
    init {
        require((xCoordinate == null) == (yCoordinate == null)) {
            "x and y must both be present or both absent"
        }
        if (xCoordinate != null) require(buildingId != null) { "coordinates require a building" }
    }

    companion object {
        val UNKNOWN = ObserverPlace()

        fun of(observer: ObserverIdentity) = ObserverPlace(
            buildingId = observer.buildingId,
            zoneId = observer.defaultZoneId,
            xCoordinate = observer.xCoordinate.takeIf { observer.fixedObserver },
            yCoordinate = observer.yCoordinate.takeIf { observer.fixedObserver },
        )
    }
}

data class SessionContext(
    val sessionId: String,
    val startedAtMillis: Long,
    val scanProfile: ScanProfile,
    val place: ObserverPlace = ObserverPlace.UNKNOWN,
)

/**
 * An active survey capture. Every field here is required precisely because a ground-truth claim
 * without a point and a session is unverifiable, and unverifiable calibration data is worse than
 * none: it would silently poison every fingerprint built from it.
 */
data class SurveyContext(
    val surveySessionId: String,
    val surveyPointId: String,
    val place: ObserverPlace,
    val operator: String? = null,
    val conditions: String? = null,
) {
    init {
        require(surveySessionId.isNotBlank()) { "a survey requires a session id" }
        require(surveyPointId.isNotBlank()) { "a survey requires a survey point id" }
        require(place.buildingId != null) { "a survey point must at least identify its building" }
    }
}

/** What is currently limiting this observer. Recorded so a thin patch of data is explainable. */
data class DegradationContext(
    val missing: Set<String> = emptySet(),
    val radiosOff: Set<String> = emptySet(),
    val throttled: Boolean = false,
) {
    val isDegraded: Boolean get() = missing.isNotEmpty() || radiosOff.isNotEmpty()

    companion object {
        val NONE = DegradationContext()
    }
}

/**
 * Collection-time measurement quality in [0,1].
 *
 * This scores *the sample*, not a position: how much weight the Lab should give this single reading.
 * The deductions are deliberately blunt and few, because a sophisticated formula here would be
 * fabricated precision — the real weights can only be fitted once the site's data exists
 * (`docs/15-assumptions-requiring-validation.md`). What matters now is that a stale cached scan
 * result is distinguishable from a fresh one.
 */
internal object SampleQuality {

    /** Above this age a Wi-Fi result describes the past rather than the present. */
    private const val STALE_RESULT_MILLIS = 30_000L

    fun of(sample: RadioSample, degradation: DegradationContext): Double? {
        if (!sample.sensorType.isRadioMeasurement) return null

        var quality = 1.0
        if (sample.freshness == ResultFreshness.CACHED) quality -= 0.4
        if (sample.freshness == ResultFreshness.UNKNOWN) quality -= 0.1
        sample.resultAgeMillis?.let { age ->
            if (age > STALE_RESULT_MILLIS) quality -= 0.2
        }
        if (sample.rssi == null && sample.sensorType != SensorType.RTT) quality -= 0.3
        if (degradation.isDegraded) quality -= 0.2
        if (degradation.throttled) quality -= 0.1

        return quality.coerceIn(0.0, 1.0)
    }
}
