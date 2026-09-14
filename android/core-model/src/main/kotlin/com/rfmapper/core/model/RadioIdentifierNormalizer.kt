package com.rfmapper.core.model

/**
 * Deterministic identifier normalization.
 *
 * Deduplication and fingerprint matching both compare identifiers byte-for-byte, so normalization
 * must be defined once and applied identically everywhere. Two spellings of one MAC address would
 * otherwise become two distinct radio sources and quietly halve every fingerprint's sample count.
 */
object RadioIdentifierNormalizer {

    /** The Bluetooth Base UUID; 16- and 32-bit short UUIDs expand against it. */
    private const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private val UNKNOWN_MAC_SENTINELS = setOf("02:00:00:00:00:00", "00:00:00:00:00:00")

    private val MAC_CANONICAL = Regex("""^([0-9a-f]{2}:){5}[0-9a-f]{2}$""")
    private val UUID_CANONICAL =
        Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")

    /**
     * Lowercase, colon-separated, zero-padded octets: `AA-BB-C-01-02-03` -> `aa:bb:0c:01:02:03`.
     * Returns null when [raw] is not a recognisable 6-octet address.
     */
    fun normalizeMac(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase() ?: return null
        if (trimmed.isEmpty()) return null

        // Checked before the canonical fast path: Android has historically reported these
        // unknown-BSSID sentinels instead of null, and storing one would create a phantom access
        // point shared across the whole site.
        if (trimmed in UNKNOWN_MAC_SENTINELS) return null
        if (MAC_CANONICAL.matches(trimmed)) return trimmed

        val separated = trimmed.split(':', '-', '.').filter { it.isNotEmpty() }
        val octets = when {
            separated.size == 6 -> separated
            separated.size == 1 && trimmed.length == 12 -> trimmed.chunked(2)
            else -> return null
        }
        if (octets.any { it.length > 2 || !it.all { c -> c.isHexDigit() } }) return null
        return octets.joinToString(":") { it.padStart(2, '0') }
    }

    /**
     * Lowercase hyphenated 8-4-4-4-12. A 16- or 32-bit Bluetooth short UUID is expanded against the
     * Bluetooth base UUID, so `180F`, `0000180f` and the full form collapse to one identifier.
     */
    fun normalizeUuid(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase()?.removePrefix("0x") ?: return null
        if (trimmed.isEmpty()) return null
        if (UUID_CANONICAL.matches(trimmed)) return trimmed

        val compact = trimmed.replace("-", "")
        if (!compact.all { it.isHexDigit() }) return null

        return when (compact.length) {
            4 -> "0000$compact$BLUETOOTH_BASE_SUFFIX"
            8 -> "$compact$BLUETOOTH_BASE_SUFFIX"
            32 -> buildString(36) {
                append(compact, 0, 8); append('-')
                append(compact, 8, 12); append('-')
                append(compact, 12, 16); append('-')
                append(compact, 16, 20); append('-')
                append(compact, 20, 32)
            }
            else -> null
        }
    }

    /** Uppercase hex, no separators. Returns null for empty payloads rather than an empty string. */
    fun normalizeHex(raw: String?): String? {
        val compact = raw?.replace(Regex("""[\s:\-]"""), "")?.uppercase() ?: return null
        if (compact.isEmpty()) return null
        if (!compact.all { it.isHexDigit() }) return null
        return compact
    }

    /**
     * Encodes BLE manufacturer data as the 4-hex-digit company identifier followed by its payload,
     * matching the canonical schema's `manufacturer_data` form.
     */
    fun encodeManufacturerData(companyId: Int, payload: ByteArray): String? {
        if (companyId < 0 || companyId > 0xFFFF) return null
        val prefix = companyId.toString(16).uppercase().padStart(4, '0')
        return prefix + payload.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
    }

    /**
     * SSIDs are preserved byte-for-byte: trailing spaces are legitimate and distinguishing, and
     * case-folding would merge genuinely different networks. A hidden network scans as an empty
     * SSID, which becomes null rather than `""`.
     */
    fun normalizeSsid(raw: String?): String? {
        if (raw == null) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        return unquoted.ifEmpty { null }
    }

    /**
     * True when a BLE address is locally administered (bit 0x02 of the first octet), which is how
     * both Android and iOS present privacy-rotating addresses.
     */
    fun isRandomBleAddress(normalizedMac: String): Boolean {
        val firstOctet = normalizedMac.substringBefore(':').toIntOrNull(16) ?: return false
        return (firstOctet and 0x02) != 0
    }

    fun identifierTypeForBleAddress(normalizedMac: String): IdentifierType =
        if (isRandomBleAddress(normalizedMac)) {
            IdentifierType.BLE_MAC_RANDOM
        } else {
            IdentifierType.BLE_MAC_PUBLIC
        }

    /** Wi-Fi channel from a centre frequency in MHz; null when the frequency is not a known channel. */
    fun channelForFrequency(frequencyMhz: Int?): Int? = when (frequencyMhz) {
        null -> null
        2484 -> 14
        in 2412..2472 -> (frequencyMhz - 2412) / 5 + 1
        in 5160..5885 -> (frequencyMhz - 5000) / 5
        in 5955..7115 -> (frequencyMhz - 5950) / 5
        in 58320..70200 -> (frequencyMhz - 56160) / 2160
        else -> null
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
