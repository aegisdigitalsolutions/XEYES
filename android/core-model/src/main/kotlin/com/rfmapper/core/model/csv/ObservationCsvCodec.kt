package com.rfmapper.core.model.csv

import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.Observation
import com.rfmapper.core.model.ObserverDeviceType
import com.rfmapper.core.model.SensorType
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The single authority for the `observations.csv` column order and cell encoding, per
 * `docs/03-csv-column-specification.md`.
 *
 * Encoding is deterministic — sorted metadata keys, fixed decimal formatting, stable row order
 * chosen by the caller — so two exports of the same data are byte-identical. That is what makes
 * `checksum.txt` meaningful and re-exports diffable.
 */
object ObservationCsvCodec {

    val COLUMNS: List<String> = listOf(
        "observation_id",
        "schema_version",
        "timestamp_utc",
        "observer_id",
        "observer_device_type",
        "sensor_type",
        "target_device_id",
        "radio_identifier",
        "identifier_type",
        "ssid",
        "bssid",
        "ble_service_uuid",
        "manufacturer_data",
        "rssi",
        "tx_power",
        "frequency",
        "channel",
        "rtt_distance_mm",
        "rtt_stddev_mm",
        "latitude",
        "longitude",
        "horizontal_accuracy",
        "building_id",
        "zone_id",
        "x_coordinate",
        "y_coordinate",
        "confidence",
        "metadata_json",
        "row_checksum",
    )

    /** Columns covered by [rowChecksum]: everything except the checksum itself. */
    private const val CHECKSUM_COLUMN_COUNT = 28
    private const val UNIT_SEPARATOR = '\u001f'
    private const val DECIMAL_SCALE = 6

    private val json = Json { encodeDefaults = true }

    val header: String get() = Csv.encodeRow(COLUMNS)

    fun encode(observation: Observation): String = Csv.encodeRow(cells(observation))

    /** The decoded (unquoted) cell values, in column order. */
    fun cells(observation: Observation): List<String?> {
        val withoutChecksum = valueColumns(observation)
        return withoutChecksum + rowChecksum(withoutChecksum)
    }

    private fun valueColumns(o: Observation): List<String?> = listOf(
        o.observationId,
        o.schemaVersion,
        o.timestampUtc,
        o.observerId,
        o.observerDeviceType.name,
        o.sensorType.name,
        o.targetDeviceId,
        o.radioIdentifier,
        o.identifierType.name,
        o.ssid,
        o.bssid,
        o.bleServiceUuid,
        o.manufacturerData,
        o.rssi?.toString(),
        o.txPower?.toString(),
        o.frequency?.toString(),
        o.channel?.toString(),
        o.rttDistanceMm?.toString(),
        o.rttStddevMm?.toString(),
        formatDecimal(o.latitude),
        formatDecimal(o.longitude),
        formatDecimal(o.horizontalAccuracy),
        o.buildingId,
        o.zoneId,
        formatDecimal(o.xCoordinate),
        formatDecimal(o.yCoordinate),
        formatDecimal(o.confidence),
        encodeMetadata(o.metadata),
    )

