/// A JSON writer with the same output as the Kotlin producer's `RfMapperJson`.
///
/// `JSONEncoder` is not used, and the reason is specific rather than stylistic. The package contract
/// requires three properties that `JSONEncoder` will not give:
///
/// 1. **Declared field order.** kotlinx.serialization emits properties in declaration order.
///    `JSONEncoder` offers insertion order (unspecified for synthesised `Codable`) or
///    `.sortedKeys`, and neither is the Kotlin order.
/// 2. **Explicit nulls.** `RfMapperJson` sets `explicitNulls = true` so an observation always shows
///    all 29 fields and a reader never has to consult the schema to learn whether a field was
///    absent or null. `JSONEncoder` omits nil.
/// 3. **Byte-stable numbers.** See ``CanonicalNumber``.
///
/// Since `checksum.txt` digests these bytes, all three are part of the contract.
public enum JSONValue {
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case null
    case array([JSONValue])

    /// An *ordered* object. Key order is part of the output, so it cannot be a dictionary.
    case object([(String, JSONValue)])

    public static func string(_ value: String?) -> JSONValue {
        value.map { JSONValue.string($0) } ?? .null
    }

    public static func int(_ value: Int?) -> JSONValue {
        value.map { JSONValue.int(Int64($0)) } ?? .null
    }

    public static func double(_ value: Double?) -> JSONValue {
        value.map { JSONValue.double($0) } ?? .null
    }

    /// A string-keyed map, sorted by key. Used for the metadata and count maps, whose key order is
    /// not otherwise defined; sorting makes it reproducible.
    public static func sortedMap(_ value: [String: String]) -> JSONValue {
        .object(value.keys.sorted().map { ($0, .string(value[$0]!)) })
    }

    public static func sortedCounts(_ value: [String: Int64]) -> JSONValue {
        .object(value.keys.sorted().map { ($0, .int(value[$0]!)) })
    }
}

public enum CanonicalJSON {

    /// Compact form, matching `RfMapperJson.compact`: no whitespace at all.
    public static func compact(_ value: JSONValue) -> String {
        var out = ""
        write(value, into: &out, indent: nil, depth: 0)
        return out
    }

    /// Pretty form, matching `RfMapperJson.pretty`, which sets `prettyPrintIndent = "  "`.
    public static func pretty(_ value: JSONValue) -> String {
        var out = ""
        write(value, into: &out, indent: "  ", depth: 0)
        return out
    }

    private static func write(_ value: JSONValue, into out: inout String, indent: String?, depth: Int) {
        switch value {
        case .null:
            out += "null"
        case .bool(let flag):
            out += flag ? "true" : "false"
        case .int(let number):
            out += String(number)
        case .double(let number):
            out += CanonicalNumber.javaDouble(number)
        case .string(let text):
            writeString(text, into: &out)
        case .array(let items):
            writeContainer(open: "[", close: "]", count: items.count, into: &out, indent: indent, depth: depth) { index, out, indent, depth in
                write(items[index], into: &out, indent: indent, depth: depth)
            }
        case .object(let entries):
            writeContainer(open: "{", close: "}", count: entries.count, into: &out, indent: indent, depth: depth) { index, out, indent, depth in
                writeString(entries[index].0, into: &out)
                out += indent == nil ? ":" : ": "
                write(entries[index].1, into: &out, indent: indent, depth: depth)
            }
        }
    }

    private static func writeContainer(
        open: String,
        close: String,
        count: Int,
        into out: inout String,
        indent: String?,
        depth: Int,
        element: (Int, inout String, String?, Int) -> Void
    ) {
        // kotlinx renders an empty collection as `[]` / `{}` with no inner newline.
        if count == 0 {
            out += open + close
            return
        }
        out += open
        for index in 0..<count {
            if index > 0 { out += "," }
            if let indent {
                out += "\n" + String(repeating: indent, count: depth + 1)
            }
            element(index, &out, indent, depth + 1)
        }
        if let indent {
            out += "\n" + String(repeating: indent, count: depth)
        }
        out += close
    }

    private static func writeString(_ text: String, into out: inout String) {
        out += "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                // kotlinx escapes only the characters JSON requires, and emits everything else --
                // including non-ASCII -- as literal UTF-8. Escaping more would still be valid JSON
                // but would not be the same bytes.
                if scalar.value < 0x20 {
                    out += "\\u" + hex4(scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }

    private static func hex4(_ value: UInt32) -> String {
        let digits = Array("0123456789abcdef")
        return String([
            digits[Int((value >> 12) & 0xF)],
            digits[Int((value >> 8) & 0xF)],
            digits[Int((value >> 4) & 0xF)],
            digits[Int(value & 0xF)],
        ])
    }
}
