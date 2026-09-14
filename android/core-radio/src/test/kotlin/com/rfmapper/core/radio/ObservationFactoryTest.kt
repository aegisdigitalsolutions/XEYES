package com.rfmapper.core.radio

import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SampleKind
import com.rfmapper.core.model.SchemaVersion
import com.rfmapper.core.model.SensorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The factory is the single place a sample becomes a record, so these tests are really about what
 * *cannot* be forgotten: identity, provenance, the clock pair, and the ground-truth safeguard.
 */
class ObservationFactoryTest {

    private val clock = TestClock()
    private val observer = TestRadio.observer()
    private val factory = ObservationFactory(observer, clock, SequentialIds())

    @Test
    fun `every observation is stamped with observer, session and schema`() {
        val observation = factory.create(TestRadio.wifiSample(), TestRadio.session())

        assertEquals("OBS-04", observation.observerId)
        assertEquals(SchemaVersion.CURRENT, observation.schemaVersion)
        assertEquals("0d6b1f4a-7c2e-4a91-b6d3-8f5e1c2a9b40", observation.sessionId)
        assertEquals("android", observation.metadata[MetadataKeys.PLATFORM])
        assertEquals("1.0.0", observation.metadata[MetadataKeys.APP_VERSION])
        assertEquals("Pixel 7a", observation.metadata[MetadataKeys.DEVICE_MODEL])
    }

    @Test
    fun `identifiers are normalised so one radio cannot become two identities`() {
        val upper = factory.create(TestRadio.wifiSample(bssid = "AA:BB:CC:11:22:33"), TestRadio.session())
        val dashed = factory.create(TestRadio.wifiSample(bssid = "aa-bb-cc-11-22-33"), TestRadio.session())

        assertEquals("aa:bb:cc:11:22:33", upper.radioIdentifier)
        assertEquals(upper.radioIdentifier, dashed.radioIdentifier)
        assertEquals(upper.bssid, dashed.bssid)
    }

