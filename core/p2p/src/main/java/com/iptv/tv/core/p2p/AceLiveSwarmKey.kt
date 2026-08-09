package com.iptv.tv.core.p2p

/**
 * Verified 20-byte swarm identifier used by Ace Live peer handshakes and BitTorrent-compatible
 * discovery transports.
 *
 * This type is intentionally distinct from an Ace `content_id` and from the application's ordinary
 * BitTorrent routing identity. Parsing accepts exactly 40 hexadecimal characters and never performs
 * decimal conversion or content-id inference.
 */
class AceLiveSwarmKey private constructor(
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
        other is AceLiveSwarmKey && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "AceLiveSwarmKey(redacted)"

    companion object {
        const val BYTES: Int = 20
        private const val HEX_DIGITS = "0123456789abcdef"
        private val HEX_PATTERN = Regex("^[0-9a-fA-F]{40}$")

        fun fromBytes(bytes: ByteArray): AceLiveSwarmKey {
            require(bytes.size == BYTES) { "Ace Live swarm key must be exactly $BYTES bytes" }
            return AceLiveSwarmKey(bytes)
        }

        fun parseHex(value: String?): AceLiveSwarmKey? {
            val normalized = value?.trim()?.takeIf(HEX_PATTERN::matches) ?: return null
            val bytes = ByteArray(BYTES)
            for (index in bytes.indices) {
                val offset = index * 2
                bytes[index] = normalized.substring(offset, offset + 2).toInt(16).toByte()
            }
            return AceLiveSwarmKey(bytes)
        }
    }
}
