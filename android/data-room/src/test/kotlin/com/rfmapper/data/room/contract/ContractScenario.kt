package com.rfmapper.data.room.contract

import com.rfmapper.core.model.Building
import com.rfmapper.core.model.DeviceStatus
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.InfrastructureNode
import com.rfmapper.core.model.InfrastructureType
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.ManagedDevice
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.Platform
import com.rfmapper.core.model.Point
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.model.SiteFrame
import com.rfmapper.core.model.SiteModel
import com.rfmapper.core.model.SurveyPoint
import com.rfmapper.core.model.Zone
import com.rfmapper.core.model.ZoneEdge
import com.rfmapper.core.model.ZoneEdgeType
import com.rfmapper.core.model.ZoneKind

/**
 * One small site, described once, from which every cross-language fixture is generated.
 *
 * Deliberately a coherent scenario rather than three unrelated sample files. The Lab is handed the
 * observation packages, the site model and the device registry together and has to make sense of
 * all three at once — an observer's rows are only interpretable against the observer record, a BLE
 * detection is only attributable against the registry, and a fingerprint is only comparable to a
 * live vector because the collector phones are registered as infrastructure. Fixtures generated
 * independently would each parse and still not compose, which is the failure this is built to
 * catch.
 *
 * Everything is deterministic: fixed ids, fixed timestamps, and RSSI from an arithmetic pattern
 * rather than a random source, so regenerating the fixtures with no code change produces no diff.
 */
object ContractScenario {

    const val BUILDING = "B1"
    const val ZONE_NORTH = "B1-NORTH"
    const val ZONE_SOUTH = "B1-SOUTH"

    const val OBSERVER_NORTH = "OBS-C1"
    const val OBSERVER_SOUTH = "OBS-C2"
    const val SURVEYOR = "OBS-C3"

    const val TAG_DEVICE = "DEVICE-TAG-1"
    const val TAG_BLE = "d0:d0:d0:00:00:0a"
    const val TAG_SERVICE_UUID = "6b1a7e10-3c4d-4f5a-9b8c-1d2e3f405162"

    const val AP_NORTH = "aa:bb:cc:00:00:01"
    const val AP_SOUTH = "aa:bb:cc:00:00:02"
    const val AP_RTT = "aa:bb:cc:00:00:03"
    const val BEACON_NORTH = "c1:c1:c1:00:00:01"
    const val BEACON_SOUTH = "c2:c2:c2:00:00:02"

    const val SURVEY_POINT_NORTH = "SP-N"
    const val SURVEY_POINT_SOUTH = "SP-S"

    const val REFERENCE_MODEL_ID = "site-contract-2026-05-04"

    /** 2026-05-04T08:00:00.000Z. Fixed, so the package names and every id stay stable. */
    val DAY_START: Long = requireNotNull(Iso8601.parseToEpochMillis("2026-05-04T08:00:00.000Z"))

    val FRAME = SiteFrame(originLat = 51.5074, originLon = -0.1278, rotationDeg = 12.5)

    // -- REFERENCE ---------------------------------------------------------------------------------

