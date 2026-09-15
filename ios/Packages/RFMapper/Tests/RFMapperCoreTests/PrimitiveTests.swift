import XCTest
@testable import RFMapperCore

/// The primitives every package depends on. Each of these produces bytes that end up inside a
/// digest, so "nearly right" is indistinguishable from "wrong" downstream.
final class Sha256Tests: XCTestCase {

    /// FIPS 180-4 / NIST CAVS vectors. The implementation is hand-written -- see ``Sha256`` -- so it
    /// is checked against the standard rather than against itself.
    func testNistVectors() {
        XCTAssertEqual(
            Sha256.hex(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        XCTAssertEqual(
            Sha256.hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
        XCTAssertEqual(
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        )
        // Spans several blocks and an awkward padding boundary.
        XCTAssertEqual(
            Sha256.hex(String(repeating: "a", count: 1_000_000)),
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        )
    }

    /// 55, 56 and 64 bytes straddle the point where padding needs an extra block.
    func testPaddingBoundaries() {
        for length in [54, 55, 56, 57, 63, 64, 65, 119, 120] {
            let text = String(repeating: "x", count: length)
            var streamed = Sha256()
            for byte in Array(text.utf8) {
                streamed.update([byte])
            }
            XCTAssertEqual(
                Sha256.format(streamed.finalized()),
                Sha256.hex(text),
                "byte-at-a-time and whole-buffer hashing disagree at \(length) bytes"
            )
        }
    }

    func testChecksumFileIsSortedAndRoundTrips() {
        let digests = [
            "observations.csv": "aaaa",
            "manifest.json": "bbbb",
            "checksum.txt": "cccc",
        ]
        let text = Sha256.checksumFile(digests)

        XCTAssertEqual(text, "cccc  checksum.txt\nbbbb  manifest.json\naaaa  observations.csv\n")
        XCTAssertEqual(Sha256.parseChecksumFile(text), digests)
    }
}

final class Iso8601Tests: XCTestCase {

    func testFixedMillisecondWidth() {
        // The whole reason this type is hand-rolled: a formatter that dropped a zero millisecond
        // component would break the column contract and byte-identical re-export.
        XCTAssertEqual(Iso8601.format(0), "1970-01-01T00:00:00.000Z")
        XCTAssertEqual(Iso8601.format(1), "1970-01-01T00:00:00.001Z")
        XCTAssertEqual(Iso8601.format(1_000), "1970-01-01T00:00:01.000Z")
    }

    func testKnownInstants() {
        XCTAssertEqual(Iso8601.parseToEpochMillis("2026-05-04T10:00:00.000Z"), 1_777_888_800_000)
        XCTAssertEqual(Iso8601.format(1_777_888_800_000), "2026-05-04T10:00:00.000Z")
        // A leap day, and the end of a year.
        XCTAssertEqual(Iso8601.format(Iso8601.parseToEpochMillis("2024-02-29T23:59:59.999Z")!),
                       "2024-02-29T23:59:59.999Z")
        XCTAssertEqual(Iso8601.format(Iso8601.parseToEpochMillis("1999-12-31T23:59:59.999Z")!),
                       "1999-12-31T23:59:59.999Z")
    }

    func testRoundTripAcrossManyDays() {
        // Every day for forty years, plus a millisecond, catches an off-by-one in the civil-date
        // arithmetic that a handful of spot checks would miss.
        var millis: Int64 = 0
        while millis < 40 * 365 * Iso8601.millisPerDay {
            let text = Iso8601.format(millis)
            XCTAssertEqual(Iso8601.parseToEpochMillis(text), millis, "round trip failed at \(text)")
            millis += Iso8601.millisPerDay + 1
        }
    }

    func testStrictnessRejectsNearMisses() {
        // A lenient parser would accept these and they would then fail to re-export identically --
        // a determinism bug surfacing a long way from its cause.
        let rejected = [
            "2026-05-04T10:00:00Z",         // no milliseconds
            "2026-05-04T10:00:00.00Z",      // two digits
            "2026-05-04T10:00:00.0000Z",    // four digits
            "2026-05-04T10:00:00.000+00:00", // offset rather than Z
            "2026-05-04 10:00:00.000Z",     // space instead of T
            "2026-13-04T10:00:00.000Z",     // month 13
            "2026-02-30T10:00:00.000Z",     // February 30th
            "2023-02-29T10:00:00.000Z",     // not a leap year
            "2026-05-04T24:00:00.000Z",     // hour 24
            "",
        ]
        for text in rejected {
            XCTAssertNil(Iso8601.parseToEpochMillis(text), "should have rejected '\(text)'")
            XCTAssertFalse(Iso8601.isValid(text), "should have rejected '\(text)'")
        }
    }

    func testStartOfDayAndStampBeforeTheEpoch() {
        let inside = Iso8601.parseToEpochMillis("1969-07-20T20:17:40.000Z")!
        XCTAssertEqual(Iso8601.format(Iso8601.startOfUtcDay(inside)), "1969-07-20T00:00:00.000Z")
        XCTAssertEqual(Iso8601.utcDateStamp(inside), "1969-07-20")
    }
}

final class CanonicalNumberTests: XCTestCase {

    /// Java's `Double.toString` layout, which is what kotlinx.serialization emits and therefore what
    /// `observations.json` has to contain. Swift's own `description` disagrees on every case below
    /// the plain-decimal floor and above its ceiling.
    func testJavaLayout() {
        let cases: [(Double, String)] = [
            (0.0, "0.0"),
            (1.0, "1.0"),
            (2.0, "2.0"),
            (-1.0, "-1.0"),
            (0.9, "0.9"),
            (14.5, "14.5"),
            (51.50742, "51.50742"),
            (-0.12781, "-0.12781"),
            (100.0, "100.0"),
            (1_000_000.0, "1000000.0"),
            // At 10^7 Java leaves plain decimal for scientific notation.
            (10_000_000.0, "1.0E7"),
            (12_345_678.0, "1.2345678E7"),
            // And below 10^-3 likewise.
            (0.001, "0.001"),
            (0.0001, "1.0E-4"),
            (0.000015, "1.5E-5"),
            (1e-7, "1.0E-7"),
            (1e20, "1.0E20"),
        ]
        for (value, expected) in cases {
            XCTAssertEqual(CanonicalNumber.javaDouble(value), expected, "for \(value)")
        }
    }

    func testNonFiniteUsesJavaSpelling() {
        XCTAssertEqual(CanonicalNumber.javaDouble(.nan), "NaN")
        XCTAssertEqual(CanonicalNumber.javaDouble(.infinity), "Infinity")
        XCTAssertEqual(CanonicalNumber.javaDouble(-.infinity), "-Infinity")
    }
}

final class DecimalCellTests: XCTestCase {

    /// The CSV cell format: Kotlin's
    /// `BigDecimal.valueOf(v).setScale(6, HALF_UP).stripTrailingZeros().toPlainString()`.
    func testSixDecimalsHalfUpPlainNotation() {
        let cases: [(Double?, String?)] = [
            (nil, nil),
            (0.0, "0"),
            // Integral values lose the point entirely, so 11.0 round-trips through CSV as 11.
            (11.0, "11"),
            (2.0, "2"),
            (-3.0, "-3"),
            (14.5, "14.5"),
            (0.3, "0.3"),
            (51.50742, "51.50742"),
            (-0.12781, "-0.12781"),
            // Rounded at the sixth decimal, half away from zero.
            (0.12345649, "0.123456"),
            (0.12345651, "0.123457"),
            (0.1234565, "0.123457"),
            (-0.1234565, "-0.123457"),
            // Carries all the way out.
            (0.9999999, "1"),
            (9.9999999, "10"),
            // Below the sixth decimal there is nothing left to say.
            (1e-7, "0"),
            (1.5e-6, "0.000002"),
            (4.9e-7, "0"),
            (5.1e-7, "0.000001"),
            // Never exponential, however large.
            (12_345_678.0, "12345678"),
            (1e20, "100000000000000000000"),
        ]
        for (value, expected) in cases {
            XCTAssertEqual(ObservationCsvCodec.formatDecimal(value), expected, "for \(value as Any)")
        }
    }
}

final class RadioIdentifierNormalizerTests: XCTestCase {

    func testMacSeparatorsAndCaseAreCollapsed() {
        XCTAssertEqual(RadioIdentifierNormalizer.mac("AA-BB-CC-00-00-01"), "aa:bb:cc:00:00:01")
        XCTAssertEqual(RadioIdentifierNormalizer.mac("aabbcc000001"), "aa:bb:cc:00:00:01")
        XCTAssertNil(RadioIdentifierNormalizer.mac("aa:bb:cc:00:00"))
        XCTAssertNil(RadioIdentifierNormalizer.mac("not a mac"))
    }

    /// The asymmetry that would otherwise throw away iOS's only cross-platform join key.
    ///
    /// `CBUUID.uuidString` returns `"180D"` for the heart-rate service where Android returns the
    /// full 128-bit form. Both must normalize to the same string or the same tag sighted by an
    /// iPhone and by a Pixel becomes two unrelated radios.
    func testShortBluetoothUuidsExpandToWhatAndroidReports() {
        let heartRate = "0000180d-0000-1000-8000-00805f9b34fb"
        XCTAssertEqual(RadioIdentifierNormalizer.bluetoothUuid("180D"), heartRate)
        XCTAssertEqual(RadioIdentifierNormalizer.bluetoothUuid("0x180D"), heartRate)
        XCTAssertEqual(RadioIdentifierNormalizer.bluetoothUuid("0000180D"), heartRate)
        XCTAssertEqual(RadioIdentifierNormalizer.bluetoothUuid(heartRate.uppercased()), heartRate)

        // A vendor's own 128-bit UUID is left alone apart from case and separators.
        let vendor = "6b1a7e10-3c4d-4f5a-9b8c-1d2e3f405162"
        XCTAssertEqual(RadioIdentifierNormalizer.bluetoothUuid(vendor.uppercased()), vendor)

        // Lengths that are neither a shorthand nor a UUID are refused rather than padded into one.
        XCTAssertNil(RadioIdentifierNormalizer.bluetoothUuid("18"))
        XCTAssertNil(RadioIdentifierNormalizer.bluetoothUuid("180D0"))
        XCTAssertNil(RadioIdentifierNormalizer.bluetoothUuid(""))
    }

    func testAnSsidIsNotNormalizedBecauseItIsNotAnIdentity() {
        // Case and trailing whitespace are legitimate parts of a network name.
        XCTAssertEqual(RadioIdentifierNormalizer.ssid("Guest WiFi "), "Guest WiFi ")
        XCTAssertNil(RadioIdentifierNormalizer.ssid(""))
    }
}

final class CsvDialectTests: XCTestCase {

    func testQuotingOnlyWhenRequired() {
        XCTAssertEqual(Csv.encodeField("plain"), "plain")
        XCTAssertEqual(Csv.encodeField(nil), "")
        XCTAssertEqual(Csv.encodeField(""), "")
        XCTAssertEqual(Csv.encodeField("a,b"), "\"a,b\"")
        XCTAssertEqual(Csv.encodeField("say \"hi\""), "\"say \"\"hi\"\"\"")
        XCTAssertEqual(Csv.encodeField("two\nlines"), "\"two\nlines\"")
    }

    func testRoundTripOfTheAwkwardCases() {
        // An SSID can contain anything, which is why the parser is a character machine rather than
        // a line splitter.
        let fields = ["a,b", "say \"hi\"", "two\nlines", "", "trailing "]
        let encoded = Csv.encodeRow(fields)
        let parsed = Csv.parse(encoded + "\n")

        XCTAssertEqual(parsed.count, 1)
        XCTAssertEqual(parsed[0], fields)
    }

    func testEmbeddedNewlineDoesNotStartARecord() {
        let text = "one,\"line\nbreak\",three\nfour,five,six\n"
        XCTAssertEqual(Csv.parse(text), [
            ["one", "line\nbreak", "three"],
            ["four", "five", "six"],
        ])
    }
}

final class MiniJSONTests: XCTestCase {

    func testStringMapRejectsNonStringValues() {
        XCTAssertEqual(MiniJSON.parseStringMap("{\"a\":\"1\"}"), ["a": "1"])
        XCTAssertEqual(MiniJSON.parseStringMap("{}"), [:])
        // A number is not a string: the Kotlin decoder reports INVALID_METADATA_JSON rather than
        // coercing, and so must this.
        XCTAssertNil(MiniJSON.parseStringMap("{\"a\":1}"))
        XCTAssertNil(MiniJSON.parseStringMap("{\"a\":null}"))
        XCTAssertNil(MiniJSON.parseStringMap("[]"))
        XCTAssertNil(MiniJSON.parseStringMap("{"))
    }

    func testEscapesAndSurrogatePairs() {
        XCTAssertEqual(
            MiniJSON.parseStringMap(#"{"k":"a\"b\\c\nd\te"}"#),
            ["k": "a\"b\\c\nd\te"]
        )
        XCTAssertEqual(MiniJSON.parseStringMap(#"{"k":"\u00e9"}"#), ["k": "é"])
        // Outside the BMP, so it arrives as a surrogate pair.
        XCTAssertEqual(MiniJSON.parseStringMap(#"{"k":"\ud83d\ude00"}"#), ["k": "😀"])
        XCTAssertNil(MiniJSON.parseStringMap(#"{"k":"\ud83d"}"#), "an unpaired surrogate is not a scalar")
    }

    func testWriterAndReaderAgree() {
        let metadata = ["b": "2", "a": "1", "unicode": "café, \"quoted\"\n"]
        let written = CanonicalJSON.compact(.sortedMap(metadata))

        XCTAssertEqual(MiniJSON.parseStringMap(written), metadata)
        // Sorted, so the bytes do not depend on how the dictionary was built.
        XCTAssertTrue(written.hasPrefix("{\"a\":\"1\",\"b\":\"2\","), written)
    }
}
