package com.iptv.tv.core.p2p

/**
 * BEP-42 IPv4 node-ID validation and generation.
 *
 * Discovery remains tolerant of legacy/non-compliant responders. Strict validation is applied to
 * DHT write targets, while client identity generation is enabled only after independent responders
 * agree on the client's globally-routable external IPv4 address.
 */
internal object AceDhtNodeIdSecurity {
    private val IPV4_MASK = intArrayOf(0x03, 0x0f, 0x3f, 0xff)
    private const val CRC32C_POLYNOMIAL_REVERSED = 0x82F63B78u
    private const val IPV4_MASK_32 = 0xffff_ffffL

    fun isValidWriteTarget(nodeId: AceLiveDhtNodeId, host: String): Boolean {
        val ipv4 = parseIpv4(host) ?: return false
        if (isBep42ExemptIpv4(ipv4)) return true

        val id = nodeId.toByteArray()
        val crc = bep42Crc(ipv4, id[19].toInt() and 0x07)
        return (id[0].toInt() and 0xff) == ((crc shr 24) and 0xffu).toInt() &&
            (id[1].toInt() and 0xff) == ((crc shr 16) and 0xffu).toInt() &&
            (id[2].toInt() and 0xf8) == ((crc shr 8) and 0xf8u).toInt()
    }

    fun createCompatibleNodeId(host: String, randomBytes: ByteArray): AceLiveDhtNodeId? {
        val ipv4 = parseIpv4(host) ?: return null
        require(randomBytes.size == AceLiveDhtNodeId.BYTES) {
            "BEP-42 random node-id seed must be exactly ${AceLiveDhtNodeId.BYTES} bytes"
        }
        val id = randomBytes.copyOf()
        val crc = bep42Crc(ipv4, id[19].toInt() and 0x07)
        id[0] = (crc shr 24).toByte()
        id[1] = (crc shr 16).toByte()
        id[2] = (((crc shr 8) and 0xf8u).toInt() or (id[2].toInt() and 0x07)).toByte()
        return AceLiveDhtNodeId.fromBytes(id)
    }

    fun isGloballyRoutableIpv4(host: String): Boolean {
        val bytes = parseIpv4(host) ?: return false
        val value = ipv4ToLong(bytes)
        return SPECIAL_USE_IPV4_RANGES.none { cidr -> containsIpv4(cidr, value) }
    }

    fun ipv4Prefix24(host: String): Int? {
        val bytes = parseIpv4(host) ?: return null
        return ((bytes[0].toInt() and 0xff) shl 16) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            (bytes[2].toInt() and 0xff)
    }

    private fun bep42Crc(ipv4: ByteArray, r: Int): UInt {
        val masked = ByteArray(4) { index ->
            (ipv4[index].toInt() and IPV4_MASK[index]).toByte()
        }
        masked[0] = ((masked[0].toInt() and 0xff) or (r shl 5)).toByte()
        return crc32c(masked)
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

    private data class Ipv4Cidr(val network: Long, val prefixBits: Int)

    private val SPECIAL_USE_IPV4_RANGES = listOf(
        cidr(0, 0, 0, 0, 8),
        cidr(10, 0, 0, 0, 8),
        cidr(100, 64, 0, 0, 10),
        cidr(127, 0, 0, 0, 8),
        cidr(169, 254, 0, 0, 16),
        cidr(172, 16, 0, 0, 12),
        cidr(192, 0, 0, 0, 24),
        cidr(192, 0, 2, 0, 24),
        cidr(192, 31, 196, 0, 24),
        cidr(192, 52, 193, 0, 24),
        cidr(192, 88, 99, 0, 24),
        cidr(192, 168, 0, 0, 16),
        cidr(192, 175, 48, 0, 24),
        cidr(198, 18, 0, 0, 15),
        cidr(198, 51, 100, 0, 24),
        cidr(203, 0, 113, 0, 24),
        cidr(224, 0, 0, 0, 4),
        cidr(240, 0, 0, 0, 4)
    )

    private fun cidr(a: Int, b: Int, c: Int, d: Int, prefixBits: Int): Ipv4Cidr =
        Ipv4Cidr(
            network = ((a.toLong() shl 24) or (b.toLong() shl 16) or
                (c.toLong() shl 8) or d.toLong()) and IPV4_MASK_32,
            prefixBits = prefixBits
        )

    private fun ipv4ToLong(bytes: ByteArray): Long = bytes.fold(0L) { value, byte ->
        ((value shl 8) or (byte.toLong() and 0xffL)) and IPV4_MASK_32
    }

    private fun containsIpv4(cidr: Ipv4Cidr, value: Long): Boolean {
        val mask = (IPV4_MASK_32 shl (32 - cidr.prefixBits)) and IPV4_MASK_32
        return (value and mask) == (cidr.network and mask)
    }
}
