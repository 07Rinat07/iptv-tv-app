package com.iptv.tv.core.p2p

import java.io.Closeable
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JvmAceLiveTcpTransportFactoryWireIntegrationTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val handshakeCodec = AceLivePeerHandshakeCodec()

    @Test
    fun `physical TCP first loses to swarm valid uTP on production JVM factory`() = runBlocking {
        DualLivePeer(loopback).use { peer ->
            val raceMetric = AtomicReference<AceLiveTransportRaceMetric?>(null)
            val raceMetricSeen = CountDownLatch(1)
            val factory = JvmAceLiveTcpTransportFactory(
                metricsReporter = P2pRuntimeMetricsReporter { metric ->
                    if (metric is AceLiveTransportRaceMetric) {
                        raceMetric.set(metric)
                        raceMetricSeen.countDown()
                    }
                }
            )
            val policy = AceLiveTcpConnectionPolicy(
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 2_000,
                handshakeTimeoutMillis = 2_000,
                writeTimeoutMillis = 2_000
            )
            val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", peer.port)
            val raced = factory.connect(endpoint, policy)

            try {
                assertTrue(peer.awaitTcpAccepted())
                peer.throwIfFailed()

                val clientHandshake = handshakeCodec.encode(
                    swarmKey = SWARM_KEY,
                    peerId = CLIENT_PEER_ID
                )
                raced.write(clientHandshake)

                assertTrue(peer.awaitTcpWrongHandshakeSent())
                assertTrue(peer.awaitTcpRejectedAndClosed())
                peer.throwIfFailed()

                val serverHandshakeBytes = readExactly(
                    transport = raced,
                    byteCount = AceLivePeerHandshakeCodec.HANDSHAKE_BYTES
                )
                val decodedServerHandshake = handshakeCodec.decode(
                    buffer = serverHandshakeBytes,
                    expectedSwarmKey = SWARM_KEY
                )
                assertTrue(decodedServerHandshake is AceLivePeerHandshakeDecodeResult.Decoded)
                decodedServerHandshake as AceLivePeerHandshakeDecodeResult.Decoded
                assertEquals(
                    AceLivePeerHandshakeCodec.HANDSHAKE_BYTES,
                    decodedServerHandshake.consumedBytes
                )
                assertArrayEquals(SWARM_KEY, decodedServerHandshake.handshake.swarmKeyBytes())
                assertArrayEquals(UTP_SERVER_PEER_ID, decodedServerHandshake.handshake.peerIdBytes())

                assertTrue(peer.awaitUtpClientAck())
                peer.throwIfFailed()

                raced.write(APPLICATION_PAYLOAD)
                assertTrue(peer.awaitUtpApplicationData())
                peer.throwIfFailed()
                assertArrayEquals(APPLICATION_PAYLOAD, peer.utpApplicationPayload.get())

                assertTrue(raceMetricSeen.await(3, TimeUnit.SECONDS))
                val metric = raceMetric.get()
                assertNotNull(metric)
                metric!!
                assertEquals(AceLiveTransportKind.UTP, metric.winner)
                assertEquals("127.0.0.1", metric.endpointHost)
                assertEquals(peer.port, metric.endpointPort)

                val tcpMetric = metric.candidates.single {
                    it.transport == AceLiveTransportKind.TCP
                }
                val utpMetric = metric.candidates.single {
                    it.transport == AceLiveTransportKind.UTP
                }
                assertEquals(
                    AceLiveTransportCandidateOutcome.HANDSHAKE_REJECTED,
                    tcpMetric.outcome
                )
                assertEquals(
                    AceLiveTransportCandidateOutcome.QUALIFIED_WINNER,
                    utpMetric.outcome
                )
                assertNotNull(tcpMetric.physicalConnectedMillis)
                assertNotNull(utpMetric.physicalConnectedMillis)
            } finally {
                raced.close()
            }
        }
    }

    private suspend fun readExactly(
        transport: AceLiveTcpTransport,
        byteCount: Int
    ): ByteArray {
        val output = ByteArray(byteCount)
        var offset = 0

        repeat(MAX_READ_ATTEMPTS) {
            if (offset == byteCount) return output

            val readBuffer = ByteArray(byteCount - offset)
            when (val read = transport.read(readBuffer)) {
                -1 -> error("Qualified transport closed after $offset of $byteCount handshake bytes")
                0 -> Unit
                else -> {
                    readBuffer.copyInto(
                        destination = output,
                        destinationOffset = offset,
                        startIndex = 0,
                        endIndex = read
                    )
                    offset += read
                }
            }
        }

        check(offset == byteCount) {
            "Expected $byteCount handshake bytes from qualified transport, received $offset"
        }
        return output
    }

    private class DualLivePeer(
        bindAddress: InetAddress
    ) : Closeable {
        private val handshakeCodec = AceLivePeerHandshakeCodec()
        private val tcpServer = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
            soTimeout = 3_000
        }
        private val udpSocket = DatagramSocket(InetSocketAddress(bindAddress, tcpServer.localPort)).apply {
            soTimeout = 3_000
        }
        private val failure = AtomicReference<Throwable?>(null)
        private val tcpAccepted = CountDownLatch(1)
        private val tcpWrongHandshakeSent = CountDownLatch(1)
        private val tcpRejectedAndClosed = CountDownLatch(1)
        private val utpSyn = AtomicReference<AceLiveUtpPacket?>(null)
        private val utpClientAddress = AtomicReference<SocketAddress?>(null)
        private val utpClientAckSeen = CountDownLatch(1)
        private val utpApplicationDataSeen = CountDownLatch(1)
        val utpApplicationPayload = AtomicReference<ByteArray?>(null)
        val port: Int = tcpServer.localPort

        private val tcpWorker = thread(
            start = true,
            isDaemon = true,
            name = "ace-dual-live-tcp-peer"
        ) {
            try {
                tcpServer.accept().use { socket ->
                    socket.soTimeout = 3_000
                    tcpAccepted.countDown()
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val requestBytes = readExactly(input, AceLivePeerHandshakeCodec.HANDSHAKE_BYTES)
                    val request = handshakeCodec.decode(
                        buffer = requestBytes,
                        expectedSwarmKey = SWARM_KEY
                    )
                    check(request is AceLivePeerHandshakeDecodeResult.Decoded)
                    check(request.handshake.peerIdBytes().contentEquals(CLIENT_PEER_ID))

                    output.write(
                        handshakeCodec.encode(
                            swarmKey = WRONG_SWARM_KEY,
                            peerId = TCP_SERVER_PEER_ID
                        )
                    )
                    output.flush()
                    tcpWrongHandshakeSent.countDown()

                    check(input.read() == -1) {
                        "Rejected TCP candidate received downstream bytes instead of being closed"
                    }
                    tcpRejectedAndClosed.countDown()
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }

        private val utpWorker = thread(
            start = true,
            isDaemon = true,
            name = "ace-dual-live-utp-peer"
        ) {
            try {
                val synDatagram = receiveRaw()
                val receivedSyn = checkNotNull(AceLiveUtpCodec.decode(synDatagram.bytes))
                check(receivedSyn.header.type == AceLiveUtpPacketType.SYN)
                check(receivedSyn.header.sequenceNumber == 1)
                utpSyn.set(receivedSyn)
                utpClientAddress.set(synDatagram.source)

                check(tcpRejectedAndClosed.await(3, TimeUnit.SECONDS)) {
                    "uTP qualification started before rejected TCP candidate was closed"
                }

                sendUtp(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 10,
                            timestampDifferenceMicros = 0,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = UTP_SERVER_STATE_SEQUENCE,
                            acknowledgementNumber = receivedSyn.header.sequenceNumber
                        )
                    )
                )

                val handshakeDatagram = receiveRaw()
                val handshakeData = checkNotNull(AceLiveUtpCodec.decode(handshakeDatagram.bytes))
                check(handshakeData.header.type == AceLiveUtpPacketType.DATA)
                check(handshakeData.header.connectionId == clientUtpSendConnectionId())
                check(handshakeData.header.sequenceNumber == 2)
                check(handshakeData.header.acknowledgementNumber == UTP_SERVER_STATE_SEQUENCE)
                val request = handshakeCodec.decode(
                    buffer = handshakeData.payload,
                    expectedSwarmKey = SWARM_KEY
                )
                check(request is AceLivePeerHandshakeDecodeResult.Decoded)
                check(request.handshake.peerIdBytes().contentEquals(CLIENT_PEER_ID))

                sendUtp(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 20,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = UTP_SERVER_DATA_SEQUENCE,
                            acknowledgementNumber = handshakeData.header.sequenceNumber
                        )
                    )
                )
                sendUtp(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.DATA,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 30,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = UTP_SERVER_DATA_SEQUENCE,
                            acknowledgementNumber = handshakeData.header.sequenceNumber
                        ),
                        payload = handshakeCodec.encode(
                            swarmKey = SWARM_KEY,
                            peerId = UTP_SERVER_PEER_ID
                        )
                    )
                )

                val ackDatagram = receiveRaw()
                val ack = checkNotNull(AceLiveUtpCodec.decode(ackDatagram.bytes))
                check(ack.header.type == AceLiveUtpPacketType.STATE)
                check(ack.header.connectionId == clientUtpSendConnectionId())
                check(ack.header.acknowledgementNumber == UTP_SERVER_DATA_SEQUENCE)
                utpClientAckSeen.countDown()

                val applicationDatagram = receiveRaw()
                val application = checkNotNull(AceLiveUtpCodec.decode(applicationDatagram.bytes))
                check(application.header.type == AceLiveUtpPacketType.DATA)
                check(application.header.connectionId == clientUtpSendConnectionId())
                check(application.header.sequenceNumber == 3)
                check(application.header.acknowledgementNumber == UTP_SERVER_DATA_SEQUENCE)
                check(application.payload.contentEquals(APPLICATION_PAYLOAD))
                utpApplicationPayload.set(application.payload.copyOf())
                utpApplicationDataSeen.countDown()

                sendUtp(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 40,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = UTP_SERVER_DATA_SEQUENCE + 1,
                            acknowledgementNumber = application.header.sequenceNumber
                        )
                    )
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }

        fun awaitTcpAccepted(): Boolean = tcpAccepted.await(3, TimeUnit.SECONDS)

        fun awaitTcpWrongHandshakeSent(): Boolean = tcpWrongHandshakeSent.await(3, TimeUnit.SECONDS)

        fun awaitTcpRejectedAndClosed(): Boolean = tcpRejectedAndClosed.await(3, TimeUnit.SECONDS)

        fun awaitUtpClientAck(): Boolean = utpClientAckSeen.await(3, TimeUnit.SECONDS)

        fun awaitUtpApplicationData(): Boolean = utpApplicationDataSeen.await(3, TimeUnit.SECONDS)

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("dual live transport peer failed", it) }
        }

        override fun close() {
            runCatching { tcpServer.close() }
            runCatching { udpSocket.close() }
            tcpWorker.join(1_000)
            utpWorker.join(1_000)
        }

        private fun fail(error: Throwable) {
            failure.compareAndSet(null, error)
            tcpAccepted.countDown()
            tcpWrongHandshakeSent.countDown()
            tcpRejectedAndClosed.countDown()
            utpClientAckSeen.countDown()
            utpApplicationDataSeen.countDown()
        }

        private fun clientUtpSendConnectionId(): Int =
            ((checkNotNull(utpSyn.get()).header.connectionId + 1) and 0xffff)

        private fun sendUtp(packet: AceLiveUtpPacket) {
            val target = checkNotNull(utpClientAddress.get())
            val bytes = AceLiveUtpCodec.encode(packet)
            udpSocket.send(DatagramPacket(bytes, bytes.size, target))
        }

        private fun receiveRaw(): ReceivedDatagram {
            val buffer = ByteArray(AceLiveUtpCodec.MAX_DATAGRAM_BYTES + 1)
            val packet = DatagramPacket(buffer, buffer.size)
            udpSocket.receive(packet)
            return ReceivedDatagram(
                bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                source = packet.socketAddress
            )
        }

        private fun readExactly(input: InputStream, byteCount: Int): ByteArray {
            val output = ByteArray(byteCount)
            var offset = 0
            while (offset < byteCount) {
                val read = input.read(output, offset, byteCount - offset)
                check(read >= 0) { "TCP peer closed after $offset of $byteCount handshake bytes" }
                offset += read
            }
            return output
        }

        private data class ReceivedDatagram(
            val bytes: ByteArray,
            val source: SocketAddress
        )
    }

    private companion object {
        const val MAX_READ_ATTEMPTS = 8
        const val UTP_SERVER_STATE_SEQUENCE = 500
        const val UTP_SERVER_DATA_SEQUENCE = 501
        val SWARM_KEY = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
            (0x11 + index).toByte()
        }
        val WRONG_SWARM_KEY = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
            (0x71 + index).toByte()
        }
        val CLIENT_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x31 + index).toByte()
        }
        val TCP_SERVER_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x41 + index).toByte()
        }
        val UTP_SERVER_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x51 + index).toByte()
        }
        val APPLICATION_PAYLOAD = byteArrayOf(0x55, 0x54, 0x50, 0x2D, 0x57, 0x49, 0x4E)
    }
}
