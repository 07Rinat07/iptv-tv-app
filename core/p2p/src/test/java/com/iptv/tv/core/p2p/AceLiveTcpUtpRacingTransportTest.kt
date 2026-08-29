package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTcpUtpRacingTransportTest {
    private val codec = AceLivePeerHandshakeCodec()
    private val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 8621)
    private val policy = AceLiveTcpConnectionPolicy(
        connectTimeoutMillis = 1_000,
        writeTimeoutMillis = 1_000,
        readTimeoutMillis = 1_000,
        handshakeTimeoutMillis = 1_000
    )

    @Test
    fun `slower uTP candidate can win after faster TCP returns wrong swarm`() = runBlocking {
        val expectedSwarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 7 }
        val localHandshake = codec.encode(
            swarmKey = expectedSwarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 1 }
        )
        val wrongTcpHandshake = codec.encode(
            swarmKey = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 9 },
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 2 }
        )
        val validUtpHandshake = codec.encode(
            swarmKey = expectedSwarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 3 }
        ) + byteArrayOf(99)
        val tcp = ScriptedTransport(listOf(wrongTcpHandshake))
        val utp = ScriptedTransport(listOf(validUtpHandshake))
        val allowUtpConnect = CompletableDeferred<Unit>()
        val factory = factory(
            tcpConnect = { tcp },
            utpConnect = {
                allowUtpConnect.await()
                utp
            }
        )

        val raced = factory.connect(endpoint, policy)
        raced.write(localHandshake)
        allowUtpConnect.complete(Unit)

        val received = ByteArray(256)
        val count = raced.read(received)

        assertArrayEquals(validUtpHandshake, received.copyOf(count))
        assertTrue(tcp.closed.get())
        assertFalse(utp.closed.get())

        raced.write(byteArrayOf(5, 6))
        assertEquals(1, tcp.writes.size)
        assertEquals(2, utp.writes.size)
        assertArrayEquals(byteArrayOf(5, 6), utp.writes.last())
        raced.close()
    }

    @Test
    fun `valid TCP handshake wins and closes uTP loser`() = runBlocking {
        val swarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 4 }
        val localHandshake = codec.encode(
            swarmKey = swarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 1 }
        )
        val tcpHandshake = codec.encode(
            swarmKey = swarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 2 }
        )
        val holdUtpRead = CompletableDeferred<Unit>()
        val tcp = ScriptedTransport(listOf(tcpHandshake))
        val utp = ScriptedTransport(
            reads = emptyList(),
            beforeEmptyRead = { holdUtpRead.await() }
        )
        val factory = factory({ tcp }, { utp })

        val raced = factory.connect(endpoint, policy)
        raced.write(localHandshake)
        val received = ByteArray(128)
        val count = raced.read(received)

        assertArrayEquals(tcpHandshake, received.copyOf(count))
        assertTrue(utp.closed.get())
        assertFalse(tcp.closed.get())
        raced.close()
    }

    @Test
    fun `failed uTP physical connect does not block valid TCP`() = runBlocking {
        val swarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 6 }
        val localHandshake = codec.encode(
            swarmKey = swarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 1 }
        )
        val remoteHandshake = codec.encode(
            swarmKey = swarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 2 }
        )
        val tcp = ScriptedTransport(listOf(remoteHandshake))
        val factory = factory(
            tcpConnect = { tcp },
            utpConnect = { throw IOException("udp unavailable") }
        )

        val raced = factory.connect(endpoint, policy)
        raced.write(localHandshake)
        val received = ByteArray(128)

        assertEquals(AceLivePeerHandshakeCodec.HANDSHAKE_BYTES, raced.read(received))
        assertArrayEquals(remoteHandshake, received.copyOf(AceLivePeerHandshakeCodec.HANDSHAKE_BYTES))
        raced.close()
    }

    @Test
    fun `both physical connect failures fail the race`() = runBlocking {
        val factory = factory(
            tcpConnect = { throw IOException("tcp unavailable") },
            utpConnect = { throw IOException("udp unavailable") }
        )

        val result = runCatching { factory.connect(endpoint, policy) }

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `winner prebuffer preserves bytes after remote handshake`() = runBlocking {
        val swarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 8 }
        val localHandshake = codec.encode(
            swarmKey = swarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 1 }
        )
        val remote = codec.encode(
            swarmKey = swarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 2 }
        ) + byteArrayOf(11, 12, 13)
        val tcp = ScriptedTransport(listOf(remote))
        val factory = factory(
            tcpConnect = { tcp },
            utpConnect = { throw IOException("udp unavailable") }
        )

        val raced = factory.connect(endpoint, policy)
        raced.write(localHandshake)
        val first = ByteArray(32)
        val second = ByteArray(64)
        val third = ByteArray(64)

        val firstCount = raced.read(first)
        val secondCount = raced.read(second)
        val thirdCount = raced.read(third)
        val combined = first.copyOf(firstCount) + second.copyOf(secondCount) + third.copyOf(thirdCount)

        assertArrayEquals(remote, combined)
        raced.close()
    }

    @Test
    fun `first race write must be the exact Ace handshake`() = runBlocking {
        val tcp = ScriptedTransport(emptyList())
        val factory = factory(
            tcpConnect = { tcp },
            utpConnect = { throw IOException("udp unavailable") }
        )
        val raced = factory.connect(endpoint, policy)

        val result = runCatching { raced.write(byteArrayOf(1, 2, 3)) }

        assertTrue(result.exceptionOrNull() is IOException)
        raced.close()
    }

    private fun factory(
        tcpConnect: suspend () -> AceLiveTcpTransport,
        utpConnect: suspend () -> AceLiveTcpTransport
    ) = AceLiveTcpUtpRacingTransportFactory(
        tcpConnect = { _, _ -> tcpConnect() },
        utpConnect = { _, _ -> utpConnect() }
    )

    private class ScriptedTransport(
        reads: List<ByteArray>,
        private val beforeEmptyRead: suspend () -> Unit = {}
    ) : AceLiveTcpTransport {
        private val pendingReads = ArrayDeque(reads.map(ByteArray::copyOf))
        val writes = mutableListOf<ByteArray>()
        val closed = AtomicBoolean(false)

        override suspend fun read(buffer: ByteArray): Int {
            if (closed.get()) return -1
            val next = pendingReads.pollFirst()
            if (next == null) {
                beforeEmptyRead()
                return if (closed.get()) -1 else 0
            }
            val count = minOf(buffer.size, next.size)
            next.copyInto(buffer, endIndex = count)
            if (count < next.size) {
                pendingReads.addFirst(next.copyOfRange(count, next.size))
            }
            return count
        }

        override suspend fun write(bytes: ByteArray) {
            check(!closed.get())
            writes += bytes.copyOf()
        }

        override suspend fun close() {
            closed.set(true)
        }
    }
}