    fun siteModel(): SiteModel = SiteModel(
        referenceModelId = REFERENCE_MODEL_ID,
        createdAt = Iso8601.format(DAY_START),
        notes = "Cross-language contract fixture. Not a real site.",
        frame = FRAME,
        buildings = listOf(
            Building(
                buildingId = BUILDING,
                name = "Workshop",
                floors = listOf(0),
                outlinePolygon = listOf(
                    Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 14.0), Point(0.0, 14.0),
                ),
            ),
        ),
        zones = listOf(
            Zone(
                zoneId = ZONE_NORTH,
                buildingId = BUILDING,
                name = "North bay",
                zoneKind = ZoneKind.ROOM,
                polygon = listOf(
                    Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 7.0), Point(0.0, 7.0),
                ),
            ),
            Zone(
                zoneId = ZONE_SOUTH,
                buildingId = BUILDING,
                name = "South bay",
                zoneKind = ZoneKind.ROOM,
                polygon = listOf(
                    Point(0.0, 7.0), Point(10.0, 7.0), Point(10.0, 14.0), Point(0.0, 14.0),
                ),
            ),
        ),
        zoneEdges = listOf(
            ZoneEdge(
                fromZoneId = ZONE_NORTH,
                toZoneId = ZONE_SOUTH,
                edgeType = ZoneEdgeType.DOOR,
                typicalTraversalS = 4.0,
            ),
        ),
        infrastructureNodes = listOf(
            InfrastructureNode(
                nodeId = "AP-NORTH",
                friendlyName = "North AP",
                type = InfrastructureType.WIFI_AP,
                buildingId = BUILDING,
                floor = 0,
                zoneId = ZONE_NORTH,
                x = 2.0,
                y = 2.0,
                knownBssid = AP_NORTH,
            ),
            InfrastructureNode(
                nodeId = "AP-SOUTH",
                friendlyName = "South AP",
                type = InfrastructureType.WIFI_AP,
                buildingId = BUILDING,
                floor = 0,
                zoneId = ZONE_SOUTH,
                x = 2.0,
                y = 12.0,
                knownBssid = AP_SOUTH,
            ),
            InfrastructureNode(
                nodeId = "AP-RTT",
                friendlyName = "Ranging AP",
                type = InfrastructureType.RTT_ANCHOR,
                buildingId = BUILDING,
                floor = 0,
                zoneId = ZONE_NORTH,
                x = 8.0,
                y = 7.0,
                knownBssid = AP_RTT,
                rttCapable = true,
                notes = "rtt_capable set from an observed range, never from the datasheet",
            ),
            // node_id == observer_id is what lets the Lab treat a collector phone as a source and
            // compare "observer heard device" against a fingerprint by reciprocity.
            InfrastructureNode(
                nodeId = OBSERVER_NORTH,
                friendlyName = "North collector",
                type = InfrastructureType.OBSERVER_PHONE,
                buildingId = BUILDING,
                floor = 0,
                zoneId = ZONE_NORTH,
                x = 2.0,
                y = 3.0,
                knownBleIdentifier = BEACON_NORTH,
            ),
            InfrastructureNode(
                nodeId = OBSERVER_SOUTH,
                friendlyName = "South collector",
                type = InfrastructureType.OBSERVER_PHONE,
                buildingId = BUILDING,
                floor = 0,
                zoneId = ZONE_SOUTH,
                x = 2.0,
                y = 11.0,
                knownBleIdentifier = BEACON_SOUTH,
            ),
        ),
        observers = listOf(
            observer(OBSERVER_NORTH, "North collector", ZONE_NORTH, 2.0, 3.0, fixed = true),
            observer(OBSERVER_SOUTH, "South collector", ZONE_SOUTH, 2.0, 11.0, fixed = true),
            observer(SURVEYOR, "Survey handset", ZONE_NORTH, null, null, fixed = false),
        ),
        surveyPoints = listOf(
            SurveyPoint(
                surveyPointId = SURVEY_POINT_NORTH,
                buildingId = BUILDING,
                zoneId = ZONE_NORTH,
                floor = 0,
                x = 2.0,
                y = 2.0,
                label = "North bay, under the AP",
                physicalDescription = "Floor marker N, 1 m from the north wall",
                createdAtUtc = Iso8601.format(DAY_START),
            ),
            SurveyPoint(
                surveyPointId = SURVEY_POINT_SOUTH,
                buildingId = BUILDING,
                zoneId = ZONE_SOUTH,
                floor = 0,
                x = 2.0,
                y = 12.0,
                label = "South bay, by the roller door",
                physicalDescription = "Floor marker S, 2 m from the roller door",
                createdAtUtc = Iso8601.format(DAY_START),
            ),
        ),
    )

    private fun observer(
        id: String,
        name: String,
        zone: String,
        x: Double?,
        y: Double?,
        fixed: Boolean,
    ) = ObserverIdentity(
        observerId = id,
        friendlyName = name,
        observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
        buildingId = BUILDING,
        defaultZoneId = zone,
        deviceModel = "Pixel 7a",
        manufacturer = "Google",
        platform = Platform.ANDROID,
        osVersion = "34",
        appVersion = "1.0.0",
        installationId = "b1c2d3e4-1111-4222-8333-4444555566${id.takeLast(2).filter { it.isDigit() }.padStart(2, '0')}",
        capabilities = setOf(
            ObserverCapability.WIFI_SCAN,
            ObserverCapability.BLE,
            ObserverCapability.GPS,
            ObserverCapability.RTT,
        ),
        // Declared, not merely absent: an observer that cannot associate reporting no association
        // rows means "cannot see", not "nothing was there".
        unsupported = setOf(ObserverCapability.WIFI_ASSOCIATION),
        xCoordinate = x,
        yCoordinate = y,
        fixedObserver = fixed,
    )

    fun managedDevices(): List<ManagedDevice> = listOf(
        ManagedDevice(
            deviceId = TAG_DEVICE,
            friendlyName = "Toolbox tag",
            deviceType = "BLE_TAG",
            status = DeviceStatus.AUTHORIZED,
            knownBleIdentifiers = listOf(TAG_BLE),
            knownServiceUuids = listOf(TAG_SERVICE_UUID),
            notes = "Service UUID is the durable identifier; the MAC may rotate.",
        ),
        ManagedDevice(
            deviceId = "DEVICE-RETIRED-1",
            friendlyName = "Withdrawn handset",
            deviceType = "PHONE",
            // Exported despite being blocked. The Lab still has to recognise it: filtering it out
            // would make its rows indistinguishable from environmental RF.
            status = DeviceStatus.BLOCKED,
            knownWifiIdentifiers = listOf("ee:ff:00:11:22:33"),
        ),
    )

    // -- RAW ---------------------------------------------------------------------------------------

    /** Sources audible at each survey point, with the median level a surveyor would record. */
    private val SURVEY_SOURCES: Map<String, List<Source>> = mapOf(
        SURVEY_POINT_NORTH to listOf(
            Source(AP_NORTH, IdentifierType.WIFI_BSSID, SensorType.WIFI_SCAN, -45),
            Source(AP_SOUTH, IdentifierType.WIFI_BSSID, SensorType.WIFI_SCAN, -72),
            Source(AP_RTT, IdentifierType.WIFI_BSSID, SensorType.WIFI_SCAN, -58),
            Source(BEACON_NORTH, IdentifierType.BLE_MAC_PUBLIC, SensorType.BLE, -40),
            Source(BEACON_SOUTH, IdentifierType.BLE_MAC_PUBLIC, SensorType.BLE, -70),
        ),
        SURVEY_POINT_SOUTH to listOf(
            Source(AP_NORTH, IdentifierType.WIFI_BSSID, SensorType.WIFI_SCAN, -73),
            Source(AP_SOUTH, IdentifierType.WIFI_BSSID, SensorType.WIFI_SCAN, -44),
            Source(AP_RTT, IdentifierType.WIFI_BSSID, SensorType.WIFI_SCAN, -60),
            Source(BEACON_NORTH, IdentifierType.BLE_MAC_PUBLIC, SensorType.BLE, -71),
            Source(BEACON_SOUTH, IdentifierType.BLE_MAC_PUBLIC, SensorType.BLE, -41),
        ),
    )

    private data class Source(
        val identifier: String,
        val type: IdentifierType,
        val sensor: SensorType,
        val medianRssi: Int,
    )

    private const val SAMPLES_PER_SESSION = 8
    private const val SURVEY_SESSIONS = 3

    /**
     * The surveyor's package: ground truth at both points, over three separate visits.
     *
     * Three sessions rather than one because a fingerprint built from a single afternoon records
     * that afternoon's conditions, and the engine's own parameters say so. The sample count clears
     * the engine's floor, so this fixture exercises fingerprint construction rather than the
     * "too thin to promote" path.
     */
    fun surveyorObservations(): List<Observation> = buildList {
        var index = 0
        for (session in 0 until SURVEY_SESSIONS) {
            val sessionId = uuid(0xA0 + session)
            val surveySessionId = uuid(0xB0 + session)
            for ((pointId, sources) in SURVEY_SOURCES) {
                for (sample in 0 until SAMPLES_PER_SESSION) {
                    // Sessions a day apart, samples five seconds apart: separate visits, not one
                    // long stand.
                    val at = DAY_START + session * 86_400_000L +
                        (if (pointId == SURVEY_POINT_NORTH) 0L else 3_600_000L) +
                        sample * 5_000L
                    for (source in sources) {
                        add(
                            surveyRow(
                                index = index++,
                                at = at,
                                pointId = pointId,
                                sessionId = sessionId,
                                surveySessionId = surveySessionId,
                                source = source,
                                jitter = jitter(index),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun surveyRow(
        index: Int,
        at: Long,
        pointId: String,
        sessionId: String,
        surveySessionId: String,
        source: Source,
        jitter: Int,
    ): Observation {
        val point = siteModel().surveyPoints.first { it.surveyPointId == pointId }
        return Observation(
            observationId = uuid(1_000 + index),
            timestampUtc = Iso8601.format(at),
            observerId = SURVEYOR,
            observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
            sensorType = source.sensor,
            radioIdentifier = source.identifier,
            identifierType = source.type,
            ssid = if (source.sensor == SensorType.WIFI_SCAN) "WORKSHOP-INFRA" else null,
            bssid = if (source.sensor == SensorType.WIFI_SCAN) source.identifier else null,
            rssi = source.medianRssi + jitter,
            txPower = if (source.sensor == SensorType.BLE) -4 else null,
            frequency = if (source.sensor == SensorType.WIFI_SCAN) 5180 else null,
            channel = if (source.sensor == SensorType.WIFI_SCAN) 36 else null,
            buildingId = BUILDING,
            zoneId = point.zoneId,
            xCoordinate = point.x,
            yCoordinate = point.y,
            confidence = 0.95,
            metadata = mapOf(
                MetadataKeys.SESSION_ID to sessionId,
                MetadataKeys.RESULT_FRESHNESS to ResultFreshness.FRESH.name,
                MetadataKeys.SAMPLE_KIND to SampleKind.GROUND_TRUTH.name,
                MetadataKeys.SURVEY_POINT_ID to pointId,
                MetadataKeys.SURVEY_SESSION_ID to surveySessionId,
                MetadataKeys.SURVEY_OPERATOR to "contract-fixture",
            ),
        )
    }

    /**
     * A fixed collector's package: the managed tag heard repeatedly, plus the awkward rows.
     *
     * The awkward rows are the point of this fixture as much as the tag is. An SSID containing a
     * comma, a quote and a non-ASCII character is what proves the two CSV dialects agree; a cached
     * scan, a randomized BLE address and a GNSS fix are the three cases a reader is most likely to
     * mishandle silently.
     */
    fun collectorObservations(observerId: String, hearsTagAt: Int, firstId: Int): List<Observation> {
        val north = observerId == OBSERVER_NORTH
        val sessionId = sessionUuid(if (north) 1 else 2)
        var index = firstId

        // Every field is passed at construction rather than copied in afterwards. The model
        // validates cross-field invariants in its constructor, so a GNSS row built without its
        // coordinates and corrected later would throw before the correction ran.
        fun row(
            at: Long,
            sensor: SensorType,
            identifier: String,
            type: IdentifierType,
            rssi: Int? = null,
            txPower: Int? = null,
            ssid: String? = null,
            bssid: String? = null,
            bleServiceUuid: String? = null,
            manufacturerData: String? = null,
            frequency: Int? = null,
            channel: Int? = null,
            rttDistanceMm: Int? = null,
            rttStddevMm: Int? = null,
            latitude: Double? = null,
            longitude: Double? = null,
            horizontalAccuracy: Double? = null,
            confidence: Double = 0.9,
            extra: Map<String, String> = emptyMap(),
        ) = Observation(
            observationId = uuid(index++),
            timestampUtc = Iso8601.format(at),
            observerId = observerId,
            observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
            sensorType = sensor,
            radioIdentifier = identifier,
            identifierType = type,
            ssid = ssid,
            bssid = bssid,
            bleServiceUuid = bleServiceUuid,
            manufacturerData = manufacturerData,
            rssi = rssi,
            txPower = txPower,
            frequency = frequency,
            channel = channel,
            rttDistanceMm = rttDistanceMm,
            rttStddevMm = rttStddevMm,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracy = horizontalAccuracy,
            buildingId = BUILDING,
            zoneId = if (north) ZONE_NORTH else ZONE_SOUTH,
            xCoordinate = 2.0,
            yCoordinate = if (north) 3.0 else 11.0,
            confidence = confidence,
            metadata = mapOf(
                MetadataKeys.SESSION_ID to sessionId,
                MetadataKeys.RESULT_FRESHNESS to ResultFreshness.FRESH.name,
            ) + extra,
        )

        val tail = DAY_START + 7_300_000L

        return buildList {
            // The tag, every two seconds for a minute, heard by both collectors at once.
            for (step in 0 until 30) {
                add(
                    row(
                        at = DAY_START + 7_200_000L + step * 2_000L,
                        sensor = SensorType.BLE,
                        identifier = TAG_BLE,
                        type = IdentifierType.BLE_MAC_PUBLIC,
                        rssi = hearsTagAt + jitter(step),
                        txPower = -8,
                        bleServiceUuid = TAG_SERVICE_UUID,
                        manufacturerData = "004C0215A1B2",
                        extra = mapOf(
                            MetadataKeys.BLE_DEVICE_NAME to "TOOLBOX-1",
                            MetadataKeys.BLE_IS_CONNECTABLE to "false",
                        ),
                    ),
                )
            }

            add(
                row(
                    at = tail,
                    sensor = SensorType.WIFI_SCAN,
                    identifier = AP_NORTH,
                    type = IdentifierType.WIFI_BSSID,
                    // A comma, a quote and a non-ASCII character, because an SSID is an arbitrary
                    // byte string and the two CSV dialects have to agree about one.
                    ssid = "Caf\u00e9 \"Nord\", guest",
                    bssid = AP_NORTH,
                    rssi = if (north) -46 else -74,
                    frequency = 5180,
                    channel = 36,
                ),
            )

            add(
                row(
                    at = tail + 1_000L,
                    sensor = SensorType.WIFI_SCAN,
                    identifier = AP_SOUTH,
                    type = IdentifierType.WIFI_BSSID,
                    ssid = "WORKSHOP-INFRA",
                    bssid = AP_SOUTH,
                    rssi = if (north) -71 else -45,
                    frequency = 2437,
                    channel = 6,
                    confidence = 0.4,
                    extra = mapOf(
                        MetadataKeys.RESULT_FRESHNESS to ResultFreshness.CACHED.name,
                        MetadataKeys.SCAN_RESULT_AGE_MS to "27400",
                        MetadataKeys.THROTTLED to "true",
                    ),
                ),
            )

            add(
                row(
                    at = tail + 2_000L,
                    sensor = SensorType.RTT,
                    identifier = AP_RTT,
                    type = IdentifierType.WIFI_BSSID,
                    rssi = -57,
                    bssid = AP_RTT,
                    frequency = 5180,
                    channel = 36,
                    rttDistanceMm = if (north) 6_420 else 9_180,
                    rttStddevMm = 780,
                    extra = mapOf(
                        MetadataKeys.RTT_NUM_ATTEMPTED to "8",
                        MetadataKeys.RTT_NUM_SUCCESSFUL to "7",
                        MetadataKeys.RTT_IS_80211MC to "true",
                    ),
                ),
            )

            add(
                row(
                    at = tail + 3_000L,
                    sensor = SensorType.BLE,
                    identifier = "4f:2a:9c:d1:0e:77",
                    type = IdentifierType.BLE_MAC_RANDOM,
                    rssi = -88,
                    // Left unattributed on purpose: a randomized address is environmental context,
                    // and nothing in the system may merge it into a managed device.
                    extra = mapOf(MetadataKeys.IDENTIFIER_SCOPE to "GLOBAL"),
                ),
            )

            add(
                row(
                    at = tail + 4_000L,
                    sensor = SensorType.GPS,
                    identifier = IdentifierType.GNSS_FIX.name,
                    type = IdentifierType.GNSS_FIX,
                    latitude = 51.50742 + if (north) 0.0 else 0.00009,
                    longitude = -0.12781,
                    horizontalAccuracy = 14.5,
                    confidence = 0.3,
                ),
            )

            add(
                row(
                    at = tail + 5_000L,
                    sensor = SensorType.WIFI_SCAN,
                    identifier = "aa:bb:cc:00:00:09",
                    type = IdentifierType.WIFI_BSSID,
                    rssi = -79,
                    // A hidden SSID, so the reader meets an absent string next to present ones.
                    bssid = "aa:bb:cc:00:00:09",
                    frequency = 5180,
                    channel = 36,
                    extra = mapOf(
                        MetadataKeys.PERMISSION_DEGRADED to "true",
                        MetadataKeys.MISSING_PERMISSIONS to "ACCESS_BACKGROUND_LOCATION",
                    ),
                ),
            )
        }
    }

    /** Deterministic ±2 dB, standing in for measurement noise without a random source. */
    private fun jitter(index: Int): Int = (index * 7) % 5 - 2

    fun uuid(index: Int): String =
        "00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}"

    /** A separate arm, so a session id can never be mistaken for an observation id. */
    private fun sessionUuid(index: Int): String =
        "00000000-0000-4000-9000-${index.toString(16).padStart(12, '0')}"
}
