package com.rfmapper.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One canonical radio observation: a single statement of the form
 * *"observer OBS-04, standing at B7_CENTER, saw identifier X at -61 dBm at this instant"*.
 *
 * This is the immutable RAW record. Nothing downstream edits it.
 *
 * **[buildingId], [zoneId], [xCoordinate], [yCoordinate], [latitude] and [longitude] describe where
 * the OBSERVER was, never where the observed device is.** Any claim about a target's location lives
 * exclusively in a [PositionEstimate] in the DERIVED layer. Confusing the two would silently turn a
 * measurement into an unvalidated location claim.
 *
 * Invariants are enforced in [init], so an instance that violates the schema cannot be constructed —
 * including when it arrives via deserialization.
 *
 * @see <a href="../../../../../../../../../docs/02-observation-schema.md">docs/02-observation-schema.md</a>
 */
@Serializable
data class Observation(
    @SerialName("observation_id") val observationId: String,
    @SerialName("schema_version") val schemaVersion: String = SchemaVersion.CURRENT,
    @SerialName("timestamp_utc") val timestampUtc: String,
    @SerialName("observer_id") val observerId: String,
    @SerialName("observer_device_type") val observerDeviceType: ObserverDeviceType,
    @SerialName("sensor_type") val sensorType: SensorType,

    /**
     * The managed device this observation is attributed to, or null. Set only by an explicit
     * enrollment match or administrator rule — never inferred from a randomized identifier.
     */
    @SerialName("target_device_id") val targetDeviceId: String? = null,

    /** Normalized: see [RadioIdentifierNormalizer]. Deduplication and fingerprinting depend on it. */
    @SerialName("radio_identifier") val radioIdentifier: String,
    @SerialName("identifier_type") val identifierType: IdentifierType,

    @SerialName("ssid") val ssid: String? = null,
    @SerialName("bssid") val bssid: String? = null,
    @SerialName("ble_service_uuid") val bleServiceUuid: String? = null,
    @SerialName("manufacturer_data") val manufacturerData: String? = null,

    /** Raw, uncalibrated dBm as reported by the radio. Normalization happens only in DERIVED. */
    @SerialName("rssi") val rssi: Int? = null,
    @SerialName("tx_power") val txPower: Int? = null,
    @SerialName("frequency") val frequency: Int? = null,
    @SerialName("channel") val channel: Int? = null,

    /** Genuine Wi-Fi RTT range. Never back-filled from an RSSI model. */
    @SerialName("rtt_distance_mm") val rttDistanceMm: Int? = null,
    @SerialName("rtt_stddev_mm") val rttStddevMm: Int? = null,

    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("horizontal_accuracy") val horizontalAccuracy: Double? = null,

    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("x_coordinate") val xCoordinate: Double? = null,
    @SerialName("y_coordinate") val yCoordinate: Double? = null,

    /**
     * Collection-time measurement quality in [0,1]: how much this single sample should be trusted,
     * given freshness, scan completeness and permission degradation. **Not** a position confidence.
     */
    @SerialName("confidence") val confidence: Double? = null,

    @SerialName("metadata") val metadata: Map<String, String> = emptyMap(),
) {
    init {
        val violations = ObservationInvariants.check(this)
        require(violations.isEmpty()) {
            "Invalid Observation ${observationId}: ${violations.joinToString("; ")}"
        }
    }

    val timestampEpochMillis: Long
        get() = requireNotNull(Iso8601.parseToEpochMillis(timestampUtc))

    val sampleKind: SampleKind
        get() = SampleKind.fromWireOrDefault(metadata[MetadataKeys.SAMPLE_KIND])

    val sessionId: String?
        get() = metadata[MetadataKeys.SESSION_ID]

    val resultFreshness: ResultFreshness
        get() = when (metadata[MetadataKeys.RESULT_FRESHNESS]) {
            ResultFreshness.FRESH.name -> ResultFreshness.FRESH
            ResultFreshness.CACHED.name -> ResultFreshness.CACHED
            else -> ResultFreshness.UNKNOWN
        }

    /** True when a permission or hardware limitation means fields are missing from this record. */
    val isPermissionDegraded: Boolean
        get() = metadata[MetadataKeys.PERMISSION_DEGRADED] == "true"
}

