package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerExchangeTest {
    @Test
    fun `extension handshake exposes additive peer-local ut pex mapping`() {
        val enabled = AceLivePeerExchangeCodec.decodeExtensionHandshake(
            encodedDictionary("m" to valueDictionary("ut_pex" to AceBencodeValue.Integer(7)))
        )
        val unrelatedUpdate = AceLivePeerExchangeCodec.decodeExtensionHandshake(
            encodedDictionary("m" to valueDictionary("ut_metadata" to AceBencodeValue.Integer(2)))
        )
        val disabled = AceLivePeerExchangeCodec.decodeExtensionHandshake(
            encodedDictionary("m" to valueDictionary("ut_pex" to AceBencodeValue.Integer(0)))
        )

        requireNotNull(enabled)
        assertTrue(enabled.utPexPresent)
        assertEquals(7, enabled.utPexMessageId)
        requireNotNull(unrelatedUpdate)
        assertFalse(unrelatedUpdate.utPexPresent)
        assertNull(unrelatedUpdate.utPexMessageId)
        requireNotNull(disabled)
        assertTrue(disabled.utPexPresent)
        assertNull(disabled.utPexMessageId)
    }

    @Test
    fun `peer exchange decodes bounded global ipv4 peers and deduplicates host`() {
        val added = compactPeer(8, 8, 8, 8, 8000) +
            compactPeer(8, 8, 8, 8, 9000) +
            compactPeer(1, 1, 1, 1, 8621) +
            compactPeer(10, 0, 0, 1, 8621)
        val decoded = AceLivePeerExchangeCodec.decodePeerExchange(
            encodedDictionary("added" to AceBencodeValue.Bytes(added))
        )

        requireNotNull(decoded)
        assertEquals(
            listOf(
                AceLiveTcpPeerEndpoint("8.8.8.8", 8000),
                AceLiveTcpPeerEndpoint("1.1.1.1", 8621)
            ),
            decoded.added
        )
    }

    @Test
    fun `peer exchange caps accepted peers per untrusted message`() {
        val added = (1..60).fold(byteArrayOf()) { bytes, index ->
            bytes + compactPeer(11, 0, index / 255, (index % 254) + 1, 8000 + index)
        }
        val decoded = AceLivePeerExchangeCodec.decodePeerExchange(
            encodedDictionary("added" to AceBencodeValue.Bytes(added))
        )

        requireNotNull(decoded)
        assertEquals(AceLivePeerExchangeCodec.MAX_ADDED_IPV4_PEERS, decoded.added.size)
    }

    @Test
    fun `malformed compact peer field is rejected without partial candidates`() {
        assertNull(
            AceLivePeerExchangeCodec.decodePeerExchange(
                encodedDictionary("added" to AceBencodeValue.Bytes(byteArrayOf(1, 2, 3, 4, 5)))
            )
        )
    }

    private fun encodedDictionary(vararg entries: Pair<String, AceBencodeValue>): ByteArray =
        AceBencodeEncoder.encode(AceBencodeValue.Dictionary(linkedMapOf(*entries)))

    private fun valueDictionary(vararg entries: Pair<String, AceBencodeValue>): AceBencodeValue.Dictionary =
        AceBencodeValue.Dictionary(linkedMapOf(*entries))

    private fun compactPeer(a: Int, b: Int, c: Int, d: Int, port: Int): ByteArray = byteArrayOf(
        a.toByte(), b.toByte(), c.toByte(), d.toByte(),
        (port ushr 8).toByte(), port.toByte()
    )
}
