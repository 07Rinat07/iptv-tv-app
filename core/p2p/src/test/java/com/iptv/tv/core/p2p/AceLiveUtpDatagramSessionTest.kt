package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUtpDatagramSessionTest {
    @Test
    fun `send respects peer window and cumulative ack`() {
        val session = session(
            policy = policy(maxPayloadBytes = 4, maxInFlightPackets = 2, maxInFlightBytes = 8),
            remoteWindow = 6
        )

        val sent = session.send(ByteArray(10), nowMillis = 0, timestampMicros = 1)
        assertEquals(6, sent.acceptedBytes)
        assertEquals(listOf(4, 2), sent.transmissions.map { it.packet.payload.size })
        assertEquals(listOf(2, 3), sent.transmissions.map { it.packet.header.sequenceNumber })

        val ack = session.receivePacket(state(ack = 2, window = 8), nowMillis = 100, nowMicros = 2)
        assertEquals(setOf(2), ack.acknowledgedSequenceNumbers)
        assertEquals(2, session.inFlightPayloadBytes())
        assertEquals(500L, session.retransmissionTimeoutMillis())
    }

    @Test
    fun `cumulative ack and sequence allocation wrap at uint16`() {
        val session = session(
            policy = policy(maxPayloadBytes = 1, maxInFlightPackets = 4, maxInFlightBytes = 4),
            localSequence = 0xffff,
            remoteWindow = 4
        )
        val sent = session.send(byteArrayOf(1, 2), nowMillis = 0, timestampMicros = 1)
        assertEquals(listOf(0xffff, 0), sent.transmissions.map { it.packet.header.sequenceNumber })

        val ack = session.receivePacket(state(ack = 0), nowMillis = 10, nowMicros = 20)
        assertEquals(linkedSetOf(0xffff, 0), ack.acknowledgedSequenceNumbers)
        assertEquals(0, session.inFlightPacketCount())
    }

    @Test
    fun `selective ack maps first bit to ack plus two`() {
        val session = session(
            policy = policy(maxPayloadBytes = 1, maxInFlightPackets = 4, maxInFlightBytes = 4),
            remoteWindow = 4
        )
        session.send(byteArrayOf(1, 2, 3), nowMillis = 0, timestampMicros = 1)
        val sack = AceLiveUtpExtension(
            AceLiveUtpExtension.SELECTIVE_ACK_TYPE,
            byteArrayOf(1, 0, 0, 0)
        )

        val ack = session.receivePacket(
            state(ack = 1, extensions = listOf(sack)),
            nowMillis = 10,
            nowMicros = 20
        )

        assertEquals(setOf(3), ack.acknowledgedSequenceNumbers)
        assertEquals(2, session.inFlightPacketCount())
    }

    @Test
    fun `timeout retransmits oldest packet with bounded exponential backoff`() {
        val session = session(policy = policy(maxRetransmissions = 2))
        session.send(byteArrayOf(7), nowMillis = 100, timestampMicros = 1)

        assertTrue(session.pollTimeout(1099, 2) is AceLiveUtpTimeoutResult.None)

        val first = session.pollTimeout(1100, 3) as AceLiveUtpTimeoutResult.Retransmit
        assertEquals(2, first.transmission.attempt)
        assertEquals(2000L, session.retransmissionTimeoutMillis())

        val second = session.pollTimeout(3100, 4) as AceLiveUtpTimeoutResult.Retransmit
        assertEquals(3, second.transmission.attempt)
        assertEquals(4000L, session.retransmissionTimeoutMillis())

        assertTrue(session.pollTimeout(7100, 5) is AceLiveUtpTimeoutResult.Exhausted)
        assertTrue(session.isReset())
    }

    @Test
    fun `out of order payload emits sack then drains when gap arrives`() {
        val session = session(remoteSequence = 10)

        val outOfOrder = session.receivePacket(
            data(sequence = 12, payload = byteArrayOf(12)),
            nowMillis = 0,
            nowMicros = 100
        )
        assertEquals(0, outOfOrder.deliveredBytes.size)
        val firstAck = checkNotNull(outOfOrder.acknowledgement).packet
        assertEquals(10, firstAck.header.acknowledgementNumber)
        assertEquals(1, firstAck.extensions.single().payload[0].toInt() and 1)

        val contiguous = session.receivePacket(
            data(sequence = 11, payload = byteArrayOf(11)),
            nowMillis = 1,
            nowMicros = 200
        )
        assertArrayEquals(byteArrayOf(11, 12), contiguous.deliveredBytes)
        val secondAck = checkNotNull(contiguous.acknowledgement).packet
        assertEquals(12, secondAck.header.acknowledgementNumber)
        assertTrue(secondAck.extensions.isEmpty())
    }

    @Test
    fun `future cumulative ack is ignored until a sent sequence is acknowledged`() {
        val session = session()
        session.send(byteArrayOf(1), nowMillis = 0, timestampMicros = 1)

        val future = session.receivePacket(
            state(ack = 99),
            nowMillis = 10,
            nowMicros = 20
        )
        assertTrue(future.acknowledgedSequenceNumbers.isEmpty())
        assertEquals(1, session.inFlightPacketCount())

        val actual = session.receivePacket(
            state(ack = 2),
            nowMillis = 20,
            nowMicros = 30
        )
        assertEquals(setOf(2), actual.acknowledgedSequenceNumbers)
        assertEquals(0, session.inFlightPacketCount())
    }

    @Test
    fun `unexpected syn on connected id is ignored without session mutation`() {
        val session = session()
        session.send(byteArrayOf(1), nowMillis = 0, timestampMicros = 1)
        val syn = AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.SYN,
                connectionId = RECEIVE_ID,
                timestampMicros = 1,
                timestampDifferenceMicros = 0,
                receiveWindowBytes = 0,
                sequenceNumber = 1,
                acknowledgementNumber = 2
            )
        )

        val result = session.receivePacket(syn, nowMillis = 10, nowMicros = 20)

        assertTrue(result.ignored)
        assertEquals(1, session.inFlightPacketCount())
    }

    @Test
    fun `codec datagram path ignores foreign connection id`() {
        val session = session(remoteSequence = 10)
        val valid = AceLiveUtpCodec.encode(data(sequence = 11, payload = byteArrayOf(3, 4)))
        assertArrayEquals(
            byteArrayOf(3, 4),
            session.receiveDatagram(valid, nowMillis = 0, nowMicros = 50).deliveredBytes
        )

        val foreign = AceLiveUtpPacket(
            header = data(sequence = 12, payload = byteArrayOf(9)).header.copy(connectionId = 999),
            payload = byteArrayOf(9)
        )
        assertTrue(
            session.receiveDatagram(
                AceLiveUtpCodec.encode(foreign),
                nowMillis = 1,
                nowMicros = 60
            ).ignored
        )
    }

    private fun session(
        policy: AceLiveUtpSessionPolicy = policy(),
        localSequence: Int = 2,
        remoteSequence: Int = 500,
        remoteWindow: Long = 64 * 1024L
    ) = AceLiveUtpDatagramSession(
        connectionIds = AceLiveUtpClientConnectionIds(RECEIVE_ID, SEND_ID),
        initialLocalSequenceNumber = localSequence,
        initialRemoteSequenceNumber = remoteSequence,
        initialRemoteReceiveWindowBytes = remoteWindow,
        policy = policy
    )

    private fun policy(
        maxPayloadBytes: Int = 1200,
        maxInFlightPackets: Int = 32,
        maxInFlightBytes: Int = 64 * 1024,
        maxRetransmissions: Int = 4
    ) = AceLiveUtpSessionPolicy(
        maxPayloadBytes = maxPayloadBytes,
        maxInFlightPackets = maxInFlightPackets,
        maxInFlightBytes = maxInFlightBytes,
        maxRetransmissionsPerPacket = maxRetransmissions
    )

    private fun state(
        ack: Int,
        window: Long = 64 * 1024L,
        extensions: List<AceLiveUtpExtension> = emptyList()
    ) = AceLiveUtpPacket(
        header = AceLiveUtpHeader(
            type = AceLiveUtpPacketType.STATE,
            connectionId = RECEIVE_ID,
            timestampMicros = 1,
            timestampDifferenceMicros = 0,
            receiveWindowBytes = window,
            sequenceNumber = 700,
            acknowledgementNumber = ack
        ),
        extensions = extensions
    )

    private fun data(sequence: Int, payload: ByteArray) = AceLiveUtpPacket(
        header = AceLiveUtpHeader(
            type = AceLiveUtpPacketType.DATA,
            connectionId = RECEIVE_ID,
            timestampMicros = 1,
            timestampDifferenceMicros = 0,
            receiveWindowBytes = 64 * 1024L,
            sequenceNumber = sequence,
            acknowledgementNumber = 1
        ),
        payload = payload
    )

    private companion object {
        const val RECEIVE_ID = 100
        const val SEND_ID = 101
    }
}
