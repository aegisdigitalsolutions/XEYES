/// Reserved ``Observation/metadata`` keys, mirroring `core-model/Observation.kt`.
///
/// Producers must use these names rather than inventing synonyms; consumers must preserve unknown
/// keys verbatim. The Python Lab reads several of them by name, so these strings are contract.
public enum MetadataKeys {

    // Provenance
    public static let sessionId = "session_id"
    public static let platform = "platform"
    public static let osVersion = "os_version"
    public static let deviceModel = "device_model"
    public static let appVersion = "app_version"
    public static let installationId = "installation_id"

    // Clock and freshness
    public static let clockElapsedRealtimeMs = "clock_elapsed_realtime_ms"
    public static let clockBootUtc = "clock_boot_utc"
    public static let resultFreshness = "result_freshness"
    public static let scanResultAgeMs = "scan_result_age_ms"

    // Wi-Fi
    public static let capabilities = "capabilities"
    public static let channelWidthMhz = "channel_width_mhz"
    public static let centerFreq0Mhz = "center_freq0_mhz"
    public static let centerFreq1Mhz = "center_freq1_mhz"
    public static let wifiStandard = "wifi_standard"
    public static let linkSpeedMbps = "link_speed_mbps"
    public static let isConnected = "is_connected"
    public static let scanTrigger = "scan_trigger"

    // BLE
    public static let bleDeviceName = "ble_device_name"
    public static let bleServiceUuids = "ble_service_uuids"
    public static let bleServiceData = "ble_service_data"
    public static let blePrimaryPhy = "ble_primary_phy"
    public static let bleIsLegacy = "ble_is_legacy"
    public static let bleIsConnectable = "ble_is_connectable"

    /// `GLOBAL` for a hardware address, `APP_INSTALL` for an iOS `CBPeripheral.identifier`.
    ///
    /// The single most consequential field for an iOS Collector: see
    /// `docs/06-ios-capability-matrix.md` §3. The Lab must never merge identifiers of differing
    /// scope, nor use an `APP_INSTALL` identifier as a site-wide fingerprint key.
    public static let identifierScope = "identifier_scope"
    public static let iosPeripheralIdentifier = "ios_peripheral_identifier"

    // RTT
    public static let rttStatus = "rtt_status"
    public static let rttNumAttempted = "rtt_num_attempted"
    public static let rttNumSuccessful = "rtt_num_successful"
    public static let rttRssi = "rtt_rssi"
    public static let rttIs80211mc = "rtt_is_80211mc"

    // Survey / ground truth
    public static let sampleKind = "sample_kind"
    public static let surveyPointId = "survey_point_id"
    public static let surveySessionId = "survey_session_id"
    public static let surveyOperator = "survey_operator"
    public static let surveyConditions = "survey_conditions"

    // Degradation
    public static let permissionDegraded = "permission_degraded"
    public static let missingPermissions = "missing_permissions"
    public static let throttled = "throttled"

    // Attribution
    public static let attributionSource = "attribution_source"
    public static let attributionConfidence = "attribution_confidence"
    public static let collectorClaimedDeviceId = "collector_claimed_device_id"

    public static let importSource = "import_source"
}

/// Values for ``MetadataKeys/identifierScope``.
public enum IdentifierScope: String, Sendable {
    /// A hardware address, comparable across observers and platforms.
    case global = "GLOBAL"

    /// An iOS `CBPeripheral.identifier`: stable for this peripheral, for this app install, on this
    /// device, and meaningless anywhere else.
    case appInstall = "APP_INSTALL"
}
