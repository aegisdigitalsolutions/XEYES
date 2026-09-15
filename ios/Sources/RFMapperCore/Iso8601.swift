/// ISO-8601 UTC timestamps with fixed millisecond precision, e.g. `2026-09-14T08:19:04.312Z`.
///
/// Fixed precision matters and is the reason this is hand-rolled rather than delegated to
/// `ISO8601DateFormatter`: a formatter that omits a zero millisecond component would make two
/// timestamps one millisecond apart serialize with different widths, breaking both the CSV column
/// contract and byte-identical re-export. The Kotlin side documents the same hazard.
///
/// Hand-rolling also keeps the type free of Foundation, so the export path is testable on any
/// platform, and avoids `Date`'s binary floating point entirely: a timestamp here is an integer
/// count of milliseconds from the epoch and never round-trips through a Double.
public enum Iso8601 {

    public static let millisPerDay: Int64 = 86_400_000

    public static func isValid(_ text: String) -> Bool {
        parseToEpochMillis(text) != nil
    }

    /// Formats `epochMillis` as `YYYY-MM-DDTHH:MM:SS.mmmZ`.
    public static func format(_ epochMillis: Int64) -> String {
        let days = floorDiv(epochMillis, millisPerDay)
        let millisOfDay = epochMillis - days * millisPerDay

        let (year, month, day) = civilFromDays(days)
        let millis = Int(millisOfDay % 1000)
        let totalSeconds = Int(millisOfDay / 1000)
        let hour = totalSeconds / 3600
        let minute = (totalSeconds % 3600) / 60
        let second = totalSeconds % 60

        var out = ""
        out.reserveCapacity(24)
        out += pad(year, 4)
        out += "-"
        out += pad(month, 2)
        out += "-"
        out += pad(day, 2)
        out += "T"
        out += pad(hour, 2)
        out += ":"
        out += pad(minute, 2)
        out += ":"
        out += pad(second, 2)
        out += "."
        out += pad(millis, 3)
        out += "Z"
        return out
    }

    /// Parses the canonical form, returning nil for anything else.
    ///
    /// Strict by design. A lenient parser here would accept a timestamp whose width differs from the
    /// canonical form, and that value would then fail to re-export identically -- a determinism bug
    /// surfacing far from its cause.
    public static func parseToEpochMillis(_ text: String) -> Int64? {
        let characters = Array(text.utf8)
        guard characters.count == 24 else { return nil }

        func digits(_ range: Range<Int>) -> Int? {
            var value = 0
            for index in range {
                let byte = characters[index]
                guard byte >= 48, byte <= 57 else { return nil }
                value = value * 10 + Int(byte - 48)
            }
            return value
        }
        func literal(_ index: Int, _ character: UInt8) -> Bool { characters[index] == character }

        guard literal(4, UInt8(ascii: "-")), literal(7, UInt8(ascii: "-")),
              literal(10, UInt8(ascii: "T")), literal(13, UInt8(ascii: ":")),
              literal(16, UInt8(ascii: ":")), literal(19, UInt8(ascii: ".")),
              literal(23, UInt8(ascii: "Z"))
        else { return nil }

        guard let year = digits(0..<4), let month = digits(5..<7), let day = digits(8..<10),
              let hour = digits(11..<13), let minute = digits(14..<16),
              let second = digits(17..<19), let millis = digits(20..<23)
        else { return nil }

        guard month >= 1, month <= 12, day >= 1, day <= daysInMonth(year: year, month: month),
              hour <= 23, minute <= 59, second <= 59
        else { return nil }

        let days = daysFromCivil(year: year, month: month, day: day)
        return days * millisPerDay
            + Int64(hour) * 3_600_000
            + Int64(minute) * 60_000
            + Int64(second) * 1_000
            + Int64(millis)
    }

    /// Start of the UTC day containing `epochMillis`.
    public static func startOfUtcDay(_ epochMillis: Int64) -> Int64 {
        floorDiv(epochMillis, millisPerDay) * millisPerDay
    }

    /// `YYYY-MM-DD` for the UTC day containing `epochMillis`; used in export package names.
    public static func utcDateStamp(_ epochMillis: Int64) -> String {
        String(format(epochMillis).prefix(10))
    }

    // MARK: - Calendar arithmetic

    // Howard Hinnant's days<->civil algorithms, which are exact over the whole proleptic Gregorian
    // range and involve no floating point. Used rather than Calendar so that a device's locale or
    // calendar setting cannot influence the bytes of an export package.

    static func daysFromCivil(year: Int, month: Int, day: Int) -> Int64 {
        let y = year - (month <= 2 ? 1 : 0)
        let era = (y >= 0 ? y : y - 399) / 400
        let yearOfEra = y - era * 400
        let dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return Int64(era) * 146_097 + Int64(dayOfEra) - 719_468
    }

    static func civilFromDays(_ days: Int64) -> (year: Int, month: Int, day: Int) {
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let dayOfEra = Int(z - era * 146_097)
        let yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146_096) / 365
        let year = yearOfEra + Int(era) * 400
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let monthPrime = (5 * dayOfYear + 2) / 153
        let day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
        let month = monthPrime + (monthPrime < 10 ? 3 : -9)
        return (year + (month <= 2 ? 1 : 0), month, day)
    }

    private static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        default:
            let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            return leap ? 29 : 28
        }
    }

    static func floorDiv(_ value: Int64, _ divisor: Int64) -> Int64 {
        let quotient = value / divisor
        return (value % divisor != 0 && (value < 0) != (divisor < 0)) ? quotient - 1 : quotient
    }

    private static func pad(_ value: Int, _ width: Int) -> String {
        let text = String(value)
        if text.count >= width { return text }
        return String(repeating: "0", count: width - text.count) + text
    }
}
