/// Normalizes a radio identifier into the single canonical form used for deduplication,
/// fingerprinting and device attribution. Mirrors `core-model/RadioIdentifierNormalizer.kt`.
///
/// Two observations of the same radio taken by different observers must produce the same
/// `radio_identifier`, or they will be treated as two different radios. That is why normalization
/// happens once, at capture, and is never left to the consumer.
///
/// On iOS there is a case Android does not have, and it is the most consequential difference between
/// the two Collectors. CoreBluetooth never discloses a peripheral's hardware address; it reports a
/// `CBPeripheral.identifier` UUID that is stable for this peripheral, for this app install, on this
/// device, and meaningless anywhere else. Such an identifier is normalized as
/// ``iosPeripheral(_:)`` and tagged `identifier_scope=APP_INSTALL`, and the Lab must never join it
/// against anything. See `docs/06-ios-capability-matrix.md` §3.
public enum RadioIdentifierNormalizer {

    /// Lowercase, colon-separated. Accepts the common separators a platform might hand back.
    public static func mac(_ raw: String) -> String? {
        let hex = raw.lowercased().filter { $0.isLowercaseHexDigit }
        guard hex.count == 12 else { return nil }
        var out = ""
        out.reserveCapacity(17)
        for (index, character) in hex.enumerated() {
            if index > 0 && index % 2 == 0 { out.append(":") }
            out.append(character)
        }
        return out
    }

    /// Lowercase, hyphenated, canonical 8-4-4-4-12 form.
    public static func uuid(_ raw: String) -> String? {
        let hex = raw.lowercased().filter { $0.isLowercaseHexDigit }
        guard hex.count == 32 else { return nil }
        let digits = Array(hex)
        let groups = [0..<8, 8..<12, 12..<16, 16..<20, 20..<32]
        return groups.map { String(digits[$0]) }.joined(separator: "-")
    }

    /// An iOS peripheral identifier, which is a UUID but *not* an address.
    ///
    /// Normalized identically to any other UUID so the column stays uniform. What distinguishes it
    /// is ``MetadataKeys/identifierScope``, which the capture path sets to `APP_INSTALL`, and the
    /// ``IdentifierType/other`` type -- it is neither a public nor a random MAC, and claiming either
    /// would invite a consumer to treat it as an address.
    public static func iosPeripheral(_ raw: String) -> String? {
        uuid(raw)
    }

    /// An SSID is used verbatim. It is not an identity and is not normalized: two networks may share
    /// one, case is meaningful, and trailing whitespace is a legitimate part of the name.
    public static func ssid(_ raw: String) -> String? {
        raw.isEmpty ? nil : raw
    }

    /// Uppercase hex, no separators, as `manufacturer_data` requires.
    public static func manufacturerData(_ bytes: [UInt8]) -> String? {
        guard !bytes.isEmpty else { return nil }
        let digits = Array("0123456789ABCDEF")
        var out = ""
        out.reserveCapacity(bytes.count * 2)
        for byte in bytes {
            out.append(digits[Int(byte >> 4)])
            out.append(digits[Int(byte & 0x0F)])
        }
        return out
    }
}
