package com.rfmapper.core.export

import com.rfmapper.core.model.DateRange
import com.rfmapper.core.model.ExportKind
import com.rfmapper.core.model.Generator
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverCapability
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.Platform
import com.rfmapper.core.model.SensorType
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An export package is identified by the sha256 of its bytes, so "the same rows produce the same
 * archive" is not a nicety: the Master keys a re-import on it, and the cross-language fixtures under
 * `contract/` are compared against committed copies.
 *
 * The threat is not the data. It is everything the zip format takes from its environment — chiefly
 * the member timestamp, which is stored as *local* DOS time and therefore passes through the JVM's
 * default zone on its way into the archive. A Collector carried across a time zone, or a build agent
 * configured differently from a developer's laptop, is enough to change the bytes without changing
 * an observation.
 */
class ZipDeterminismTest {

    private val exporter = ExportEngineV1()

    @Test
    fun `the same rows exported in different time zones produce identical bytes`() {
        // Chosen to sit either side of the DOS floor as it was in 1980, which is what the previous
        // fixed timestamp fell foul of: at +09:00 that instant is a representable local time and is
        // written into the DOS field verbatim, at -11:00 it is not and gets clamped. A pair of zones
        // that were both west of UTC would agree even with a badly chosen constant and prove nothing.
        val east = inZone("Asia/Tokyo") { exportZip(day()) }
        val west = inZone("Pacific/Niue") { exportZip(day()) }

        assertContentEquals(
            east,
            west,
            "an export moved across the date line changed its bytes without changing an observation",
        )
    }

    @Test
    fun `every entry carries the epoch, read back from any zone`() {
        // The mechanism the previous test depends on, asserted where a change would be legible: the
        // epoch is before 1980 in every zone, so the DOS field clamps to the format's floor
        // everywhere and the exact time travels in a UTC extra field. Move the constant forward to
        // any representable instant and these read back differently either side of the date line.
        val east = inZone("Asia/Tokyo") { readEntryTimes(exportZip(day())) }
        val west = inZone("Pacific/Niue") { readEntryTimes(exportZip(day())) }

        assertEquals(ExportPackage.REQUIRED_ENTRIES.toSet(), east.keys)
        assertEquals(east, west)
        for ((name, millis) in east) {
            assertEquals(0L, millis, "entry '$name' does not carry a zone-independent timestamp")
        }
    }

    @Test
    fun `re-exporting the same rows produces identical bytes`() {
        assertContentEquals(exportZip(day()), exportZip(day()))
    }

    @Test
    fun `a changed row changes the archive`() {
        val changed = day().toMutableList().also { rows ->
            rows[3] = rows[3].copy(rssi = rows[3].rssi!! - 1)
        }

        assertTrue(
            !exportZip(day()).contentEquals(exportZip(changed)),
            "a fixed timestamp must not make the archive insensitive to its contents",
        )
    }

    // -- helpers --------------------------------------------------------------------------------

    private fun <T> inZone(id: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun exportZip(observations: List<Observation>): ByteArray {
        val buffer = ByteArrayOutputStream()
        exporter.write(
            request = request(observations),
            source = ObservationSource { observations.iterator() },
            sink = ZipExportSink(buffer),
        )
        return buffer.toByteArray()
    }

    /** Entry name to the modification time a reader sees, in epoch millis. */
    private fun readEntryTimes(zip: ByteArray): Map<String, Long> =
        ZipInputStream(zip.inputStream()).use { stream ->
            buildMap {
                while (true) {
                    val entry = stream.nextEntry ?: break
                    put(entry.name, entry.time)
                }
            }
        }

    private fun day(count: Int = 12): List<Observation> = (1..count).map { index ->
        Observation(
            observationId = "00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}",
            timestampUtc = Iso8601.format(DAY_START + index * 1_000L),
            observerId = OBSERVER,
            observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
            sensorType = SensorType.WIFI_SCAN,
            radioIdentifier = "aa:bb:cc:11:22:33",
            identifierType = IdentifierType.WIFI_BSSID,
            ssid = "SITE-INFRA-7",
            bssid = "aa:bb:cc:11:22:33",
            rssi = -61 - (index % 7),
            frequency = 5180,
            channel = 36,
            buildingId = "B7",
            zoneId = "B7-CENTER",
            confidence = 0.9,
        )
    }

    private fun request(observations: List<Observation>) = ExportRequest(
        exportId = "3f8e1c20-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
        observer = ObserverIdentity(
            observerId = OBSERVER,
            friendlyName = "Building 4 Android Collector",
            observerDeviceType = ObserverDeviceType.ANDROID_PHONE,
            deviceModel = "Pixel 7a",
            manufacturer = "Google",
            platform = Platform.ANDROID,
            osVersion = "34",
            appVersion = "1.0.0",
            installationId = "b1c2d3e4-1111-4222-8333-444455556666",
            capabilities = setOf(ObserverCapability.WIFI_SCAN),
        ),
        exportKind = ExportKind.DAY,
        createdAtEpochMillis = CREATED_AT,
        summary = ObservationSummary(
            observationCount = observations.size.toLong(),
            firstObservationUtc = observations.first().timestampUtc,
            lastObservationUtc = observations.last().timestampUtc,
            countsBySensorType = mapOf(SensorType.WIFI_SCAN.name to observations.size.toLong()),
        ),
        dateRange = DateRange(from = "2026-09-14T00:00:00.000Z", to = "2026-09-14T23:59:59.999Z"),
        appVersion = "1.0.0",
        generator = Generator("RFMapper Collector", "1.0.0"),
    )

    private companion object {
        const val OBSERVER = "OBS-04"
        const val CREATED_AT = 1_789_400_000_000L
        val DAY_START = Iso8601.parseToEpochMillis("2026-09-14T06:11:02.310Z")!!
    }
}
