package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUtpProtocolTest {
    @Test
    fun `codec round trips version one data packet in network byte order`() {
        val packet = AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.DATA,
                connectionId = 0x1234,
                timestampMicros = 0x89ab_cdefL,
                timestampDifferenceMicros = 0x0102_0304L,
                receiveWindowBytes = 64 * 1024L,
                sequenceNumber = 0x4567,
                acknowledgementNumber = 0x2345
            ),
            payload = byteArrayOf(1, 2, 3, 4)
        )

        val encoded = AceLiveUtpCodec.encode(packet)
        assertEquals(0x01, encoded[0].toInt() and 0xff)
        assertEquals(0, encoded[1].toInt() and 0xff)
        assertEquals(0x12, encoded[2].toInt() and 0xff)
        assertEquals(0x34, encoded[3].toInt() and 0xff)

        val decoded = AceLiveUtpCodec.decode(encoded)
        assertNotNull(decoded)
        decoded!!
        assertEquals(AceLiveUtpPacketType.DATA, decoded.header.type)
        assertEquals(0x1234, decoded.header.connectionId)
        assertEquals(0x89ab_cdefL, decoded.header.timestampMicros)
        assertEquals(0x0102_0304L, decoded.header.timestampDifferenceMicros)
        assertEquals(64 * 1024L, decoded.header.receiveWindowBytes)
        assertEquals(0x4567, decoded.header.sequenceNumber)
        assertEquals(0x2345, decoded.header.acknowledgementNumber)
        assertTrue(decoded.payload.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `codec preserves bounded extension chain and selective ack`() {
        val packet = AceLiveUtpPacket(
            header = header(AceLiveUtpPacketType.STATE),
            extensions = listOf(
                AceLiveUtpExtension(42, byteArrayOf(7, 8)),
                AceLiveUtpExtension(
                    AceLiveUtpExtension.SELECTIVE_ACK_TYPE,
                    byteArrayOf(0x01, 0x00, 0x80.toByte(), 0x00)
                )
            )
        )

        val decoded = AceLiveUtpCodec.decode(AceLiveUtpCodec.encode(packet))
        assertNotNull(decoded)
        decoded!!
        assertEquals(2, decoded.extensions.size)
        assertEquals(42, decoded.extensions[0].type)
        assertTrue(decoded.extensions[0].payload.contentEquals(byteArrayOf(7, 8)))
        assertEquals(AceLiveUtpExtension.SELECTIVE_ACK_TYPE, decoded.extensions[1].type)
        assertTrue(
            decoded.extensions[1].payload.contentEquals(
                byteArrayOf(0x01, 0x00, 0x80.toByte(), 0x00)
            )
        )
    }

    @Test
    fun `codec rejects unsupported malformed and oversized datagrams`() {
        val valid = AceLiveUtpCodec.encode(AceLiveUtpPacket(header(AceLiveUtpPacketType.STATE)))

        assertNull(AceLiveUtpCodec.decode(valid.copyOf().also { it[0] = 0x22 }))
        assertNull(AceLiveUtpCodec.decode(valid.copyOf().also { it[0] = 0x51 }))
        assertNull(AceLiveUtpCodec.decode(ByteArray(AceLiveUtpCodec.HEADER_BYTES - 1)))
        assertNull(AceLiveUtpCodec.decode(ByteArray(AceLiveUtpCodec.MAX_DATAGRAM_BYTES + 1)))

        val malformedExtension = valid.copyOf().also {
            it[1] = AceLiveUtpExtension.SELECTIVE_ACK_TYPE.toByte()
        }
        assertNull(AceLiveUtpCodec.decode(malformedExtension))
    }

    @Test
    fun `selective ack extension enforces BEP29 word sized mask`() {
        assertFails {
            AceLiveUtpExtension(AceLiveUtpExtension.SELECTIVE_ACK_TYPE, byteArrayOf(1, 2, 3))
        }
        assertFails {
            AceLiveUtpExtension(AceLiveUtpExtension.SELECTIVE_ACK_TYPE, ByteArray(6))
        }
        AceLiveUtpExtension(AceLiveUtpExtension.SELECTIVE_ACK_TYPE, ByteArray(4))
    }

    @Test
    fun `initiator connection ids wrap as uint16`() {
        val normal = AceLiveUtpClientConnectionIds.fromSynConnectionId(40000)
        assertEquals(40000, normal.receiveConnectionId)
        assertEquals(40001, normal.sendConnectionId)

        val wrapped = AceLiveUtpClientConnectionIds.fromSynConnectionId(0xffff)
        assertEquals(0xffff, wrapped.receiveConnectionId)
        assertEquals(0, wrapped.sendConnectionId)
    }

    @Test
    fun `client handshake follows BEP29 syn state and first data ids`() {
        val handshake = AceLiveUtpClientHandshake(synConnectionId = 0xfffe)
        val syn = handshake.createSyn(timestampMicros = 0x1_0000_0001L)

        assertEquals(AceLiveUtpClientHandshakePhase.SYN_SENT, handshake.phase)
        assertEquals(AceLiveUtpPacketType.SYN, syn.header.type)
        assertEquals(0xfffe, syn.header.connectionId)
        assertEquals(1L, syn.header.timestampMicros)
        assertEquals(1, syn.header.sequenceNumber)
        assertEquals(0, syn.header.acknowledgementNumber)

        val state = AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.STATE,
                connectionId = 0xfffe,
                timestampMicros = 10,
                timestampDifferenceMicros = 5,
                receiveWindowBytes = 32 * 1024L,
                sequenceNumber = 62000,
                acknowledgementNumber = 1
            )
        )
        assertTrue(handshake.acceptHandshakeResponse(state))
        assertEquals(AceLiveUtpClientHandshakePhase.CONNECTED, handshake.phase)

        val firstData = handshake.createFirstData(
            payload = byteArrayOf(9, 8, 7),
            timestampMicros = 20,
            timestampDifferenceMicros = 4
        )
        assertEquals(AceLiveUtpPacketType.DATA, firstData.header.type)
        assertEquals(0xffff, firstData.header.connectionId)
        assertEquals(2, firstData.header.sequenceNumber)
        assertEquals(62000, firstData.header.acknowledgementNumber)
        assertTrue(firstData.payload.contentEquals(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun `client handshake rejects wrong state and records reset`() {
        val wrongAckHandshake = AceLiveUtpClientHandshake(123)
        wrongAckHandshake.createSyn(1)
        assertFalse(
            wrongAckHandshake.acceptHandshakeResponse(
                AceLiveUtpPacket(
                    header = header(
                        type = AceLiveUtpPacketType.STATE,
                        connectionId = 123,
                        acknowledgementNumber = 2
                    )
                )
            )
        )
        assertEquals(AceLiveUtpClientHandshakePhase.SYN_SENT, wrongAckHandshake.phase)

        val resetHandshake = AceLiveUtpClientHandshake(321)
        resetHandshake.createSyn(1)
        assertFalse(
            resetHandshake.acceptHandshakeResponse(
                AceLiveUtpPacket(
                    header = header(
                        type = AceLiveUtpPacketType.RESET,
                        connectionId = 321,
                        acknowledgementNumber = 1
                    )
                )
            )
        )
        assertEquals(AceLiveUtpClientHandshakePhase.RESET, resetHandshake.phase)
    }

    private fun header(
        type: AceLiveUtpPacketType,
        connectionId: Int = 7,
        acknowledgementNumber: Int = 5
    ) = AceLiveUtpHeader(
        type = type,
        connectionId = connectionId,
        timestampMicros = 1,
        timestampDifferenceMicros = 0,
        receiveWindowBytes = 1024,
        sequenceNumber = 6,
        acknowledgementNumber = acknowledgementNumber
    )

    private inline fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
