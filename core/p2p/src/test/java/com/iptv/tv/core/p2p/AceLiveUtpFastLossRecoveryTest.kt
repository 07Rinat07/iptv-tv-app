package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUtpFastLossRecoveryTest {
    @Test
    fun `three selectively acknowledged packets trigger fast retransmit of missing oldest`() {
        val session = session()
        session.send(byteArrayOf(10, 11, 12, 13), nowMillis = 0, timestampMicros = 1)

        val result = session.receivePacket(
            state(ack = 1, sackMask = 0x07),
            nowMillis = 10,
            nowMicros = 20,
            timestampDifferenceMicros = 33
        )

        assertEquals(linkedSetOf(3, 4, 5), result.acknowledgedSequenceNumbers)
        val retransmission = result.fastRetransmissions.single()
        assertEquals(2, retransmission.packet.header.sequenceNumber)
        assertArrayEquals(byteArrayOf(10), retransmission.packet.payload)
        assertEquals(2, retransmission.attempt)
        assertTrue(retransmission.retransmission)
        assertEquals(20L, retransmission.packet.header.timestampMicros)
        assertEquals(33L, retransmission.packet.header.timestampDifferenceMicros)
    }

    @Test
    fun `two selectively acknowledged packets do not trigger fast retransmit`() {
        val session = session()
        session.send(byteArrayOf(10, 11, 12, 13), nowMillis = 0, timestampMicros = 1)

        val result = session.receivePacket(
            state(ack = 1, sackMask = 0x03),
            nowMillis = 10,
            nowMicros = 20
        )

        assertTrue(result.fastRetransmissions.isEmpty())
    }

    @Test
    fun `third duplicate acknowledgement fast retransmits ack plus one once`() {
        val session = session()
        session.send(byteArrayOf(1, 2, 3, 4), nowMillis = 0, timestampMicros = 1)

        assertTrue(session.receivePacket(state(ack = 1), 10, 20).fastRetransmissions.isEmpty())
        assertTrue(session.receivePacket(state(ack = 1), 20, 30).fastRetransmissions.isEmpty())

        val third = session.receivePacket(state(ack = 1), 30, 40)
        assertEquals(2, third.fastRetransmissions.single().packet.header.sequenceNumber)

        val fourth = session.receivePacket(state(ack = 1), 40, 50)
        assertTrue(fourth.fastRetransmissions.isEmpty())
    }

    @Test
    fun `selective ack evidence wraps across uint16`() {
        val session = session(localSequence = 0xffff)
        session.send(byteArrayOf(9, 8, 7, 6), nowMillis = 0, timestampMicros = 1)

        val result = session.receivePacket(
            state(ack = 0xfffe, sackMask = 0x07),
            nowMillis = 10,
            nowMicros = 20
        )

        assertEquals(0xffff, result.fastRetransmissions.single().packet.header.sequenceNumber)
    }

    @Test
    fun `fast retransmit obeys existing retransmission budget`() {
        val session = session(maxRetransmissions = 0)
        session.send(byteArrayOf(1, 2, 3, 4), nowMillis = 0, timestampMicros = 1)

        val result = session.receivePacket(
            state(ack = 1, sackMask = 0x07),
            nowMillis = 10,
            nowMicros = 20
        )

        assertTrue(result.fastRetransmissions.isEmpty())
        assertTrue(session.pollTimeout(1_000, 30) is AceLiveUtpTimeoutResult.Exhausted)
    }

    private fun session(
        localSequence: Int = 2,
        maxRetransmissions: Int = 4
    ) = AceLiveUtpDatagramSession(
        connectionIds = AceLiveUtpClientConnectionIds(RECEIVE_ID, SEND_ID),
        initialLocalSequenceNumber = localSequence,
        initialRemoteSequenceNumber = 500,
        initialRemoteReceiveWindowBytes = 64 * 1024L,
        policy = AceLiveUtpSessionPolicy(
            maxPayloadBytes = 1,
            maxInFlightPackets = 8,
            maxInFlightBytes = 8,
            maxRetransmissionsPerPacket = maxRetransmissions
        )
    )

    private fun state(ack: Int, sackMask: Int = 0) = AceLiveUtpPacket(
        header = AceLiveUtpHeader(
            type = AceLiveUtpPacketType.STATE,
            connectionId = RECEIVE_ID,
            timestampMicros = 1,
            timestampDifferenceMicros = 10,
            receiveWindowBytes = 64 * 1024L,
            sequenceNumber = 700,
            acknowledgementNumber = ack
        ),
        extensions = if (sackMask == 0) {
            emptyList()
        } else {
            listOf(
                AceLiveUtpExtension(
                    AceLiveUtpExtension.SELECTIVE_ACK_TYPE,
                    byteArrayOf(sackMask.toByte(), 0, 0, 0)
                )
            )
        }
    )

    private companion object {
        const val RECEIVE_ID = 100
        const val SEND_ID = 101
    }
}