    /**
     * First 16 hex characters of the SHA-256 of the decoded value columns joined by the unit
     * separator. A cheap per-row corruption check that survives a round trip through a spreadsheet,
     * which is the most likely way a CSV gets mangled in the field.
     *
     * Truncation to 64 bits is deliberate: this detects corruption, not tampering. Package integrity
     * is `checksum.txt`.
     */
    fun rowChecksum(valueColumns: List<String?>): String {
        require(valueColumns.size == CHECKSUM_COLUMN_COUNT) {
            "row checksum covers $CHECKSUM_COLUMN_COUNT columns, got ${valueColumns.size}"
        }
        val joined = valueColumns.joinToString(UNIT_SEPARATOR.toString()) { it ?: "" }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (index in 0 until 8) {
                append("%02x".format(digest[index].toInt() and 0xFF))
            }
        }
    }

    /** Compact JSON with lexicographically sorted keys, so the row stays deterministic. */
    fun encodeMetadata(metadata: Map<String, String>): String {
        if (metadata.isEmpty()) return "{}"
        val sorted = metadata.toSortedMap().mapValues { JsonPrimitive(it.value) }
        return json.encodeToString(JsonObject.serializer(), JsonObject(sorted))
    }

    fun decodeMetadata(text: String?): Map<String, String>? {
        if (text.isNullOrEmpty()) return emptyMap()
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null
        val result = LinkedHashMap<String, String>(obj.size)
        for ((key, value) in obj) {
            val primitive = value as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            result[key] = primitive.content
        }
        return result
    }

    sealed interface DecodeResult {
        data class Success(val observation: Observation, val checksumMatched: Boolean) : DecodeResult
        data class Failure(val reasons: List<String>) : DecodeResult
    }

    /**
     * Decodes one record. Columns are matched **by header name**, not by position, so a future
     * minor schema version that appends columns remains readable. Unknown extra columns are
     * preserved into metadata as `csv_extra_<name>` rather than discarded.
     */
    fun decode(header: List<String>, record: List<String>): DecodeResult {
        val reasons = mutableListOf<String>()
        val missing = COLUMNS.filter { it !in header }
        if (missing.isNotEmpty()) {
            return DecodeResult.Failure(listOf("CSV_HEADER_MISMATCH: missing ${missing.joinToString()}"))
        }
        if (record.size != header.size) {
            return DecodeResult.Failure(
                listOf("CSV_FIELD_COUNT_MISMATCH: expected ${header.size} fields, got ${record.size}"),
            )
        }

        val byName = header.withIndex().associate { (index, name) -> name to record[index] }
        fun text(column: String): String? = byName[column]?.ifEmpty { null }

        fun int(column: String): Int? {
            val raw = text(column) ?: return null
            return raw.toIntOrNull().also { if (it == null) reasons += "INVALID_INTEGER: $column='$raw'" }
        }

        fun double(column: String): Double? {
            val raw = text(column) ?: return null
            val parsed = raw.toDoubleOrNull()
            if (parsed == null || parsed.isNaN() || parsed.isInfinite()) {
                reasons += "INVALID_DECIMAL: $column='$raw'"
                return null
            }
            return parsed
        }

        // Every cell is parsed up front, before the error guard below. Parsing lazily inside the
        // constructor call would collect malformed-cell reasons only after they had been checked,
        // letting a corrupted row through as a null field instead of a reported failure.
        val sensorTypeRaw = text("sensor_type")
        val sensorType = sensorTypeRaw?.let(SensorType::fromWireOrNull)
        if (sensorType == null) reasons += "INVALID_ENUM: sensor_type='$sensorTypeRaw'"

        val identifierTypeRaw = text("identifier_type")
        val identifierType = identifierTypeRaw?.let(IdentifierType::fromWireOrNull)
        if (identifierType == null) reasons += "INVALID_ENUM: identifier_type='$identifierTypeRaw'"

        val deviceTypeRaw = text("observer_device_type")
        val deviceType = deviceTypeRaw?.let(ObserverDeviceType::fromWireOrNull)
        if (deviceType == null) reasons += "INVALID_ENUM: observer_device_type='$deviceTypeRaw'"

        val metadata = decodeMetadata(byName["metadata_json"])
        if (metadata == null) reasons += "INVALID_METADATA_JSON"

        val rssi = int("rssi")
        val txPower = int("tx_power")
        val frequency = int("frequency")
        val channel = int("channel")
        val rttDistanceMm = int("rtt_distance_mm")
        val rttStddevMm = int("rtt_stddev_mm")
        val latitude = double("latitude")
        val longitude = double("longitude")
        val horizontalAccuracy = double("horizontal_accuracy")
        val xCoordinate = double("x_coordinate")
        val yCoordinate = double("y_coordinate")
        val confidence = double("confidence")

        val extras = header.filter { it !in COLUMNS }
            .mapNotNull { name -> byName[name]?.ifEmpty { null }?.let { "csv_extra_$name" to it } }

        if (reasons.isNotEmpty()) return DecodeResult.Failure(reasons)
        if (sensorType == null || identifierType == null || deviceType == null || metadata == null) {
            return DecodeResult.Failure(listOf("INVALID_ROW"))
        }

        val observation = runCatching {
            Observation(
                observationId = text("observation_id").orEmpty(),
                schemaVersion = text("schema_version").orEmpty(),
                timestampUtc = text("timestamp_utc").orEmpty(),
                observerId = text("observer_id").orEmpty(),
                observerDeviceType = deviceType,
                sensorType = sensorType,
                targetDeviceId = text("target_device_id"),
                radioIdentifier = text("radio_identifier").orEmpty(),
                identifierType = identifierType,
                ssid = text("ssid"),
                bssid = text("bssid"),
                bleServiceUuid = text("ble_service_uuid"),
                manufacturerData = text("manufacturer_data"),
                rssi = rssi,
                txPower = txPower,
                frequency = frequency,
                channel = channel,
                rttDistanceMm = rttDistanceMm,
                rttStddevMm = rttStddevMm,
                latitude = latitude,
                longitude = longitude,
                horizontalAccuracy = horizontalAccuracy,
                buildingId = text("building_id"),
                zoneId = text("zone_id"),
                xCoordinate = xCoordinate,
                yCoordinate = yCoordinate,
                confidence = confidence,
                metadata = metadata + extras,
            )
        }.getOrElse { error ->
            return DecodeResult.Failure(listOf(error.message ?: "INVALID_ROW"))
        }

        // Recompute over the metadata as written, excluding any extra columns folded into metadata,
        // so the checksum reflects the row as the producer encoded it.
        val declaredChecksum = byName["row_checksum"]?.ifEmpty { null }
        val recomputed = rowChecksum(valueColumns(observation.copy(metadata = metadata)))
        val matched = declaredChecksum == null || declaredChecksum == recomputed

        return DecodeResult.Success(observation, checksumMatched = matched)
    }

    /**
     * Plain decimal notation, no exponent, up to six decimals, trailing zeros trimmed. Integral
     * values print without a decimal point so `11.0` round-trips as `11`.
     */
    fun formatDecimal(value: Double?): String? {
        if (value == null) return null
        require(!value.isNaN() && !value.isInfinite()) { "non-finite value cannot be encoded" }
        val scaled = BigDecimal.valueOf(value)
            .setScale(DECIMAL_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        return scaled.toPlainString()
    }
}
