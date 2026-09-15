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

    /// A Bluetooth service or characteristic UUID, expanded to its full 128-bit form.
    ///
    /// Necessary because of an asymmetry that would otherwise discard the only cross-platform join
    /// key iOS has. Bluetooth SIG-assigned UUIDs are 16- or 32-bit shorthands for a value in the
    /// Bluetooth Base UUID range, and `CBUUID.uuidString` hands back the shorthand: a heart-rate
    /// service is `"180D"`, not `"0000180d-0000-1000-8000-00805f9b34fb"`. Android's
    /// `ParcelUuid.toString()` always returns the 128-bit form. Passing the shorthand to ``uuid(_:)``
    /// yields nil -- it is four hex digits, not thirty-two -- so an iOS sighting of a tag
    /// advertising a standard service would record no service UUID at all and become unjoinable to
    /// the Android sighting of the same hardware, which is the one thing
    /// `docs/06-ios-capability-matrix.md` §3 says must keep working.
    ///
    /// Already-128-bit input is normalized by ``uuid(_:)`` unchanged.
    public static func bluetoothUuid(_ raw: String) -> String? {
        // A `0x` prefix is stripped before anything counts digits. Shorthands are written both ways
        // -- `180D` and `0x180D` -- and the `0` of the prefix would otherwise be counted as a hex
        // digit, turning a valid shorthand into a five-digit string that matches nothing. This
        // matters because operator-entered UUIDs reach this function, not just CoreBluetooth's.
        var text = raw.trimmingASCIIWhitespace()
        if text.count > 2, text.lowercased().hasPrefix("0x") {
            text = String(text.dropFirst(2))
        }

        let hex = text.lowercased().filter { $0.isLowercaseHexDigit }
        guard hex.count == text.count else {
            // Separators are legal in the 128-bit form and nowhere else, so anything with stray
            // characters is only worth trying as a full UUID.
            return uuid(text)
        }
        switch hex.count {
        case 4:
            return uuid("0000\(hex)\(Self.bluetoothBaseSuffix)")
        case 8:
            return uuid("\(hex)\(Self.bluetoothBaseSuffix)")
        default:
            return uuid(text)
        }
    }

    /// Everything after the first 32 bits of the Bluetooth Base UUID,
    /// `00000000-0000-1000-8000-00805F9B34FB`.
    private static let bluetoothBaseSuffix = "0000" + "1000" + "8000" + "00805f9b34fb"

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
