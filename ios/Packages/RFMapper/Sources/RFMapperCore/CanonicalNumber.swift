/// Number formatting that agrees with the Kotlin producer.
///
/// `observations.json` is written by the Android Collector via kotlinx.serialization, which renders a
/// Double with Kotlin's `toString()` -- that is, Java's `Double.toString`. Swift's own
/// `Double.description` also emits the shortest round-tripping decimal, but lays it out differently:
/// Swift writes `1e-05` and `1e+20` where Java writes `1.0E-5` and `1.0E20`, and the two disagree
/// about when to leave plain decimal notation at all.
///
/// Since a package's `checksum.txt` is a digest of these exact bytes, "close enough" is not a
/// category that exists here. This takes Swift's shortest-digits result and re-lays it out under
/// Java's documented rules, so an iPhone and a Pixel encoding the same observation produce the same
/// file.
enum CanonicalNumber {

    /// Java's `Double.toString` layout, given Swift's shortest round-tripping digits.
    ///
    /// Java's rule, from the `Double.toString` specification: if the magnitude is at least 10^-3 and
    /// below 10^7 it is written in plain decimal with at least one fractional digit; otherwise it is
    /// written in "computerized scientific notation", `d.dddEn`.
    static func javaDouble(_ value: Double) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
        if value == 0 { return value.sign == .minus ? "-0.0" : "0.0" }

        let negative = value < 0
        guard let decimal = Decomposed(magnitude: abs(value)) else { return value.description }
        let body = decimal.isPlainRange ? decimal.plain() : decimal.scientific()
        return negative ? "-" + body : body
    }

    /// A positive finite double as `0.<digits> * 10^exponent`, with no leading or trailing zeros in
    /// `digits`. This normal form makes both of Java's layouts a matter of moving a point.
    struct Decomposed {
        let digits: String
        let exponent: Int

        init?(magnitude: Double) {
            // Swift's description is the shortest decimal that round-trips, which is exactly the
            // digit sequence Java's specification also calls for. Only the arrangement differs, so
            // reading the digits back out and re-arranging them cannot lose precision.
            var text = magnitude.description
            var exponentFromE = 0
            if let marker = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
                guard let parsed = Int(text[text.index(after: marker)...]) else { return nil }
                exponentFromE = parsed
                text = String(text[text.startIndex..<marker])
            }

            let integerPart: Substring
            let fractionPart: Substring
            if let point = text.firstIndex(of: ".") {
                integerPart = text[text.startIndex..<point]
                fractionPart = text[text.index(after: point)...]
            } else {
                integerPart = Substring(text)
                fractionPart = ""
            }

            var allDigits = String(integerPart) + String(fractionPart)
            guard allDigits.allSatisfy(\.isASCIIDigit), !allDigits.isEmpty else { return nil }

            // Point sits after the integer digits; shift by any exponent that was present.
            var pointPosition = integerPart.count + exponentFromE

            while allDigits.first == "0" {
                allDigits.removeFirst()
                pointPosition -= 1
            }
            while allDigits.last == "0" {
                allDigits.removeLast()
            }
            guard !allDigits.isEmpty else { return nil }

            digits = allDigits
            exponent = pointPosition
        }

        /// Java uses plain decimal for magnitudes in [10^-3, 10^7).
        var isPlainRange: Bool { exponent >= -2 && exponent <= 7 }

        func plain() -> String {
            if exponent <= 0 {
                return "0." + String(repeating: "0", count: -exponent) + digits
            }
            if exponent >= digits.count {
                return digits + String(repeating: "0", count: exponent - digits.count) + ".0"
            }
            let split = digits.index(digits.startIndex, offsetBy: exponent)
            return String(digits[digits.startIndex..<split]) + "." + String(digits[split...])
        }

        func scientific() -> String {
            let lead = digits.first!
            let rest = digits.dropFirst()
            // Java always writes at least one fractional digit, so a single-digit mantissa gains
            // ".0" rather than being emitted bare.
            return "\(lead).\(rest.isEmpty ? "0" : String(rest))E\(exponent - 1)"
        }
    }
}
