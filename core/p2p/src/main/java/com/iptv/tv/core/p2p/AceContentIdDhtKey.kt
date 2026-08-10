package com.iptv.tv.core.p2p

/**
 * Verified 20-byte Ace Content ID used as a DHT lookup target.
 *
 * This type is intentionally distinct from [AceLiveSwarmKey] and from the application's ordinary
 * BitTorrent infohash identity. A 40-hex Content ID may have the same wire width as a BTIH, but it
 * must never be reclassified as one merely because of its textual shape.
 */
class AceContentIdDhtKey private constructor(
    bytes: ByteArray
) {
    private val value: ByteArray = bytes.copyOf()

    fun toByteArray(): ByteArray = value.copyOf()

    fun toHex(): String = buildString(BYTES * 2) {
        value.forEach { byte ->
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0F])
            append(HEX_DIGITS[byte.toInt() and 0x0F])
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AceContentIdDhtKey && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "AceContentIdDhtKey(redacted)"

    companion object {
        const val BYTES: Int = 20
        private const val HEX_DIGITS = "0123456789abcdef"
        private val HEX_PATTERN = Regex("^[0-9a-fA-F]{40}$")

        fun fromBytes(bytes: ByteArray): AceContentIdDhtKey {
            require(bytes.size == BYTES) { "Ace content-id DHT key must be exactly $BYTES bytes" }
            return AceContentIdDhtKey(bytes)
        }

        fun parseHex(value: String?): AceContentIdDhtKey? {
            val normalized = value?.trim()?.takeIf(HEX_PATTERN::matches) ?: return null
            val bytes = ByteArray(BYTES)
            for (index in bytes.indices) {
                val offset = index * 2
                bytes[index] = normalized.substring(offset, offset + 2).toInt(16).toByte()
            }
            return AceContentIdDhtKey(bytes)
        }
    }
}
