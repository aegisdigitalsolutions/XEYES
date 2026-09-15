/// A small JSON reader.
///
/// The writing side is ``CanonicalJSON``, which exists because the output bytes are contract. This is
/// the counterpart for reading, and it exists for a duller reason: `JSONSerialization` lives in
/// Foundation, and keeping the core package Foundation-free is what allows the whole export path --
/// including the cross-language contract tests -- to be compiled and run on a Linux build machine
/// rather than only on a Mac.
///
/// It implements RFC 8259 with no extensions: no comments, no trailing commas, no unquoted keys.
public enum MiniJSON {

    public indirect enum Value: Equatable {
        case string(String)
        case number(Double)
        case bool(Bool)
        case null
        case array([Value])
        case object([String: Value])

        public var asString: String? {
            if case .string(let text) = self { return text }
            return nil
        }

        public var asObject: [String: Value]? {
            if case .object(let members) = self { return members }
            return nil
        }

        public var asArray: [Value]? {
            if case .array(let items) = self { return items }
            return nil
        }

        public var asDouble: Double? {
            if case .number(let value) = self { return value }
            return nil
        }

        public var asInt: Int? {
            guard case .number(let value) = self, value == value.rounded() else { return nil }
            return Int(value)
        }
    }

    public static func parse(_ text: String) -> Value? {
        var scanner = Scanner(Array(text.unicodeScalars))
        guard let value = scanner.parseValue() else { return nil }
        scanner.skipWhitespace()
        return scanner.isAtEnd ? value : nil
    }

    /// Parses an object whose every value is a string, which is what ``Observation/metadata`` is.
    ///
    /// Returns nil when the text is not an object or when any value is not a string, so a caller can
    /// report `INVALID_METADATA_JSON` rather than silently dropping a field. The Kotlin decoder makes
    /// the same distinction.
    public static func parseStringMap(_ text: String) -> [String: String]? {
        guard let value = parse(text), let members = value.asObject else { return nil }
        var result: [String: String] = [:]
        result.reserveCapacity(members.count)
        for (key, member) in members {
            guard let string = member.asString else { return nil }
            result[key] = string
        }
        return result
    }

    struct Scanner {
        private let scalars: [Unicode.Scalar]
        private var index = 0

        init(_ scalars: [Unicode.Scalar]) {
            self.scalars = scalars
        }

        var isAtEnd: Bool { index >= scalars.count }

        mutating func skipWhitespace() {
            while index < scalars.count {
                switch scalars[index] {
                case " ", "\t", "\n", "\r": index += 1
                default: return
                }
            }
        }

        mutating func parseValue() -> Value? {
            skipWhitespace()
            guard index < scalars.count else { return nil }
            switch scalars[index] {
            case "{": return parseObject()
            case "[": return parseArray()
            case "\"": return parseString().map(Value.string)
            case "t": return expect("true") ? .bool(true) : nil
            case "f": return expect("false") ? .bool(false) : nil
            case "n": return expect("null") ? .null : nil
            default: return parseNumber()
            }
        }

        private mutating func expect(_ word: String) -> Bool {
            for scalar in word.unicodeScalars {
                guard index < scalars.count, scalars[index] == scalar else { return false }
                index += 1
            }
            return true
        }

        private mutating func parseObject() -> Value? {
            index += 1 // '{'
            var members: [String: Value] = [:]
            skipWhitespace()
            if index < scalars.count, scalars[index] == "}" {
                index += 1
                return .object(members)
            }
            while true {
                skipWhitespace()
                guard index < scalars.count, scalars[index] == "\"", let key = parseString() else {
                    return nil
                }
                skipWhitespace()
                guard index < scalars.count, scalars[index] == ":" else { return nil }
                index += 1
                guard let value = parseValue() else { return nil }
                members[key] = value
                skipWhitespace()
                guard index < scalars.count else { return nil }
                if scalars[index] == "," {
                    index += 1
                    continue
                }
                if scalars[index] == "}" {
                    index += 1
                    return .object(members)
                }
                return nil
            }
        }

        private mutating func parseArray() -> Value? {
            index += 1 // '['
            var items: [Value] = []
            skipWhitespace()
            if index < scalars.count, scalars[index] == "]" {
                index += 1
                return .array(items)
            }
            while true {
                guard let value = parseValue() else { return nil }
                items.append(value)
                skipWhitespace()
                guard index < scalars.count else { return nil }
                if scalars[index] == "," {
                    index += 1
                    continue
                }
                if scalars[index] == "]" {
                    index += 1
                    return .array(items)
                }
                return nil
            }
        }

        private mutating func parseString() -> String? {
            index += 1 // opening quote
            var out = String.UnicodeScalarView()
            while index < scalars.count {
                let scalar = scalars[index]
                if scalar == "\"" {
                    index += 1
                    return String(out)
                }
                if scalar == "\\" {
                    index += 1
                    guard index < scalars.count else { return nil }
                    switch scalars[index] {
                    case "\"": out.append("\"")
                    case "\\": out.append("\\")
                    case "/": out.append("/")
                    case "b": out.append("\u{08}")
                    case "f": out.append("\u{0C}")
                    case "n": out.append("\n")
                    case "r": out.append("\r")
                    case "t": out.append("\t")
                    case "u":
                        guard let scalar = parseUnicodeEscape() else { return nil }
                        out.append(scalar)
                        continue
                    default: return nil
                    }
                    index += 1
                    continue
                }
                out.append(scalar)
                index += 1
            }
            return nil
        }

        /// Reads `\uXXXX`, joining a surrogate pair when one is present.
        private mutating func parseUnicodeEscape() -> Unicode.Scalar? {
            guard let high = readHex4() else { return nil }
            if high >= 0xD800, high <= 0xDBFF {
                // A high surrogate is only meaningful paired; an unpaired one is not a scalar.
                guard index + 1 < scalars.count, scalars[index] == "\\", scalars[index + 1] == "u" else {
                    return nil
                }
                // Advance onto the `u`, not past it: `readHex4` consumes the `u` itself.
                index += 1
                guard let low = readHex4(), low >= 0xDC00, low <= 0xDFFF else { return nil }
                let combined = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00)
                return Unicode.Scalar(combined)
            }
            return Unicode.Scalar(high)
        }

        private mutating func readHex4() -> UInt32? {
            index += 1 // 'u'
            guard index + 4 <= scalars.count else { return nil }
            var value: UInt32 = 0
            for _ in 0..<4 {
                guard let digit = scalars[index].hexDigitValue else { return nil }
                value = value << 4 | digit
                index += 1
            }
            return value
        }

        private mutating func parseNumber() -> Value? {
            let start = index
            if index < scalars.count, scalars[index] == "-" { index += 1 }
            while index < scalars.count, isNumberScalar(scalars[index]) { index += 1 }
            guard start < index else { return nil }
            let text = String(String.UnicodeScalarView(scalars[start..<index]))
            guard let value = Double(text) else { return nil }
            return .number(value)
        }

        private func isNumberScalar(_ scalar: Unicode.Scalar) -> Bool {
            switch scalar {
            case "0"..."9", ".", "e", "E", "+", "-": return true
            default: return false
            }
        }
    }
}

private extension Unicode.Scalar {
    var hexDigitValue: UInt32? {
        switch self {
        case "0"..."9": return value - 0x30
        case "a"..."f": return value - 0x61 + 10
        case "A"..."F": return value - 0x41 + 10
        default: return nil
        }
    }
}
