/// The single source of truth for the observation-contract version, mirroring
/// `core-model/SchemaVersion.kt`.
///
/// Compatibility follows semantic versioning, as specified in `docs/02-observation-schema.md`:
///  - patch: documentation or a new reserved metadata key. Always readable.
///  - minor: a new optional top-level field. Readable; unknown fields must be preserved.
///  - major: a required field or a field's meaning changed. Consumers must reject unknown majors
///    with a clear error rather than guessing.
public enum SchemaVersion {

    public static let current = "1.0.0"
    public static let supportedMajor = 1

    /// Highest minor this build understands. Higher minors are readable but may carry extra fields.
    public static let supportedMinor = 0

    public struct Parsed: Equatable, Sendable {
        public let major: Int
        public let minor: Int
        public let patch: Int
    }

    public static func parse(_ version: String) -> Parsed? {
        let parts = version.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        var numbers: [Int] = []
        for part in parts {
            // Rejects "1.0.0x", "+1", " 1" and the empty string, all of which `Int(_:)` alone or a
            // lenient scan would let through in one form or another.
            guard !part.isEmpty, part.allSatisfy(\.isASCIIDigit), let value = Int(part) else {
                return nil
            }
            numbers.append(value)
        }
        return Parsed(major: numbers[0], minor: numbers[1], patch: numbers[2])
    }

    /// True when this build can safely interpret `version`.
    public static func isReadable(_ version: String) -> Bool {
        parse(version)?.major == supportedMajor
    }

    /// True when `version` may carry fields this build does not know about. Such packages are still
    /// readable, but a consumer that re-serializes them must preserve the unknown fields.
    public static func mayContainUnknownFields(_ version: String) -> Bool {
        guard let parsed = parse(version) else { return false }
        return parsed.major == supportedMajor && parsed.minor > supportedMinor
    }
}

extension Character {
    var isASCIIDigit: Bool { isASCII && isNumber }
}
