package com.rfmapper.core.radio

import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SensorType

/**
 * One reading as the radio reported it, carried across the platform boundary.
 *
 * A sample is deliberately *not* an [com.rfmapper.core.model.Observation]. It has no id, no
 * timestamp of record, no observer and no session: those are stamped exactly once, by
 * [ObservationFactory]. A platform provider that produced finished observations would be able to
 * stamp them inconsistently, or forget a field, and the mistake would only surface months later in
 * the Positioning Lab as an unexplained gap.
 *
 * [monotonicElapsedMillis] is the platform's monotonic clock (Android's `SystemClock.elapsedRealtime`),
 * which keeps ordering intact across a wall-clock adjustment. Both clocks are recorded because
 * neither alone is sufficient: the wall clock is comparable between observers but can jump, and the
 * monotonic clock cannot jump but is meaningless between devices.
 */
data class RadioSample(
    val sensorType: SensorType,
    val identifier: String,
    val identifierType: IdentifierType,

    /** Best wall-clock estimate of the instant of measurement, in UTC milliseconds. */
    val wallClockMillis: Long,
    val monotonicElapsedMillis: Long,

    val rssi: Int? = null,
    val txPower: Int? = null,
    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    val ssid: String? = null,
    val bssid: String? = null,
    val bleServiceUuid: String? = null,
    val manufacturerData: String? = null,

    val rttDistanceMm: Int? = null,
    val rttStddevMm: Int? = null,

    val latitude: Double? = null,
    val longitude: Double? = null,
    val horizontalAccuracyMetres: Double? = null,

    val freshness: ResultFreshness = ResultFreshness.UNKNOWN,

    /**
     * How stale the underlying platform result was when it reached us, where the platform can tell
     * us. Wi-Fi results in particular may be several minutes old under scan throttling, and a
     * five-minute-old result must not be treated as a sample of the present moment.
     */
    val resultAgeMillis: Long? = null,

    /** Sensor-specific extras, merged into the observation's metadata verbatim. */
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(identifier.isNotBlank()) { "a sample must carry an identifier" }
        require(wallClockMillis > 0) { "wall_clock_millis must be a real instant" }
    }
}

/**
 * Why a sensor is not producing what it could. Reported rather than hidden, because the most
 * expensive failure in this system is discovering after a six-hour session that BLE was off.
 */
enum class Degradation {
    /** The device has no such radio. Normal on a heterogeneous fleet; never an error. */
    HARDWARE_ABSENT,

    PERMISSION_DENIED,

    /** Foreground scanning works; a session that leaves the foreground will have gaps. */
    BACKGROUND_PERMISSION_DENIED,

    RADIO_OFF,

    /**
     * Permission granted but the device's location master switch is off. Called out separately
     * because Wi-Fi scan results then come back as an empty list with no error at all, which looks
     * exactly like "no access points here".
     */
    LOCATION_SERVICES_OFF,

    /** The platform is rate-limiting scans, so the sample rate is lower than the profile asks for. */
    THROTTLED,

    /** The service runs, but its notification is hidden, so a live session looks stopped. */
    NOTIFICATIONS_BLOCKED,
}

/**
 * What a sensor can do on this device *right now*, resolved at runtime rather than assumed.
 *
 * The three flags are kept separate because they need different remedies and must not be collapsed
 * into one "unavailable": [supported] false means buy different hardware, [permitted] false means
 * grant a permission, [enabled] false means flip a system switch. Only [live] means data will flow.
 *
 * @see <a href="../../../../../../../../../docs/05-android-permission-matrix.md">docs/05, §5</a>
 */
data class Capability(
    /** The hardware and OS version can do this at all. */
    val supported: Boolean,

    /** Every permission it needs is currently granted. */
    val permitted: Boolean = supported,

    /** The radio and any required system service are switched on. */
    val enabled: Boolean = supported,

    val degradations: Set<Degradation> = emptySet(),
    val missingPermissions: List<String> = emptyList(),
) {
    /** True when this sensor will actually produce samples. */
    val live: Boolean get() = supported && permitted && enabled

    /** True when the hardware is there and only configuration stands in the way. */
    val fixableByAdministrator: Boolean get() = supported && !live

    companion object {
        val LIVE = Capability(supported = true)

        fun absent() = Capability(
            supported = false,
            permitted = false,
            enabled = false,
            degradations = setOf(Degradation.HARDWARE_ABSENT),
        )

        fun denied(missing: List<String>) = Capability(
            supported = true,
            permitted = false,
            enabled = true,
            degradations = setOf(Degradation.PERMISSION_DENIED),
            missingPermissions = missing,
        )

        fun off(reason: Degradation) = Capability(
            supported = true,
            permitted = true,
            enabled = false,
            degradations = setOf(reason),
        )
    }
}
