package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AceLiveUtpByteStreamTransportTest {
    @Test
    fun `read preserves stream order and surplus across calls`() = runBlocking {
        val connection = FakeConnection(
            receives = listOf(
                ReceiveStep.Result(receive(bytes = byteArrayOf(1, 2, 3, 4, 5)))
            )
        )
        val transport = AceLiveUtpByteStreamTransport(connection)

        val first = ByteArray(2)
        assertEquals(2, transport.read(first))
        assertArrayEquals(byteArrayOf(1, 2), first)
        assertEquals(3, transport.bufferedInboundByteCount())

        val second = ByteArray(4)
        assertEquals(3, transport.read(second))
        assertArrayEquals(byteArrayOf(3, 4, 5, 0), second)
        assertEquals(1, connection.receiveCalls)
    }

    @Test
    fun `fragmented uTP payload remains a normal partial byte stream`() = runBlocking {
        val connection = FakeConnection(
            receives = listOf(
                ReceiveStep.Result(receive(bytes = byteArrayOf(7, 8))),
                ReceiveStep.Result(receive(bytes = byteArrayOf(9)))
            )
        )
        val transport = AceLiveUtpByteStreamTransport(connection)

        val buffer = ByteArray(8)
        assertEquals(2, transport.read(buffer))
        assertArrayEquals(byteArrayOf(7, 8), buffer.copyOf(2))
        assertEquals(1, transport.read(buffer))
        assertEquals(9, buffer[0].toInt())
    }

    @Test
    fun `remote close drains already delivered bytes before eof`() = runBlocking {
        val connection = FakeConnection(
            receives = listOf(
                ReceiveStep.Result(receive(bytes = byteArrayOf(4, 5, 6), remoteClosed = true))
            )
        )
        val transport = AceLiveUtpByteStreamTransport(connection)

        val buffer = ByteArray(2)
        assertEquals(2, transport.read(buffer))
        assertArrayEquals(byteArrayOf(4, 5), buffer)
        assertEquals(1, transport.read(buffer))
        assertEquals(6, buffer[0].toInt())
        assertEquals(-1, transport.read(buffer))
    }

    @Test
    fun `ignored datagram is not exposed as stream data`() = runBlocking {
        val connection = FakeConnection(
            receives = listOf(
                ReceiveStep.Result(receive(ignored = true)),
                ReceiveStep.Result(receive(bytes = byteArrayOf(3)))
            )
        )
        val transport = AceLiveUtpByteStreamTransport(connection)
        val buffer = ByteArray(4)

        assertEquals(0, transport.read(buffer))
        assertEquals(1, transport.read(buffer))
        assertEquals(3, buffer[0].toInt())
    }

    @Test
    fun `partial send pumps receive progress and writes every application byte`() = runBlocking {
        val connection = FakeConnection(
            acceptedSendBytes = listOf(2, 2),
            receives = listOf(ReceiveStep.Result(receive()))
        )
        val transport = AceLiveUtpByteStreamTransport(connection)

        transport.write(byteArrayOf(1, 2, 3, 4))

        assertEquals(2, connection.sendCalls.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), connection.sendCalls[0])
        assertArrayEquals(byteArrayOf(3, 4), connection.sendCalls[1])
        assertEquals(1, connection.receiveCalls)
    }

    @Test
    fun `retransmission progress never advances application offset twice`() = runBlocking {
        val connection = FakeConnection(
            acceptedSendBytes = listOf(0, 4),
            receives = listOf(ReceiveStep.Timeout),
            timeouts = listOf(AceLiveUtpTimeoutResult.Retransmit(transmission()))
        )
        val transport = AceLiveUtpByteStreamTransport(connection)
        val bytes = byteArrayOf(10, 11, 12, 13)

        transport.write(bytes)

        assertEquals(2, connection.sendCalls.size)
        assertArrayEquals(bytes, connection.sendCalls[0])
        assertArrayEquals(bytes, connection.sendCalls[1])
        assertEquals(1, connection.timeoutPollCalls)
    }

    @Test
    fun `active ingress still drives retransmission timer`() = runBlocking {
        val connection = FakeConnection(
            acceptedSendBytes = listOf(0, 4),
            receives = listOf(ReceiveStep.Result(receive(bytes = byteArrayOf(9)))),
            timeouts = listOf(AceLiveUtpTimeoutResult.Retransmit(transmission()))
        )
        val transport = AceLiveUtpByteStreamTransport(connection)

        transport.write(byteArrayOf(1, 2, 3, 4))

        assertEquals(1, connection.timeoutPollCalls)
        val inbound = ByteArray(1)
        assertEquals(1, transport.read(inbound))
        assertEquals(9, inbound[0].toInt())
    }

    @Test
    fun `retransmission exhaustion becomes terminal io failure`() = runBlocking {
        val connection = FakeConnection(
            acceptedSendBytes = listOf(0),
            receives = listOf(ReceiveStep.Timeout),
            timeouts = listOf(AceLiveUtpTimeoutResult.Exhausted(42))
        )
        val transport = AceLiveUtpByteStreamTransport(connection)

        try {
            transport.write(byteArrayOf(1))
            fail("expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("42"))
        }
        assertTrue(connection.closed)
    }

    @Test
    fun `unread queue overflow fails closed instead of growing`() = runBlocking {
        val connection = FakeConnection(
            receives = listOf(
                ReceiveStep.Result(receive(bytes = byteArrayOf(1, 2, 3, 4, 5)))
            )
        )
        val transport = AceLiveUtpByteStreamTransport(
            connection = connection,
            policy = AceLiveUtpByteStreamPolicy(maxBufferedInboundBytes = 4)
        )

        try {
            transport.read(ByteArray(1))
            fail("expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("4 bytes"))
        }
        assertTrue(connection.closed)
        assertEquals(0, transport.bufferedInboundByteCount())
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val connection = FakeConnection()
        val transport = AceLiveUtpByteStreamTransport(connection)

        transport.close()
        transport.close()

        assertEquals(1, connection.closeCalls)
        assertEquals(-1, transport.read(ByteArray(1)))
    }

    private fun receive(
        bytes: ByteArray = ByteArray(0),
        ignored: Boolean = false,
        remoteClosed: Boolean = false
    ) = AceLiveUtpReceiveResult(
        deliveredBytes = bytes,
        acknowledgement = null,
        acknowledgedSequenceNumbers = emptySet(),
        ignored = ignored,
        remoteClosed = remoteClosed
    )

    private fun transmission() = AceLiveUtpTransmission(
        packet = AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.STATE,
                connectionId = 1,
                timestampMicros = 0,
                timestampDifferenceMicros = 0,
                receiveWindowBytes = 1,
                sequenceNumber = 1,
                acknowledgementNumber = 1
            )
        ),
        attempt = 2,
        retransmission = true
    )

    private sealed interface ReceiveStep {
        data class Result(val result: AceLiveUtpReceiveResult) : ReceiveStep
        object Timeout : ReceiveStep
    }

    private class FakeConnection(
        acceptedSendBytes: List<Int> = emptyList(),
        receives: List<ReceiveStep> = emptyList(),
        timeouts: List<AceLiveUtpTimeoutResult> = emptyList()
    ) : AceLiveUtpStreamConnection {
        private val accepted = ArrayDeque(acceptedSendBytes)
        private val receiveSteps = ArrayDeque(receives)
        private val timeoutSteps = ArrayDeque(timeouts)
        val sendCalls = mutableListOf<ByteArray>()
        var receiveCalls = 0
            private set
        var timeoutPollCalls = 0
            private set
        var closeCalls = 0
            private set
        var closed = false
            private set

        override suspend fun send(bytes: ByteArray): AceLiveUtpSendResult {
            check(!closed)
            sendCalls += bytes.copyOf()
            val planned = if (accepted.isEmpty()) bytes.size else accepted.removeFirst()
            val acceptedBytes = planned.coerceIn(0, bytes.size)
            return AceLiveUtpSendResult(
                acceptedBytes = acceptedBytes,
                transmissions = emptyList()
            )
        }

        override suspend fun receiveOnce(): AceLiveUtpReceiveResult? {
            check(!closed)
            receiveCalls += 1
            return when (
                val step = if (receiveSteps.isEmpty()) ReceiveStep.Timeout else receiveSteps.removeFirst()
            ) {
                is ReceiveStep.Result -> step.result
                ReceiveStep.Timeout -> null
            }
        }

        override suspend fun pollTimeout(): AceLiveUtpTimeoutResult {
            check(!closed)
            timeoutPollCalls += 1
            return if (timeoutSteps.isEmpty()) {
                AceLiveUtpTimeoutResult.None
            } else {
                timeoutSteps.removeFirst()
            }
        }

        override fun isClosed(): Boolean = closed

        override fun close() {
            closeCalls += 1
            closed = true
        }
    }
}