/**
 * The cross-field invariants from `docs/02-observation-schema.md` §1, in one place so that the
 * constructor, the CSV reader and the import validator cannot drift apart.
 *
 * Returns human-readable violations rather than throwing, so the import engine can report a bad row
 * and continue with the rest of the package.
 */
object ObservationInvariants {

    private val UUID_V4 =
        Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")
    private val MAC = Regex("""^([0-9a-f]{2}:){5}[0-9a-f]{2}$""")
    private val UPPER_HEX = Regex("""^[0-9A-F]+$""")

    fun check(o: Observation): List<String> {
        val issues = mutableListOf<String>()

        if (!UUID_V4.matches(o.observationId)) {
            // Rejected rather than lower-cased: coercion would let two encodings of one id coexist,
            // which would defeat deduplication.
            issues += "observation_id must be a lowercase hyphenated UUID"
        }
        if (SchemaVersion.parse(o.schemaVersion) == null) {
            issues += "schema_version must be semantic (got '${o.schemaVersion}')"
        }
        if (!Iso8601.isValid(o.timestampUtc)) {
            issues += "timestamp_utc must be ISO-8601 UTC with milliseconds and a literal Z"
        }
        if (o.observerId.isBlank()) issues += "observer_id must not be blank"
        if (o.observerId.length > MAX_ID_LENGTH) issues += "observer_id exceeds $MAX_ID_LENGTH chars"
        if (o.radioIdentifier.isBlank()) issues += "radio_identifier must not be blank"

        o.bssid?.let { if (!MAC.matches(it)) issues += "bssid must be lowercase colon-separated" }
        o.manufacturerData?.let {
            when {
                it.isEmpty() -> issues += "manufacturer_data must be null rather than empty"
                !UPPER_HEX.matches(it) -> issues += "manufacturer_data must be uppercase hex"
            }
        }
        o.ssid?.let { if (it.length > MAX_SSID_LENGTH) issues += "ssid exceeds $MAX_SSID_LENGTH chars" }

        o.rssi?.let { if (it !in -127..20) issues += "rssi $it outside [-127,20] dBm" }
        o.txPower?.let { if (it !in -127..127) issues += "tx_power $it outside [-127,127] dBm" }
        o.frequency?.let { if (it !in 400..80_000) issues += "frequency $it MHz implausible" }
        o.channel?.let { if (it !in 0..255) issues += "channel $it outside [0,255]" }
        o.rttStddevMm?.let { if (it < 0) issues += "rtt_stddev_mm must not be negative" }

        // Invariant 1: RTT ranges only ever appear on RTT records. Prevents an RSSI-derived
        // distance from ever masquerading as a genuine range measurement.
        if ((o.rttDistanceMm != null || o.rttStddevMm != null) && o.sensorType != SensorType.RTT) {
            issues += "rtt_* fields require sensor_type=RTT (got ${o.sensorType})"
        }

        // Invariant 2: a GNSS fix must carry both coordinates.
        if (o.sensorType == SensorType.GPS && (o.latitude == null || o.longitude == null)) {
            issues += "sensor_type=GPS requires both latitude and longitude"
        }
        o.latitude?.let { if (it !in -90.0..90.0) issues += "latitude $it out of range" }
        o.longitude?.let { if (it !in -180.0..180.0) issues += "longitude $it out of range" }
        o.horizontalAccuracy?.let { if (it < 0) issues += "horizontal_accuracy must not be negative" }

        // Invariant 3: site-local coordinates are meaningless without a building.
        if ((o.xCoordinate != null || o.yCoordinate != null) && o.buildingId == null) {
            issues += "x/y_coordinate require building_id"
        }
        if ((o.xCoordinate == null) != (o.yCoordinate == null)) {
            issues += "x_coordinate and y_coordinate must both be present or both absent"
        }

        // Invariant 4: confidence is a probability.
        o.confidence?.let { if (it !in 0.0..1.0) issues += "confidence $it outside [0,1]" }

        listOfNotNull(o.latitude, o.longitude, o.horizontalAccuracy, o.xCoordinate, o.yCoordinate, o.confidence)
            .firstOrNull { it.isNaN() || it.isInfinite() }
            ?.let { issues += "numeric fields must be finite" }

        return issues
    }

