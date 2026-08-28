package com.iptv.tv.core.p2p

internal const val ACE_LIVE_EXTENDED_MESSAGE_ID: Int = 20
internal const val ACE_LIVE_EXTENDED_HANDSHAKE_ID: Int = 0
internal const val ACE_LIVE_LOCAL_UT_PEX_MESSAGE_ID: Int = 3

internal data class AceLivePeerExchangeHandshakeUpdate(
    val utPexPresent: Boolean,
    val utPexMessageId: Int?
)

internal data class AceLivePeerExchangeMessage(
    val added: List<AceLiveTcpPeerEndpoint>
)

/** Bounded BEP-10/BEP-11 parser. PEX endpoints remain untrusted discovery candidates. */
internal object AceLivePeerExchangeCodec {
    const val MAX_ADDED_IPV4_PEERS: Int = 50

    fun decodeExtensionHandshake(payload: ByteArray): AceLivePeerExchangeHandshakeUpdate? =
        runCatching {
            val root = AceBoundedBencodeParser(
                data = payload,
                maxDepth = 4,
                maxContainerEntries = 64,
                maxStringBytes = 16 * 1024,
                maxTotalNodes = 256
            ).parseRootDictionary()
            val messageMap = root.values["m"] as? AceBencodeValue.Dictionary
                ?: return@runCatching AceLivePeerExchangeHandshakeUpdate(
                    utPexPresent = false,
                    utPexMessageId = null
                )
            val rawUtPex = messageMap.values["ut_pex"]
                ?: return@runCatching AceLivePeerExchangeHandshakeUpdate(
                    utPexPresent = false,
                    utPexMessageId = null
                )
            val messageId = (rawUtPex as? AceBencodeValue.Integer)?.value
                ?: error("ut_pex extension id is not an integer")
            require(messageId in 0..255) { "ut_pex extension id is outside one-byte range" }
            AceLivePeerExchangeHandshakeUpdate(
                utPexPresent = true,
                utPexMessageId = messageId.takeIf { it != 0L }?.toInt()
            )
        }.getOrNull()

    fun decodePeerExchange(payload: ByteArray): AceLivePeerExchangeMessage? = runCatching {
        val root = AceBoundedBencodeParser(
            data = payload,
            maxDepth = 4,
            maxContainerEntries = 32,
            maxStringBytes = MAX_PEX_STRING_BYTES,
            maxTotalNodes = 128
        ).parseRootDictionary()
        val addedValue = root.values["added"]
            ?: return@runCatching AceLivePeerExchangeMessage(emptyList())
        val added = (addedValue as? AceBencodeValue.Bytes)?.value
            ?: error("PEX added field is not a byte string")
        require(added.size % COMPACT_IPV4_PEER_BYTES == 0) {
            "PEX IPv4 added field is not compact-peer aligned"
        }
        val advertisedCount = added.size / COMPACT_IPV4_PEER_BYTES
        val flags = (root.values["added.f"] as? AceBencodeValue.Bytes)?.value
        if (root.values.containsKey("added.f")) {
            require(flags != null && flags.size == advertisedCount) {
                "PEX added.f length does not match added peer count"
            }
        }

        val peers = ArrayList<AceLiveTcpPeerEndpoint>(minOf(advertisedCount, MAX_ADDED_IPV4_PEERS))
        val seenHosts = HashSet<String>()
        val boundedCount = minOf(advertisedCount, MAX_ADDED_IPV4_PEERS)
        repeat(boundedCount) { index ->
            val offset = index * COMPACT_IPV4_PEER_BYTES
            val a = added[offset].toInt() and 0xff
            val b = added[offset + 1].toInt() and 0xff
            val c = added[offset + 2].toInt() and 0xff
            val d = added[offset + 3].toInt() and 0xff
            val port = ((added[offset + 4].toInt() and 0xff) shl 8) or
                (added[offset + 5].toInt() and 0xff)
            if (port == 0 || !isGloballyRoutableIpv4(a, b, c, d)) return@repeat
            val host = "$a.$b.$c.$d"
            // BEP-11 security guidance explicitly warns against duplicate IPs under different ports.
            if (!seenHosts.add(host)) return@repeat
            peers += AceLiveTcpPeerEndpoint(host = host, port = port)
        }
        AceLivePeerExchangeMessage(peers)
    }.getOrNull()

    private fun isGloballyRoutableIpv4(a: Int, b: Int, c: Int, d: Int): Boolean {
        val value = ipv4(a, b, c, d)
        return SPECIAL_USE_IPV4_RANGES.none { range -> range.contains(value) }
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): Long =
        ((a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()) and IPV4_MASK

    private data class Ipv4Range(val network: Long, val prefixBits: Int) {
        fun contains(value: Long): Boolean {
            val mask = (IPV4_MASK shl (32 - prefixBits)) and IPV4_MASK
            return (value and mask) == (network and mask)
        }
    }

    private fun cidr(a: Int, b: Int, c: Int, d: Int, prefixBits: Int) =
        Ipv4Range(ipv4(a, b, c, d), prefixBits)

    private const val COMPACT_IPV4_PEER_BYTES = 6
    private const val MAX_PEX_STRING_BYTES = 4 * 1024
    private const val IPV4_MASK: Long = 0xffff_ffffL
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
}