    @Test
    fun `a sample whose identifier cannot be normalised is refused rather than guessed at`() {
        val nonsense = TestRadio.wifiSample(bssid = "not-a-mac")
        val error = runCatching { factory.create(nonsense, TestRadio.session()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "got $error")
    }

    @Test
    fun `both clocks are recorded so the lab can align two observers`() {
        val sample = TestRadio.wifiSample(
            wallClockMillis = TestClock.WALL_START,
            monotonicMillis = 90_000,
        )
        val observation = factory.create(sample, TestRadio.session())

        assertEquals("90000", observation.metadata[MetadataKeys.CLOCK_ELAPSED_REALTIME_MS])
        assertEquals(
            Iso8601.format(TestClock.WALL_START - 90_000),
            observation.metadata[MetadataKeys.CLOCK_BOOT_UTC],
        )
        assertEquals(Iso8601.format(TestClock.WALL_START), observation.timestampUtc)
    }

    @Test
    fun `the observer's place is recorded, never the target's`() {
        val session = TestRadio.session(
            place = ObserverPlace(buildingId = "B9", zoneId = "B9-NORTH"),
        )
        val observation = factory.create(TestRadio.bleSample(), session)

        assertEquals("B9", observation.buildingId)
        assertEquals("B9-NORTH", observation.zoneId)
        // A raw observation never carries a coordinate for the thing it observed.
        assertNull(observation.xCoordinate)
        assertNull(observation.targetDeviceId)
    }

    @Test
    fun `a cached scan result is scored lower than a fresh one`() {
        val fresh = factory.create(
            TestRadio.wifiSample(freshness = ResultFreshness.FRESH, ageMillis = 900),
            TestRadio.session(),
        )
        val cached = factory.create(
            TestRadio.wifiSample(freshness = ResultFreshness.CACHED, ageMillis = 240_000),
            TestRadio.session(),
        )

        assertNotNull(fresh.confidence)
        assertNotNull(cached.confidence)
        assertTrue(
            cached.confidence!! < fresh.confidence!!,
            "cached ${cached.confidence} should score below fresh ${fresh.confidence}",
        )
        assertEquals("CACHED", cached.metadata[MetadataKeys.RESULT_FRESHNESS])
        assertEquals("240000", cached.metadata[MetadataKeys.SCAN_RESULT_AGE_MS])
    }

    @Test
    fun `survey capture labels ground truth with the point that makes it verifiable`() {
        val survey = SurveyContext(
            surveySessionId = "6a1f2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b",
            surveyPointId = "B7_CENTER",
            place = ObserverPlace(buildingId = "B7", zoneId = "B7-CENTER", xCoordinate = 12.0, yCoordinate = 8.5),
            operator = "AM",
        )
        val observation = factory.create(TestRadio.wifiSample(), TestRadio.session(), survey = survey)

        assertEquals(SampleKind.GROUND_TRUTH, observation.sampleKind)
        assertEquals("B7_CENTER", observation.metadata[MetadataKeys.SURVEY_POINT_ID])
        assertEquals(survey.surveySessionId, observation.metadata[MetadataKeys.SURVEY_SESSION_ID])
        // The survey point's coordinates override the session's rough place.
        assertEquals(12.0, observation.xCoordinate)
        assertEquals(8.5, observation.yCoordinate)
    }

    @Test
    fun `ordinary collection cannot produce ground truth`() {
        val observation = factory.create(TestRadio.wifiSample(), TestRadio.session())
        assertEquals(SampleKind.ORDINARY, observation.sampleKind)
        assertFalse(MetadataKeys.SURVEY_POINT_ID in observation.metadata)
    }

    @Test
    fun `a survey without a point is impossible to construct`() {
        val error = runCatching {
            SurveyContext(
                surveySessionId = "6a1f2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b",
                surveyPointId = "  ",
                place = ObserverPlace(buildingId = "B7"),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "got $error")
    }

    @Test
    fun `degradation is recorded on the affected rows`() {
        val degraded = factory.create(
            TestRadio.bleSample(),
            TestRadio.session(),
            degradation = DegradationContext(missing = setOf("ACCESS_BACKGROUND_LOCATION")),
        )

        assertTrue(degraded.isPermissionDegraded)
        assertEquals("ACCESS_BACKGROUND_LOCATION", degraded.metadata[MetadataKeys.MISSING_PERMISSIONS])
    }

    @Test
    fun `channel is derived from frequency when the platform does not supply it`() {
        val observation = factory.create(TestRadio.wifiSample(), TestRadio.session())
        assertEquals(36, observation.channel)
    }

    @Test
    fun `a randomised ble address is typed as ephemeral and never attributed`() {
        val random = TestRadio.bleSample(mac = "5A:11:22:33:44:55").copy(
            identifierType = IdentifierType.BLE_MAC_RANDOM,
        )
        val observation = factory.create(random, TestRadio.session())

        assertEquals(IdentifierType.BLE_MAC_RANDOM, observation.identifierType)
        assertTrue(observation.identifierType.isEphemeral)
        assertNull(observation.targetDeviceId)
    }

    @Test
    fun `a gnss sample keeps its coordinates and scores no measurement confidence`() {
        val fix = TestRadio.wifiSample().copy(
            sensorType = SensorType.GPS,
            identifierType = IdentifierType.GNSS_FIX,
            identifier = "self",
            bssid = null,
            ssid = null,
            rssi = null,
            frequencyMhz = null,
            latitude = 51.5072,
            longitude = -0.1276,
            horizontalAccuracyMetres = 8.0,
        )
        val observation = factory.create(fix, TestRadio.session())

        assertEquals(51.5072, observation.latitude)
        assertEquals(-0.1276, observation.longitude)
        // Confidence scores a radio measurement's weight; a position fix is not one.
        assertNull(observation.confidence)
    }
}
