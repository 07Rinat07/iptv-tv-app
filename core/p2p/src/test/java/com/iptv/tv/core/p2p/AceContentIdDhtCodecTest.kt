package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentIdDhtCodecTest {
    @Test
    fun parsesExactlyFortyHexCharactersWithoutChangingIdentityClass() {
        val raw = "50BC2F512793F1E745FB5BD5B5A6AFCA199C2D19"

        val key = AceContentIdDhtKey.parseHex(raw)

        assertNotNull(key)
        assertEquals(raw.lowercase(), key!!.toHex())
        assertArrayEquals(hexBytes(raw), key.toByteArray())
        assertEquals("AceContentIdDhtKey(redacted)", key.toString())
    }

    @Test
    fun rejectsNonHexAndNonTwentyByteContentIdsForDhtLookup() {
        assertNull(AceContentIdDhtKey.parseHex("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"))
        assertNull(AceContentIdDhtKey.parseHex("1234"))
        assertNull(AceContentIdDhtKey.parseHex("z".repeat(40)))
    }

    @Test
    fun getPeersQueryCarriesRawAceContentIdBytesInBep5TargetField() {
        val contentIdHex = "50bc2f512793f1e745fb5bd5b5a6afca199c2d19"
        val contentId = AceContentIdDhtKey.parseHex(contentIdHex)!!
        val nodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { index -> index.toByte() })
        val transactionId = byteArrayOf(0x12, 0x34)

        val packet = AceContentIdDhtCodec.encodeGetPeersQuery(
            transactionId = transactionId,
            nodeId = nodeId,
            contentId = contentId
        )

        val marker = "9:info_hash20:".toByteArray(StandardCharsets.US_ASCII)
        val markerOffset = packet.indexOfSubsequence(marker)
        assertTrue(markerOffset >= 0)

        val targetOffset = markerOffset + marker.size
        assertArrayEquals(
            contentId.toByteArray(),
            packet.copyOfRange(targetOffset, targetOffset + AceContentIdDhtKey.BYTES)
        )

        val asciiPacket = String(packet, StandardCharsets.ISO_8859_1)
        assertTrue(asciiPacket.contains("get_peers"))
        assertTrue(asciiPacket.contains("2:ro"))
        assertFalse(asciiPacket.contains(contentIdHex))
    }

    @Test
    fun contentIdAndLiveSwarmKeyRemainSeparateTypesEvenForSameTwentyBytes() {
        val bytes = ByteArray(20) { index -> (index * 7).toByte() }
        val contentId = AceContentIdDhtKey.fromBytes(bytes)
        val swarmKey = AceLiveSwarmKey.fromBytes(bytes)

        assertArrayEquals(contentId.toByteArray(), swarmKey.toByteArray())
        assertFalse(contentId.toString().contains("SwarmKey"))
        assertFalse(swarmKey.toString().contains("ContentId"))
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (offset in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        return -1
    }
}