    const val MAX_ID_LENGTH = 64
    const val MAX_SSID_LENGTH = 64
}

/**
 * Reserved [Observation.metadata] keys. Producers must use these names rather than inventing
 * synonyms; consumers must preserve unknown keys verbatim.
 */
object MetadataKeys {
    // Provenance
    const val SESSION_ID = "session_id"
    const val PLATFORM = "platform"
    const val OS_VERSION = "os_version"
    const val DEVICE_MODEL = "device_model"
    const val APP_VERSION = "app_version"
    const val INSTALLATION_ID = "installation_id"

    // Clock and freshness
    const val CLOCK_ELAPSED_REALTIME_MS = "clock_elapsed_realtime_ms"
    const val CLOCK_BOOT_UTC = "clock_boot_utc"
    const val RESULT_FRESHNESS = "result_freshness"
    const val SCAN_RESULT_AGE_MS = "scan_result_age_ms"

    // Wi-Fi
    const val CAPABILITIES = "capabilities"
    const val CHANNEL_WIDTH_MHZ = "channel_width_mhz"
    const val CENTER_FREQ0_MHZ = "center_freq0_mhz"
    const val CENTER_FREQ1_MHZ = "center_freq1_mhz"
    const val WIFI_STANDARD = "wifi_standard"
    const val LINK_SPEED_MBPS = "link_speed_mbps"
    const val IS_CONNECTED = "is_connected"
    const val SCAN_TRIGGER = "scan_trigger"

    // BLE
    const val BLE_DEVICE_NAME = "ble_device_name"
    const val BLE_SERVICE_UUIDS = "ble_service_uuids"
    const val BLE_SERVICE_DATA = "ble_service_data"
    const val BLE_PRIMARY_PHY = "ble_primary_phy"
    const val BLE_IS_LEGACY = "ble_is_legacy"
    const val BLE_IS_CONNECTABLE = "ble_is_connectable"

    /** `GLOBAL` for a hardware address, `APP_INSTALL` for an iOS `CBPeripheral.identifier`. */
    const val IDENTIFIER_SCOPE = "identifier_scope"
    const val IOS_PERIPHERAL_IDENTIFIER = "ios_peripheral_identifier"

    // RTT
    const val RTT_STATUS = "rtt_status"
    const val RTT_NUM_ATTEMPTED = "rtt_num_attempted"
    const val RTT_NUM_SUCCESSFUL = "rtt_num_successful"
    const val RTT_RSSI = "rtt_rssi"
    const val RTT_IS_80211MC = "rtt_is_80211mc"

    // Survey / ground truth
    const val SAMPLE_KIND = "sample_kind"
    const val SURVEY_POINT_ID = "survey_point_id"
    const val SURVEY_SESSION_ID = "survey_session_id"
    const val SURVEY_OPERATOR = "survey_operator"
    const val SURVEY_CONDITIONS = "survey_conditions"

    // Degradation
    const val PERMISSION_DEGRADED = "permission_degraded"
    const val MISSING_PERMISSIONS = "missing_permissions"
    const val THROTTLED = "throttled"

    // Attribution
    const val ATTRIBUTION_SOURCE = "attribution_source"
    const val ATTRIBUTION_CONFIDENCE = "attribution_confidence"
    const val COLLECTOR_CLAIMED_DEVICE_ID = "collector_claimed_device_id"

    const val IMPORT_SOURCE = "import_source"
}
