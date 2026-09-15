/// The single authority for the `observations.csv` column order and cell encoding, per
/// `docs/03-csv-column-specification.md`. Mirrors `core-model/csv/ObservationCsvCodec.kt`.
///
/// Encoding is deterministic -- sorted metadata keys, fixed decimal formatting, stable row order
/// chosen by the caller -- so two exports of the same data are byte-identical. That is what makes
/// `checksum.txt` meaningful and re-exports diffable, and it is why an iPhone and a Pixel must agree
/// here down to the character.
public enum ObservationCsvCodec {

    public static let columns: [String] = [
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
    ]

    /// Columns covered by ``rowChecksum(_:)``: everything except the checksum itself.
    private static let checksumColumnCount = 28
    private static let unitSeparator: Character = "\u{001f}"
    private static let decimalScale = 6

    public static var header: String { Csv.encodeRow(columns) }

    public static func encode(_ observation: Observation) -> String {
        Csv.encodeRow(cells(observation))
    }

    /// The decoded (unquoted) cell values, in column order.
    public static func cells(_ observation: Observation) -> [String?] {
        let withoutChecksum = valueColumns(observation)
        return withoutChecksum + [rowChecksum(withoutChecksum)]
    }

    private static func valueColumns(_ o: Observation) -> [String?] {
        [
            o.observationId,
            o.schemaVersion,
            o.timestampUtc,
            o.observerId,
            o.observerDeviceType.rawValue,
            o.sensorType.rawValue,
            o.targetDeviceId,
            o.radioIdentifier,
            o.identifierType.rawValue,
            o.ssid,
            o.bssid,
            o.bleServiceUuid,
            o.manufacturerData,
            o.rssi.map(String.init),
            o.txPower.map(String.init),
            o.frequency.map(String.init),
            o.channel.map(String.init),
            o.rttDistanceMm.map(String.init),
            o.rttStddevMm.map(String.init),
            formatDecimal(o.latitude),
            formatDecimal(o.longitude),
            formatDecimal(o.horizontalAccuracy),
            o.buildingId,
            o.zoneId,
            formatDecimal(o.xCoordinate),
            formatDecimal(o.yCoordinate),
            formatDecimal(o.confidence),
            encodeMetadata(o.metadata),
        ]
    }

    /// First 16 hex characters of the SHA-256 of the decoded value columns joined by the unit
    /// separator. A cheap per-row corruption check that survives a round trip through a spreadsheet,
    /// which is the most likely way a CSV gets mangled in the field.
    ///
    /// Truncation to 64 bits is deliberate: this detects corruption, not tampering. Package
    /// integrity is `checksum.txt`.
    public static func rowChecksum(_ valueColumns: [String?]) -> String {
        precondition(
            valueColumns.count == checksumColumnCount,
            "row checksum covers \(checksumColumnCount) columns, got \(valueColumns.count)"
        )
        let joined = valueColumns.map { $0 ?? "" }.joined(separator: String(unitSeparator))
        var hasher = Sha256()
        hasher.update(joined)
        return String(Sha256.format(hasher.finalized()).prefix(16))
    }

    /// Compact JSON with lexicographically sorted keys, so the row stays deterministic.
    public static func encodeMetadata(_ metadata: [String: String]) -> String {
        if metadata.isEmpty { return "{}" }
        return CanonicalJSON.compact(.sortedMap(metadata))
    }

    public static func decodeMetadata(_ text: String?) -> [String: String]? {
        guard let text, !text.isEmpty else { return [:] }
        return MiniJSON.parseStringMap(text)
    }

    public enum DecodeResult {
        case success(observation: Observation, checksumMatched: Bool)
        case failure(reasons: [String])
    }

