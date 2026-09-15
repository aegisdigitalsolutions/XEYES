/// A minimal, deterministic ZIP writer.
///
/// Written by hand rather than delegated to a library for the reason the Kotlin side discovered the
/// hard way: a zip's member timestamp is stored as *local* DOS time, so a stock zip writer produces
/// different bytes for identical data depending on the device's time zone. An export package is
/// identified by the SHA-256 of its bytes, so a Collector carried across the date line must not be
/// able to change the archive without changing an observation.
///
/// Every field that could carry ambient state is therefore pinned:
///
/// - **Modification time is the epoch**, matching `ZipExportSink.FIXED_ENTRY_TIME_MILLIS`. The epoch
///   is before 1980 in every time zone on earth, so the DOS date field clamps to the format's floor
///   everywhere rather than varying with the zone.
/// - **No extra fields, no comments, no data descriptors.**
/// - **Entries are written in the order given**, which the export engine fixes.
///
/// Entries are STORED rather than deflated. This trades package size for having no compression
/// dependency in a target that must build for iOS and for a Linux build machine, and both the Kotlin
/// `PackageValidator` and Python's `zipfile` read STORED entries without caring. It is a deliberate
/// and reversible trade: see `docs/20-ios-collector.md` §5.
public struct ZipWriter {

    /// The epoch, as DOS date and time. Both halves are the format's floor.
    ///
    /// DOS time counts years from 1980, so the epoch is not representable and clamps. Writing the
    /// floor explicitly, rather than converting a `Date` through a calendar, is what makes the
    /// output independent of the device.
    private static let dosTime: UInt16 = 0
    private static let dosDate: UInt16 = 0x0021 // 1980-01-01

    private struct Entry {
        let name: String
        let crc32: UInt32
        let size: UInt32
        let offset: UInt32
    }

    private var output: [UInt8] = []
    private var entries: [Entry] = []
    private var finished = false

    public init() {}

    public mutating func addEntry(name: String, bytes: [UInt8]) {
        precondition(!finished, "cannot add an entry after finishing the archive")
        let nameBytes = Array(name.utf8)
        let crc = Crc32.compute(bytes)
        let offset = UInt32(output.count)

        // Local file header
        append32(0x0403_4B50)
        append16(10) // version needed: 1.0, sufficient for STORED
        append16(0) // general purpose flags: none
        append16(0) // method 0 = STORED
        append16(ZipWriter.dosTime)
        append16(ZipWriter.dosDate)
        append32(crc)
        append32(UInt32(bytes.count))
        append32(UInt32(bytes.count))
        append16(UInt16(nameBytes.count))
        append16(0) // extra field length
        output.append(contentsOf: nameBytes)
        output.append(contentsOf: bytes)

        entries.append(Entry(name: name, crc32: crc, size: UInt32(bytes.count), offset: offset))
    }

    public mutating func finish() -> [UInt8] {
        precondition(!finished, "archive already finished")
        finished = true

        let directoryOffset = UInt32(output.count)
        for entry in entries {
            let nameBytes = Array(entry.name.utf8)
            append32(0x0201_4B50)
            append16(20) // version made by
            append16(10) // version needed
            append16(0) // flags
            append16(0) // method 0 = STORED
            append16(ZipWriter.dosTime)
            append16(ZipWriter.dosDate)
            append32(entry.crc32)
            append32(entry.size)
            append32(entry.size)
            append16(UInt16(nameBytes.count))
            append16(0) // extra
            append16(0) // comment
            append16(0) // disk number
            append16(0) // internal attributes
            append32(0) // external attributes
            append32(entry.offset)
            output.append(contentsOf: nameBytes)
        }
        let directorySize = UInt32(output.count) - directoryOffset

        // End of central directory
        append32(0x0605_4B50)
        append16(0) // this disk
        append16(0) // disk with the directory
        append16(UInt16(entries.count))
        append16(UInt16(entries.count))
        append32(directorySize)
        append32(directoryOffset)
        append16(0) // comment length

        return output
    }

    private mutating func append16(_ value: UInt16) {
        output.append(UInt8(value & 0xFF))
        output.append(UInt8((value >> 8) & 0xFF))
    }

    private mutating func append32(_ value: UInt32) {
        output.append(UInt8(value & 0xFF))
        output.append(UInt8((value >> 8) & 0xFF))
        output.append(UInt8((value >> 16) & 0xFF))
        output.append(UInt8((value >> 24) & 0xFF))
    }
}

/// CRC-32 as ZIP requires it (IEEE 802.3, reflected, initial and final xor of 0xFFFFFFFF).
enum Crc32 {

    private static let table: [UInt32] = {
        (0..<256).map { index -> UInt32 in
            var value = UInt32(index)
            for _ in 0..<8 {
                value = (value & 1) == 1 ? (value >> 1) ^ 0xEDB8_8320 : value >> 1
            }
            return value
        }
    }()

    static func compute<Bytes: Sequence>(_ bytes: Bytes) -> UInt32 where Bytes.Element == UInt8 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}
