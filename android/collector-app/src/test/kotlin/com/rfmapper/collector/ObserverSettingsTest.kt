package com.rfmapper.collector

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.rfmapper.collector.settings.ObserverSettings
import com.rfmapper.core.radio.ScanProfile
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ObserverSettingsTest {

    /**
     * A store of its own per test. DataStore caches one instance per file for the life of the
     * classloader, which Robolectric shares across the tests in a class, so a shared store would
     * leak state between them — and "a fresh install has no observer id" is precisely one of the
     * things being asserted.
     */
    private val settings by lazy {
        val file = File.createTempFile("observer-${UUID.randomUUID()}", ".preferences_pb")
        file.delete()
        ObserverSettings(PreferenceDataStoreFactory.create(produceFile = { file }))
    }

    @Test
    fun `collection is refused until an observer id exists`() = runTest {
        val fresh = settings.snapshot()

        assertNull(fresh.observerId)
        assertFalse(
            fresh.isConfigured,
            "an observation with no observer_id cannot be attributed, calibrated or fused",
        )
    }

    @Test
    fun `identity round-trips and blank optional fields become null rather than empty`() = runTest {
        settings.setIdentity(
            observerId = "  OBS-04 ",
            friendlyName = "Field phone 4",
            buildingId = "   ",
            defaultZoneId = "B7_CENTER",
        )

        val stored = settings.snapshot()

        assertEquals("OBS-04", stored.observerId, "surrounding whitespace would break dedup joins")
        assertEquals("Field phone 4", stored.friendlyName)
        assertNull(stored.buildingId, "an empty building id is absence, not a building called ''")
        assertEquals("B7_CENTER", stored.defaultZoneId)
        assertTrue(stored.isConfigured)
    }

    @Test
    fun `a blank friendly name falls back to the observer id`() = runTest {
        settings.setIdentity(
            observerId = "OBS-09",
            friendlyName = "   ",
            buildingId = null,
            defaultZoneId = null,
        )

        assertEquals("OBS-09", settings.snapshot().friendlyName)
    }

    /**
     * The installation id is what lets the Master tell "OBS-04 reinstalled the app" from "two
     * phones are both claiming to be OBS-04". A regenerated or duplicated id would defeat the only
     * mechanism that detects the second case — and two chipsets' RSSI mixed into one observer's
     * calibration cannot be separated afterwards.
     */
    @Test
    fun `the installation id is minted once and never changes`() = runTest {
        val first = settings.installationId()
        val second = settings.installationId()

        assertEquals(first, second)
        assertTrue(first.isNotBlank())
        assertEquals(first, settings.snapshot().installationId)
    }

    @Test
    fun `concurrent first reads cannot mint two installation ids`() = runTest {
        val a = async { settings.installationId() }
        val b = async { settings.installationId() }

        assertEquals(a.await(), b.await())
    }

    @Test
    fun `the scan profile survives a round trip and defaults to balanced`() = runTest {
        assertEquals(ScanProfile.BALANCED, settings.snapshot().scanProfile)

        settings.setScanProfile(ScanProfile.ENDURANCE)

        assertEquals(ScanProfile.ENDURANCE, settings.snapshot().scanProfile)
    }

    @Test
    fun `the export folder grant is remembered and can be cleared`() = runTest {
        settings.setExportTree("content://tree/primary%3ARFMapper")
        assertEquals("content://tree/primary%3ARFMapper", settings.snapshot().exportTreeUri)

        settings.setExportTree(null)
        assertNull(settings.snapshot().exportTreeUri)
    }

    /**
     * A fixed observer's samples become reference-quality evidence in multi-observer fusion, so the
     * flag must never be set without both coordinates: a half-specified position would give a guess
     * the weight of a survey.
     */
    @Test
    fun `fixed observer requires both coordinates`() = runTest {
        settings.setFixedObserver(12.5, 44.0)
        settings.snapshot().let {
            assertTrue(it.fixedObserver)
            assertEquals(12.5, it.xCoordinate)
            assertEquals(44.0, it.yCoordinate)
        }

        settings.setFixedObserver(12.5, null)
        settings.snapshot().let {
            assertFalse(it.fixedObserver)
            assertNull(it.xCoordinate)
            assertNull(it.yCoordinate)
        }
    }
}
