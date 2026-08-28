package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerHandshakeCodecTest {
    private val codec = AceLivePeerHandshakeCodec()
    private val swarmKey = ByteArray(20) { it.toByte() }
    private val peerId = "R30------ABCDEFGHIJK".toByteArray(Charsets.US_ASCII)

    @Test
    fun encodesExactPublicAceHandshakeLayout() {
        val encoded = codec.encode(swarmKey = swarmKey, peerId = peerId)

        assertEquals(66, encoded.size)
        assertEquals(17, encoded[0].toInt() and 0xff)
        assertArrayEquals(
            "AceStreamProtocol".toByteArray(Charsets.US_ASCII),
            encoded.copyOfRange(1, 18)
        )
        val reserved = ByteArray(8).also { bytes -> bytes[5] = 0x10 }
        assertArrayEquals(reserved, encoded.copyOfRange(18, 26))
        assertArrayEquals(swarmKey, encoded.copyOfRange(26, 46))
        assertArrayEquals(peerId, encoded.copyOfRange(46, 66))
    }

    @Test
    fun decodesHandshakeAndLeavesCoalescedPeerBytesUnconsumed() {
        val handshake = codec.encode(swarmKey = swarmKey, peerId = peerId)
        val coalesced = handshake + byteArrayOf(0, 0, 0, 0)

        val decoded = codec.decode(coalesced, expectedSwarmKey = swarmKey) as
            AceLivePeerHandshakeDecodeResult.Decoded

        assertEquals(66, decoded.consumedBytes)
        val reserved = ByteArray(8).also { bytes -> bytes[5] = 0x10 }
        assertArrayEquals(reserved, decoded.handshake.reservedBytes())
        assertArrayEquals(swarmKey, decoded.handshake.swarmKeyBytes())
        assertArrayEquals(peerId, decoded.handshake.peerIdBytes())
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), coalesced.copyOfRange(decoded.consumedBytes, coalesced.size))
    }

    @Test
    fun partialHandshakeReportsBoundedMinimums() {
        assertEquals(
            AceLivePeerHandshakeDecodeResult.NeedMoreData(1),
            codec.decode(byteArrayOf())
        )

        val prefix = byteArrayOf(17) + "Ace".toByteArray(Charsets.US_ASCII)
        assertEquals(
            AceLivePeerHandshakeDecodeResult.NeedMoreData(18),
            codec.decode(prefix)
        )

        val protocolOnly = byteArrayOf(17) + "AceStreamProtocol".toByteArray(Charsets.US_ASCII)
        assertEquals(
            AceLivePeerHandshakeDecodeResult.NeedMoreData(66),
            codec.decode(protocolOnly)
        )
    }

    @Test
    fun rejectsUnexpectedProtocolLengthBeforeReadingVariableData() {
        val result = codec.decode(byteArrayOf(19))

        assertEquals(
            AceLivePeerHandshakeDecodeResult.Rejected(
                AceLivePeerHandshakeRejectReason.INVALID_PROTOCOL_LENGTH
            ),
            result
        )
    }

    @Test
    fun rejectsWrongProtocolString() {
        val bytes = codec.encode(swarmKey = swarmKey, peerId = peerId)
        bytes[1] = 'B'.code.toByte()

        assertEquals(
            AceLivePeerHandshakeDecodeResult.Rejected(
                AceLivePeerHandshakeRejectReason.PROTOCOL_MISMATCH
            ),
            codec.decode(bytes)
        )
    }

    @Test
    fun rejectsMismatchedSwarmKey() {
        val bytes = codec.encode(swarmKey = swarmKey, peerId = peerId)
        val otherSwarm = swarmKey.copyOf().also { it[0] = 99 }

        assertEquals(
            AceLivePeerHandshakeDecodeResult.Rejected(
                AceLivePeerHandshakeRejectReason.SWARM_KEY_MISMATCH
            ),
            codec.decode(bytes, expectedSwarmKey = otherSwarm)
        )
    }

    @Test
    fun preservesReservedBytesWithoutInventingCapabilitySemantics() {
        val reserved = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val decoded = codec.decode(
            codec.encode(swarmKey = swarmKey, peerId = peerId, reserved = reserved),
            expectedSwarmKey = swarmKey
        ) as AceLivePeerHandshakeDecodeResult.Decoded

        assertArrayEquals(reserved, decoded.handshake.reservedBytes())
    }

    @Test
    fun parsedArraysAreDefensivelyCopied() {
        val bytes = codec.encode(swarmKey = swarmKey, peerId = peerId)
        val decoded = codec.decode(bytes, expectedSwarmKey = swarmKey) as
            AceLivePeerHandshakeDecodeResult.Decoded

        val exposedSwarm = decoded.handshake.swarmKeyBytes()
        exposedSwarm.fill(0)
        bytes.fill(0)

        assertTrue(decoded.handshake.swarmKeyBytes().contentEquals(swarmKey))
        assertTrue(decoded.handshake.peerIdBytes().contentEquals(peerId))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeRejectsWrongPeerIdLength() {
        codec.encode(swarmKey = swarmKey, peerId = ByteArray(19))
    }
}
