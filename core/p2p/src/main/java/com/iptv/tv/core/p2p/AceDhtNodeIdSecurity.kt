package com.iptv.tv.core.p2p

/**
 * BEP-42 IPv4 node-ID validation used only when selecting DHT write targets.
 *
 * Discovery remains tolerant of legacy/non-compliant responders. The stricter check is applied to
 * `get_peers` write tokens before `announce_peer`, because a malicious node with an arbitrary ID
 * must not be allowed to position itself near a target info-hash and become a preferred store.
 */
internal object AceDhtNodeIdSecurity {
    private val IPV4_MASK = intArrayOf(0x03, 0x0f, 0x3f, 0xff)
    private const val CRC32C_POLYNOMIAL_REVERSED = 0x82F63B78u

    fun isValidWriteTarget(nodeId: AceLiveDhtNodeId, host: String): Boolean {
        val ipv4 = parseIpv4(host) ?: return false
        if (isBep42ExemptIpv4(ipv4)) return true

        val id = nodeId.toByteArray()
        val r = id[19].toInt() and 0x07
        val masked = ByteArray(4) { index ->
            (ipv4[index].toInt() and IPV4_MASK[index]).toByte()
        }
        masked[0] = ((masked[0].toInt() and 0xff) or (r shl 5)).toByte()
        val crc = crc32c(masked)

        return (id[0].toInt() and 0xff) == ((crc shr 24) and 0xffu).toInt() &&
            (id[1].toInt() and 0xff) == ((crc shr 16) and 0xffu).toInt() &&
            (id[2].toInt() and 0xf8) == ((crc shr 8) and 0xf8u).toInt()
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return ByteArray(4) { index ->
            val value = parts[index].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value.toByte()
        }
    }

    private fun isBep42ExemptIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            a == 127
    }

    private fun crc32c(bytes: ByteArray): UInt {
        var crc = 0xffffffffu
        for (byte in bytes) {
            crc = crc xor (byte.toUInt() and 0xffu)
            repeat(8) {
                crc = if ((crc and 1u) != 0u) {
                    (crc shr 1) xor CRC32C_POLYNOMIAL_REVERSED
                } else {
                    crc shr 1
                }
            }
        }
        return crc xor 0xffffffffu
    }
}
