/// SHA-256, FIPS 180-4.
///
/// Implemented here rather than taken from CryptoKit or swift-crypto for one reason: this type is on
/// the path that has to produce byte-identical output to the Kotlin side, and it is the part of that
/// path most worth being able to test on a build machine. CryptoKit does not exist off Apple
/// platforms, and swift-crypto would make the core package -- whose whole point is that it depends
/// on nothing -- depend on a package graph.
///
/// It is used for content digests and corruption detection, never for authentication, so the
/// relevant risk is a wrong answer rather than a side channel. A wrong answer is exactly what the
/// NIST vectors in the tests, and the committed cross-language fixtures, would catch.
public struct Sha256 {

    private static let k: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    private var state: [UInt32] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ]
    private var buffer: [UInt8] = []
    private var totalBytes: UInt64 = 0

    public init() {
        buffer.reserveCapacity(64)
    }

    public mutating func update<Bytes: Sequence>(_ bytes: Bytes) where Bytes.Element == UInt8 {
        for byte in bytes {
            buffer.append(byte)
            totalBytes += 1
            if buffer.count == 64 {
                compress(buffer)
                buffer.removeAll(keepingCapacity: true)
            }
        }
    }

    public mutating func update(_ text: String) {
        update(Array(text.utf8))
    }

    public mutating func finalized() -> [UInt8] {
        // Padding: 0x80, then zeros, then the message length in bits as a big-endian UInt64.
        let bitLength = totalBytes &* 8
        var padding: [UInt8] = [0x80]
        let remainder = (buffer.count + 1) % 64
        let zeros = remainder <= 56 ? 56 - remainder : 120 - remainder
        padding.append(contentsOf: [UInt8](repeating: 0, count: zeros))
        for shift in stride(from: 56, through: 0, by: -8) {
            padding.append(UInt8((bitLength >> UInt64(shift)) & 0xFF))
        }

        // Appended through the buffer rather than compressed directly so that a caller who keeps
        // updating after this call is wrong in an obvious way rather than subtly.
        for byte in padding {
            buffer.append(byte)
            if buffer.count == 64 {
                compress(buffer)
                buffer.removeAll(keepingCapacity: true)
            }
        }

        var digest: [UInt8] = []
        digest.reserveCapacity(32)
        for word in state {
            digest.append(UInt8((word >> 24) & 0xFF))
            digest.append(UInt8((word >> 16) & 0xFF))
            digest.append(UInt8((word >> 8) & 0xFF))
            digest.append(UInt8(word & 0xFF))
        }
        return digest
    }

    private mutating func compress(_ block: [UInt8]) {
        var w = [UInt32](repeating: 0, count: 64)
        for index in 0..<16 {
            let offset = index * 4
            w[index] = UInt32(block[offset]) << 24
                | UInt32(block[offset + 1]) << 16
                | UInt32(block[offset + 2]) << 8
                | UInt32(block[offset + 3])
        }
        for index in 16..<64 {
            let s0 = rotr(w[index - 15], 7) ^ rotr(w[index - 15], 18) ^ (w[index - 15] >> 3)
            let s1 = rotr(w[index - 2], 17) ^ rotr(w[index - 2], 19) ^ (w[index - 2] >> 10)
            w[index] = w[index - 16] &+ s0 &+ w[index - 7] &+ s1
        }

        var a = state[0], b = state[1], c = state[2], d = state[3]
        var e = state[4], f = state[5], g = state[6], h = state[7]

        for index in 0..<64 {
            let s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
            let choose = (e & f) ^ (~e & g)
            let temp1 = h &+ s1 &+ choose &+ Sha256.k[index] &+ w[index]
            let s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
            let majority = (a & b) ^ (a & c) ^ (b & c)
            let temp2 = s0 &+ majority

            h = g; g = f; f = e
            e = d &+ temp1
            d = c; c = b; b = a
            a = temp1 &+ temp2
        }

        state[0] = state[0] &+ a
        state[1] = state[1] &+ b
        state[2] = state[2] &+ c
        state[3] = state[3] &+ d
        state[4] = state[4] &+ e
        state[5] = state[5] &+ f
        state[6] = state[6] &+ g
        state[7] = state[7] &+ h
    }

    private func rotr(_ value: UInt32, _ amount: UInt32) -> UInt32 {
        (value >> amount) | (value << (32 - amount))
    }

    // MARK: - Conveniences

    public static func hex(_ bytes: [UInt8]) -> String {
        var hasher = Sha256()
        hasher.update(bytes)
        return format(hasher.finalized())
    }

    public static func hex(_ text: String) -> String {
        hex(Array(text.utf8))
    }

    public static func format(_ digest: [UInt8]) -> String {
        let digits = Array("0123456789abcdef")
        var out = ""
        out.reserveCapacity(digest.count * 2)
        for byte in digest {
            out.append(digits[Int(byte >> 4)])
            out.append(digits[Int(byte & 0x0F)])
        }
        return out
    }

    /// `checksum.txt` in `sha256sum` format: `<hex>  <filename>`, sorted by filename, so a field
    /// engineer can verify a package with standard command-line tools.
    public static func checksumFile(_ digests: [String: String]) -> String {
        digests.keys.sorted()
            .map { "\(digests[$0]!)  \($0)" }
            .joined(separator: "\n") + "\n"
    }

    public static func parseChecksumFile(_ text: String) -> [String: String] {
        var result: [String: String] = [:]
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            // `sha256sum` separates the digest from the name with exactly two spaces, and a name may
            // itself contain spaces, so the split is on the first double space rather than on any
            // whitespace run.
            let characters = Array(line)
            var separator: Int?
            for index in 0..<max(0, characters.count - 1) where characters[index] == " " && characters[index + 1] == " " {
                separator = index
                break
            }
            guard let separator, separator > 0 else { continue }
            let hex = String(characters[0..<separator]).trimmingASCIIWhitespace()
            let name = String(characters[(separator + 2)...]).trimmingASCIIWhitespace()
            guard !hex.isEmpty, !name.isEmpty else { continue }
            result[name] = hex
        }
        return result
    }
}

extension StringProtocol {
    func trimmingASCIIWhitespace() -> String {
        var view = Substring(self)
        while let first = view.first, first == " " || first == "\t" || first == "\r" {
            view.removeFirst()
        }
        while let last = view.last, last == " " || last == "\t" || last == "\r" {
            view.removeLast()
        }
        return String(view)
    }
}
