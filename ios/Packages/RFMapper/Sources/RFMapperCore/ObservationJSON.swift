extension Observation {

    /// The observation as kotlinx.serialization writes it under `RfMapperJson.compact`.
    ///
    /// Three things here are contract rather than preference, and all three were read back off a
    /// package the Android Collector actually produced:
    ///
    /// - **Field order is the Kotlin declaration order**, not alphabetical.
    /// - **Every nullable field is present as `null`**, because `explicitNulls = true`. A reader can
    ///   therefore see all of an observation's fields without consulting the schema to work out
    ///   which were merely omitted.
    /// - **Metadata keys are sorted.** kotlinx writes a map in iteration order, and sorting is what
    ///   makes that order reproducible rather than dependent on how the map was built.
    public var json: JSONValue {
        .object([
            ("observation_id", .string(observationId)),
            ("schema_version", .string(schemaVersion)),
            ("timestamp_utc", .string(timestampUtc)),
            ("observer_id", .string(observerId)),
            ("observer_device_type", .string(observerDeviceType.rawValue)),
            ("sensor_type", .string(sensorType.rawValue)),
            ("target_device_id", .string(targetDeviceId)),
            ("radio_identifier", .string(radioIdentifier)),
            ("identifier_type", .string(identifierType.rawValue)),
            ("ssid", .string(ssid)),
            ("bssid", .string(bssid)),
            ("ble_service_uuid", .string(bleServiceUuid)),
            ("manufacturer_data", .string(manufacturerData)),
            ("rssi", .int(rssi)),
            ("tx_power", .int(txPower)),
            ("frequency", .int(frequency)),
            ("channel", .int(channel)),
            ("rtt_distance_mm", .int(rttDistanceMm)),
            ("rtt_stddev_mm", .int(rttStddevMm)),
            ("latitude", .double(latitude)),
            ("longitude", .double(longitude)),
            ("horizontal_accuracy", .double(horizontalAccuracy)),
            ("building_id", .string(buildingId)),
            ("zone_id", .string(zoneId)),
            ("x_coordinate", .double(xCoordinate)),
            ("y_coordinate", .double(yCoordinate)),
            ("confidence", .double(confidence)),
            ("metadata", .sortedMap(metadata)),
        ])
    }

    public var compactJSON: String {
        CanonicalJSON.compact(json)
    }
}
