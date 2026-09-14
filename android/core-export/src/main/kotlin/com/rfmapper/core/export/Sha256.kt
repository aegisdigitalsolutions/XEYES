package com.rfmapper.core.export

import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

object Sha256 {

    fun hex(bytes: ByteArray): String = format(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun format(digest: ByteArray): String = buildString(digest.size * 2) {
        for (byte in digest) append("%02x".format(byte.toInt() and 0xFF))
    }

    /**
     * `checksum.txt` in `sha256sum` format: `<hex>  <filename>`, sorted by filename, so a field
     * engineer can verify a package with standard command-line tools.
     */
    fun checksumFile(digests: Map<String, String>): String =
        digests.entries
            .sortedBy { it.key }
            .joinToString(separator = "\n", postfix = "\n") { (name, hex) -> "$hex  $name" }

    fun parseChecksumFile(text: String): Map<String, String> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf("  ")
                if (separator <= 0) return@mapNotNull null
                val hex = line.substring(0, separator).trim()
                val name = line.substring(separator + 2).trim()
                if (hex.isEmpty() || name.isEmpty()) null else name to hex
            }
            .toMap()
}

/**
 * Computes a SHA-256 digest of everything written through it.
 *
 * Hashing as the bytes stream past means a 500k-row export never needs to be buffered or re-read to
 * produce its checksum.
 */
class DigestingOutputStream(downstream: OutputStream) : FilterOutputStream(downstream) {

    private val digest = MessageDigest.getInstance("SHA-256")
    private var byteCount = 0L

    override fun write(byte: Int) {
        out.write(byte)
        digest.update(byte.toByte())
        byteCount++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        digest.update(bytes, offset, length)
        byteCount += length
    }

    fun digestHex(): String = Sha256.format(digest.digest())

    val bytesWritten: Long get() = byteCount
}
