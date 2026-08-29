package com.iptv.tv.core.p2p

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUtpSocketTransportTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Test
    fun `syn timeout backoff is exponentially bounded`() {
        val policy = AceLiveUtpSocketPolicy(
            initialSynTimeoutMillis = 100,
            maxSynTimeoutMillis = 250,
            maxSynAttempts = 4
        )

        assertEquals(listOf(100, 200, 250, 250), (0 until 4).map(policy::synTimeoutMillis))
    }

    @Test
    fun `connector retries the same syn and seeds session from accepted state`() = runBlocking {
        HandshakePeer(loopback, expectedSyns = 2) { syn, index ->
            if (index == 0) emptyList() else listOf(stateFor(syn, sequence = 500, window = 6))
        }.use { peer ->
            var networkBindCalls = 0
            val connector = connector(
                policy = policy(initialSynTimeoutMillis = 100, maxSynAttempts = 2)
            )
            val connection = connector.connect(
                endpoint = AceLiveUtpSocketEndpoint(loopback, peer.port),
                binding = AceLiveUtpSocketBinding(
                    localAddress = loopback,
                    bindDatagramSocket = { socket ->
                        assertFalse(socket.isBound)
                        networkBindCalls += 1
                    }
                ),
                sessionPolicy = AceLiveUtpSessionPolicy(
                    maxPayloadBytes = 4,
                    maxInFlightPackets = 2,
                    maxInFlightBytes = 8
                )
            )

            assertNotNull(connection)
            assertTrue(peer.awaitSyns())
            peer.throwIfFailed()
            assertEquals(1, networkBindCalls)
            assertEquals(2, peer.syns.size)
            assertEquals(peer.syns[0].header.connectionId, peer.syns[1].header.connectionId)
            assertEquals(peer.syns[0].header.sequenceNumber, peer.syns[1].header.sequenceNumber)

            val sent = connection!!.send(ByteArray(10), nowMillis = 0, timestampMicros = 10)
            assertEquals(6, sent.acceptedBytes)
            assertEquals(listOf(4, 2), sent.transmissions.map { it.packet.payload.size })
            connection.close()
            assertTrue(connection.isClosed())
        }
    }

    @Test
    fun `foreign state inside one syn deadline is ignored without consuming an attempt`() = runBlocking {
        HandshakePeer(loopback, expectedSyns = 1) { syn, _ ->
            listOf(
                stateFor(syn, sequence = 100).copyWithConnectionId(
                    (syn.header.connectionId + 7) and 0xffff
                ),
                stateFor(syn, sequence = 101)
            )
        }.use { peer ->
            val connection = connector(
                policy = policy(initialSynTimeoutMillis = 500, maxSynAttempts = 2)
            ).connect(AceLiveUtpSocketEndpoint(loopback, peer.port))

            assertNotNull(connection)
            assertTrue(peer.awaitSyns())
            peer.throwIfFailed()
            assertEquals(1, peer.syns.size)
            connection!!.close()
        }
    }

    @Test
    fun `matching reset terminates handshake without further syn retries`() = runBlocking {
        HandshakePeer(loopback, expectedSyns = 1) { syn, _ ->
            listOf(
                AceLiveUtpPacket(
                    header = AceLiveUtpHeader(
                        type = AceLiveUtpPacketType.RESET,
                        connectionId = syn.header.connectionId,
                        timestampMicros = 2,
                        timestampDifferenceMicros = 0,
                        receiveWindowBytes = 0,
                        sequenceNumber = 700,
                        acknowledgementNumber = syn.header.sequenceNumber
                    )
                )
            )
        }.use { peer ->
            val connection = connector(
                policy = policy(initialSynTimeoutMillis = 100, maxSynAttempts = 3)
            ).connect(AceLiveUtpSocketEndpoint(loopback, peer.port))

            assertNull(connection)
            assertTrue(peer.awaitSyns())
            peer.throwIfFailed()
            assertEquals(1, peer.syns.size)
        }
    }

    @Test
    fun `connected socket delivers inbound data and emits state acknowledgement`() = runBlocking {
        HandshakePeer(loopback, expectedSyns = 1) { syn, _ ->
            listOf(stateFor(syn, sequence = 900))
        }.use { peer ->
            val connection = connector(
                policy = policy(
                    initialSynTimeoutMillis = 500,
                    maxSynAttempts = 1,
                    establishedReceiveTimeoutMillis = 1_000
                )
            ).connect(AceLiveUtpSocketEndpoint(loopback, peer.port))
            assertNotNull(connection)
            assertTrue(peer.awaitSyns())
            peer.throwIfFailed()

            val syn = peer.syns.single()
            peer.sendToClient(
                AceLiveUtpPacket(
                    header = AceLiveUtpHeader(
                        type = AceLiveUtpPacketType.DATA,
                        connectionId = syn.header.connectionId,
                        timestampMicros = 50,
                        timestampDifferenceMicros = 0,
                        receiveWindowBytes = 64 * 1024L,
                        sequenceNumber = 901,
                        acknowledgementNumber = 1
                    ),
                    payload = byteArrayOf(9, 8, 7)
                )
            )

            val received = connection!!.receiveOnce(nowMillis = 10, nowMicros = 100)
            assertNotNull(received)
            assertArrayEquals(byteArrayOf(9, 8, 7), received!!.deliveredBytes)

            val acknowledgement = peer.receiveFromClient()
            assertNotNull(acknowledgement)
            acknowledgement!!
            assertEquals(AceLiveUtpPacketType.STATE, acknowledgement.header.type)
            assertEquals((syn.header.connectionId + 1) and 0xffff, acknowledgement.header.connectionId)
            assertEquals(901, acknowledgement.header.acknowledgementNumber)
            connection.close()
        }
    }

    private fun connector(policy: AceLiveUtpSocketPolicy) =
        AceLiveUtpSocketConnector(policy = policy)

    private fun policy(
        initialSynTimeoutMillis: Int,
        maxSynAttempts: Int,
        establishedReceiveTimeoutMillis: Int = 250
    ) = AceLiveUtpSocketPolicy(
        initialSynTimeoutMillis = initialSynTimeoutMillis,
        maxSynTimeoutMillis = initialSynTimeoutMillis,
        maxSynAttempts = maxSynAttempts,
        establishedReceiveTimeoutMillis = establishedReceiveTimeoutMillis
    )

    private fun stateFor(
        syn: AceLiveUtpPacket,
        sequence: Int,
        window: Long = 64 * 1024L
    ) = AceLiveUtpPacket(
        header = AceLiveUtpHeader(
            type = AceLiveUtpPacketType.STATE,
            connectionId = syn.header.connectionId,
            timestampMicros = 2,
            timestampDifferenceMicros = 0,
            receiveWindowBytes = window,
            sequenceNumber = sequence,
            acknowledgementNumber = syn.header.sequenceNumber
        )
    )

    private fun AceLiveUtpPacket.copyWithConnectionId(connectionId: Int) =
        AceLiveUtpPacket(
            header = header.copy(connectionId = connectionId),
            payload = payload
        )

    private class HandshakePeer(
        bindAddress: InetAddress,
        private val expectedSyns: Int,
        private val responses: (AceLiveUtpPacket, Int) -> List<AceLiveUtpPacket>
    ) : Closeable {
        private val socket = DatagramSocket(InetSocketAddress(bindAddress, 0)).apply {
            soTimeout = 2_000
        }
        private val synLatch = CountDownLatch(expectedSyns)
        private val failure = AtomicReference<Throwable?>(null)
        private val clientAddress = AtomicReference<SocketAddress?>(null)
        val syns = CopyOnWriteArrayList<AceLiveUtpPacket>()
        val port: Int = socket.localPort

        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "utp-handshake-peer"
        ) {
            try {
                repeat(expectedSyns) { index ->
                    val received = receiveRaw()
                    val syn = checkNotNull(AceLiveUtpCodec.decode(received.bytes))
                    check(syn.header.type == AceLiveUtpPacketType.SYN)
                    clientAddress.set(received.source)
                    syns += syn
                    responses(syn, index).forEach { response ->
                        sendRaw(AceLiveUtpCodec.encode(response), received.source)
                    }
                    synLatch.countDown()
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                while (synLatch.count > 0L) synLatch.countDown()
            }
        }

        fun awaitSyns(): Boolean = synLatch.await(3, TimeUnit.SECONDS)

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("uTP peer failed", it) }
        }

        fun sendToClient(packet: AceLiveUtpPacket) {
            val target = checkNotNull(clientAddress.get()) { "client address is unavailable" }
            sendRaw(AceLiveUtpCodec.encode(packet), target)
        }

        fun receiveFromClient(): AceLiveUtpPacket? = try {
            val received = receiveRaw()
            AceLiveUtpCodec.decode(received.bytes)
        } catch (_: SocketTimeoutException) {
            null
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }

        private fun receiveRaw(): ReceivedDatagram {
            val buffer = ByteArray(AceLiveUtpCodec.MAX_DATAGRAM_BYTES + 1)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return ReceivedDatagram(
                bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                source = packet.socketAddress
            )
        }

        private fun sendRaw(bytes: ByteArray, target: SocketAddress) {
            socket.send(DatagramPacket(bytes, bytes.size, target))
        }

        private data class ReceivedDatagram(
            val bytes: ByteArray,
            val source: SocketAddress
        )
    }
}
