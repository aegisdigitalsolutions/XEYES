/// RFC 4180 encoding and an incremental record parser, mirroring `core-model/csv/Csv.kt`.
///
/// The parser is a character-fed state machine rather than a line reader because a quoted field may
/// legitimately contain a newline -- an SSID can contain anything -- so splitting on `\n` first
/// would corrupt such rows. Feeding characters also keeps the type free of I/O while still allowing
/// a large file to be streamed in constant memory.
public enum Csv {

    public static let separator: Character = ","
    public static let quote: Character = "\""

    /// Quotes `value` only when required, escaping embedded quotes by doubling them.
    public static func encodeField(_ value: String?) -> String {
        guard let value else { return "" }
        let needsQuoting = value.contains { $0 == separator || $0 == quote || $0 == "\n" || $0 == "\r" }
        if !needsQuoting { return value }
        var out = ""
        out.reserveCapacity(value.count + 2)
        out.append(quote)
        for character in value {
            if character == quote { out.append(quote) }
            out.append(character)
        }
        out.append(quote)
        return out
    }

    public static func encodeRow(_ fields: [String?]) -> String {
        fields.map(encodeField).joined(separator: ",")
    }

    /// Parses complete CSV text. Convenience for tests and small files; large files use ``RecordParser``.
    public static func parse<S: StringProtocol>(_ text: S) -> [[String]] {
        var records: [[String]] = []
        var parser = RecordParser()
        parser.feed(text) { records.append($0) }
        parser.finish { records.append($0) }
        return records
    }

    /// Incremental parser. Call ``feed`` with successive chunks and ``finish`` once at the end; the
    /// callback receives one record per complete row.
    ///
    /// A field is returned as the empty string when it was empty in the source. Distinguishing an
    /// empty field from a quoted empty string is impossible in CSV, which is why JSON is the
    /// canonical format and both decode to nil.
    public struct RecordParser {
        private var fields: [String] = []
        private var current = ""
        private var inQuotes = false
        private var sawQuoteInQuotes = false
        private var started = false

        public init() {}

        public mutating func feed<S: StringProtocol>(_ chunk: S, onRecord: ([String]) -> Void) {
            for character in chunk {
                feedCharacter(character, onRecord: onRecord)
            }
        }

        private mutating func feedCharacter(_ character: Character, onRecord: ([String]) -> Void) {
            started = true
            if inQuotes {
                if sawQuoteInQuotes && character == Csv.quote {
                    current.append(Csv.quote)
                    sawQuoteInQuotes = false
                } else if sawQuoteInQuotes {
                    // The quote closed the field; reprocess this character outside quotes.
                    inQuotes = false
                    sawQuoteInQuotes = false
                    feedCharacter(character, onRecord: onRecord)
                } else if character == Csv.quote {
                    sawQuoteInQuotes = true
                } else {
                    current.append(character)
                }
                return
            }

            switch character {
            case Csv.quote:
                inQuotes = true
            case Csv.separator:
                endField()
            case "\n":
                endRecord(onRecord)
            case "\r":
                break // handled by the following '\n'; a lone '\r' is not a terminator here
            default:
                current.append(character)
            }
        }

        public mutating func finish(onRecord: ([String]) -> Void) {
            if inQuotes && sawQuoteInQuotes {
                inQuotes = false
                sawQuoteInQuotes = false
            }
            if started && (!current.isEmpty || !fields.isEmpty) {
                endRecord(onRecord)
            }
            started = false
        }

        private mutating func endField() {
            fields.append(current)
            current = ""
        }

        private mutating func endRecord(_ onRecord: ([String]) -> Void) {
            endField()
            onRecord(fields)
            fields.removeAll(keepingCapacity: true)
        }
    }
}