    /// Decodes one record. Columns are matched **by header name**, not by position, so a future
    /// minor schema version that appends columns remains readable. Unknown extra columns are
    /// preserved into metadata as `csv_extra_<name>` rather than discarded.
    public static func decode(header: [String], record: [String]) -> DecodeResult {
        var reasons: [String] = []

        let missing = columns.filter { !header.contains($0) }
        if !missing.isEmpty {
            return .failure(reasons: ["CSV_HEADER_MISMATCH: missing \(missing.joined(separator: ", "))"])
        }
        if record.count != header.count {
            return .failure(reasons: [
                "CSV_FIELD_COUNT_MISMATCH: expected \(header.count) fields, got \(record.count)"
            ])
        }

        var byName: [String: String] = [:]
        for (index, name) in header.enumerated() {
            byName[name] = record[index]
        }
        func text(_ column: String) -> String? {
            guard let raw = byName[column], !raw.isEmpty else { return nil }
            return raw
        }
        func integer(_ column: String) -> Int? {
            guard let raw = text(column) else { return nil }
            guard let value = Int(raw) else {
                reasons.append("INVALID_INTEGER: \(column)='\(raw)'")
                return nil
            }
            return value
        }
        func decimal(_ column: String) -> Double? {
            guard let raw = text(column) else { return nil }
            guard let value = Double(raw), value.isFinite else {
                reasons.append("INVALID_DECIMAL: \(column)='\(raw)'")
                return nil
            }
            return value
        }

        // Every cell is parsed up front, before the error guard below. Parsing lazily during
        // construction would collect malformed-cell reasons only after they had been checked,
        // letting a corrupted row through as a nil field instead of a reported failure.
        let sensorRaw = text("sensor_type")
        let sensorType = sensorRaw.flatMap(SensorType.init(rawValue:))
        if sensorType == nil { reasons.append("INVALID_ENUM: sensor_type='\(sensorRaw ?? "")'") }

        let identifierRaw = text("identifier_type")
        let identifierType = identifierRaw.flatMap(IdentifierType.init(rawValue:))
        if identifierType == nil { reasons.append("INVALID_ENUM: identifier_type='\(identifierRaw ?? "")'") }

        let deviceRaw = text("observer_device_type")
        let deviceType = deviceRaw.flatMap(ObserverDeviceType.init(rawValue:))
        if deviceType == nil { reasons.append("INVALID_ENUM: observer_device_type='\(deviceRaw ?? "")'") }

        let metadata = decodeMetadata(byName["metadata_json"])
        if metadata == nil { reasons.append("INVALID_METADATA_JSON") }

        let rssi = integer("rssi")
        let txPower = integer("tx_power")
        let frequency = integer("frequency")
        let channel = integer("channel")
        let rttDistanceMm = integer("rtt_distance_mm")
        let rttStddevMm = integer("rtt_stddev_mm")
        let latitude = decimal("latitude")
        let longitude = decimal("longitude")
        let horizontalAccuracy = decimal("horizontal_accuracy")
        let xCoordinate = decimal("x_coordinate")
        let yCoordinate = decimal("y_coordinate")
        let confidence = decimal("confidence")

        var extras: [String: String] = [:]
        for name in header where !columns.contains(name) {
            if let value = byName[name], !value.isEmpty {
                extras["csv_extra_\(name)"] = value
            }
        }

        if !reasons.isEmpty { return .failure(reasons: reasons) }
        guard let sensorType, let identifierType, let deviceType, let metadata else {
            return .failure(reasons: ["INVALID_ROW"])
        }

        var combined = metadata
        for (key, value) in extras { combined[key] = value }

        let candidate = Observation(
            observationId: text("observation_id") ?? "",
            schemaVersion: text("schema_version") ?? "",
            timestampUtc: text("timestamp_utc") ?? "",
            observerId: text("observer_id") ?? "",
            observerDeviceType: deviceType,
            sensorType: sensorType,
            targetDeviceId: text("target_device_id"),
            radioIdentifier: text("radio_identifier") ?? "",
            identifierType: identifierType,
            ssid: text("ssid"),
            bssid: text("bssid"),
            bleServiceUuid: text("ble_service_uuid"),
            manufacturerData: text("manufacturer_data"),
            rssi: rssi,
            txPower: txPower,
            frequency: frequency,
            channel: channel,
            rttDistanceMm: rttDistanceMm,
            rttStddevMm: rttStddevMm,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracy: horizontalAccuracy,
            buildingId: text("building_id"),
            zoneId: text("zone_id"),
            xCoordinate: xCoordinate,
            yCoordinate: yCoordinate,
            confidence: confidence,
            metadata: combined
        )

        let violations = ObservationInvariants.check(candidate)
        if !violations.isEmpty {
            return .failure(reasons: [ObservationError.invalid(
                id: candidate.observationId,
                reasons: violations
            ).description])
        }

        // Recompute over the metadata as written, excluding any extra columns folded into metadata,
        // so the checksum reflects the row as the producer encoded it.
        var asWritten = candidate
        asWritten.metadata = metadata
        let declared = byName["row_checksum"].flatMap { $0.isEmpty ? nil : $0 }
        let recomputed = rowChecksum(valueColumns(asWritten))
        let matched = declared == nil || declared == recomputed

        return .success(observation: candidate, checksumMatched: matched)
    }

    /// Plain decimal notation, no exponent, up to six decimals, trailing zeros trimmed. Integral
    /// values print without a decimal point so `11.0` round-trips as `11`.
    ///
    /// This reproduces Kotlin's
    /// `BigDecimal.valueOf(value).setScale(6, HALF_UP).stripTrailingZeros().toPlainString()`.
    /// `BigDecimal.valueOf` starts from the shortest round-tripping decimal, which is the same digit
    /// sequence Swift's `description` yields, so the two agree digit for digit rather than
    /// approximately.
    public static func formatDecimal(_ value: Double?) -> String? {
        guard let value else { return nil }
        precondition(value.isFinite, "non-finite value cannot be encoded")
        if value == 0 { return "0" }

        let negative = value < 0
        guard let decomposed = CanonicalNumber.Decomposed(magnitude: abs(value)) else {
            return value.description
        }

        var digits = Array(decomposed.digits.utf8).map { $0 - 48 }
        var exponent = decomposed.exponent
        let keep = exponent + decimalScale

        if keep < 0 {
            return "0"
        } else if keep == 0 {
            // Smaller than half of the last retained place unless the leading digit carries it.
            if digits[0] >= 5 {
                digits = [1]
                exponent += 1
            } else {
                return "0"
            }
        } else if keep < digits.count {
            let roundUp = digits[keep] >= 5
            digits = Array(digits[0..<keep])
            if roundUp {
                var index = digits.count - 1
                var carrying = true
                while carrying && index >= 0 {
                    if digits[index] == 9 {
                        digits[index] = 0
                        index -= 1
                    } else {
                        digits[index] += 1
                        carrying = false
                    }
                }
                if carrying {
                    // Every retained digit was a nine, so the value rolled over to the next power.
                    digits = [1]
                    exponent += 1
                }
            }
        }

        while digits.last == 0 { digits.removeLast() }
        if digits.isEmpty { return "0" }

        let text = String(digits.map { Character(UnicodeScalar($0 + 48)) })
        let body: String
        if exponent <= 0 {
            body = "0." + String(repeating: "0", count: -exponent) + text
        } else if exponent >= text.count {
            // `stripTrailingZeros().toPlainString()` drops the point entirely for an integral value.
            body = text + String(repeating: "0", count: exponent - text.count)
        } else {
            let split = text.index(text.startIndex, offsetBy: exponent)
            body = String(text[text.startIndex..<split]) + "." + String(text[split...])
        }
        return negative ? "-" + body : body
    }
}
