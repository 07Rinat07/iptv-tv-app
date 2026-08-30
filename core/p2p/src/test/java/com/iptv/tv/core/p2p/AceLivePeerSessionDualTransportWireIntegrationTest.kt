package com.iptv.tv.core.p2p

import java.io.Closeable
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerSessionDualTransportWireIntegrationTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Test
    fun `pool feeds verified uTP peer state into session and dispatches scheduled chunk requests`() = runBlocking {
        DualLiveSessionPeer(loopback).use { peer ->
            val raceMetric = AtomicReference<AceLiveTransportRaceMetric?>(null)
            val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = AceLivePeerSessionCoordinator(
                geometry = AceLiveTransportGeometry(
                    pieceLengthBytes = 10,
                    chunkLengthBytes = 4,
                    bitrate = 1
                ),
                initialNextNeededPiece = 10,
                maxInFlightPerPeer = 1
            )
            val pool = AceLiveTcpConnectionPool(
                scope = scope,
                session = session,
                transportFactory = JvmAceLiveTcpTransportFactory(
                    metricsReporter = P2pRuntimeMetricsReporter { metric ->
                        if (metric is AceLiveTransportRaceMetric) {
                            raceMetric.set(metric)
                        }
                    }
                ),
                policy = AceLiveTcpConnectionPolicy(
                    connectTimeoutMillis = 2_000,
                    readTimeoutMillis = 2_000,
                    handshakeTimeoutMillis = 2_000,
                    writeTimeoutMillis = 2_000,
                    readBufferBytes = 4 * 1024,
                    maxConcurrentPeers = 1,
                    maxConcurrentInboundPeers = 0,
                    maxReconnectAttempts = 0,
                    maxPreHandshakeReconnectAttempts = 0,
                    reconnectDelayMillis = 0
                ),
                startupCandidateStaggerMillis = 0,
                onEvent = events::add
            )

            try {
                pool.startPeer(
                    peerId = PEER_ID,
                    endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", peer.port),
                    swarmKey = SWARM_KEY,
                    localPeerId = LOCAL_PEER_ID
                )

                assertTrue(peer.awaitTcpAccepted())
                assertTrue(peer.awaitTcpRejectedAndClosed())
                assertTrue(peer.awaitUtpApplicationHandshake())

                awaitCondition {
                    val quality = pool.peerProductionSnapshot()
                    quality.connectedPeers == 1 &&
                        quality.handshakedPeers == 1 &&
                        quality.windowUsefulPeers == 1 &&
                        quality.unchokedPeers == 1
                }
                awaitCondition {
                    events.filterIsInstance<AceLiveTcpPoolEvent.Ingress>().any { event ->
                        event.peerId == PEER_ID &&
                            event.result.metadataUpdates.any { window ->
                                window.minPiece == 10L && window.maxPiece == 12L
                            }
                    }
                }

                val ingress = events.filterIsInstance<AceLiveTcpPoolEvent.Ingress>()
                    .first { event ->
                        event.peerId == PEER_ID &&
                            event.result.metadataUpdates.any { window ->
                                window.minPiece == 10L && window.maxPiece == 12L
                            }
                    }
                assertEquals(2, ingress.result.decodedFrames)
                assertEquals(10L, pool.nextNeededPiece())

                val dispatch = pool.scheduleAndDispatch(
                    head = 10,
                    maxInFlightPerPeer = 1
                )

                assertEquals(3, dispatch.scheduledFrames)
                assertEquals(3, dispatch.selectedFrames)
                assertEquals(3, dispatch.sentFrames)
                assertTrue(dispatch.failedPeerIds.isEmpty())
                assertEquals(PEER_ID, session.ownerOf(10))

                assertTrue(peer.awaitChunkRequests())
                peer.throwIfFailed()
                val requests = peer.chunkRequestPayloads.toList()
                assertEquals(3, requests.size)
                requests.forEachIndexed { chunkIndex, payload ->
                    assertArrayEquals(expectedChunkRequestFrame(chunkIndex), payload)
                }

                awaitCondition { raceMetric.get() != null }
                val metric = raceMetric.get()
                assertNotNull(metric)
                metric!!
                assertEquals(AceLiveTransportKind.UTP, metric.winner)
                assertEquals(peer.port, metric.endpointPort)
                assertEquals(
                    AceLiveTransportCandidateOutcome.HANDSHAKE_REJECTED,
                    metric.candidates.single { it.transport == AceLiveTransportKind.TCP }.outcome
                )
                assertEquals(
                    AceLiveTransportCandidateOutcome.QUALIFIED_WINNER,
                    metric.candidates.single { it.transport == AceLiveTransportKind.UTP }.outcome
                )

                val quality = pool.peerProductionSnapshot()
                assertEquals(1, quality.connectedPeers)
                assertEquals(1, quality.handshakedPeers)
                assertEquals(1, quality.windowUsefulPeers)
                assertEquals(1, quality.unchokedPeers)
            } finally {
                pool.close()
                scope.cancel()
            }
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(3_000) {
            while (!condition()) {
                delay(5)
            }
        }
    }

    private class DualLiveSessionPeer(
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
        private val tcpRejectedAndClosed = CountDownLatch(1)
        private val utpApplicationHandshakeSeen = CountDownLatch(1)
        private val chunkRequestsSeen = CountDownLatch(1)
        private val utpSyn = AtomicReference<AceLiveUtpPacket?>(null)
        private val utpClientAddress = AtomicReference<SocketAddress?>(null)
        val chunkRequestPayloads = CopyOnWriteArrayList<ByteArray>()
        val port: Int = tcpServer.localPort

        private val tcpWorker = thread(
            start = true,
            isDaemon = true,
            name = "ace-session-dual-tcp-peer"
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

                    output.write(
                        handshakeCodec.encode(
                            swarmKey = WRONG_SWARM_KEY,
                            peerId = TCP_SERVER_PEER_ID
                        )
                    )
                    output.flush()

                    check(input.read() == -1) {
                        "Rejected TCP candidate received pool application bytes instead of closing"
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
            name = "ace-session-dual-utp-peer"
        ) {
            try {
                val synDatagram = receiveRaw()
                val receivedSyn = checkNotNull(AceLiveUtpCodec.decode(synDatagram.bytes))
                check(receivedSyn.header.type == AceLiveUtpPacketType.SYN)
                check(receivedSyn.header.sequenceNumber == 1)
                utpSyn.set(receivedSyn)
                utpClientAddress.set(synDatagram.source)

                check(tcpRejectedAndClosed.await(3, TimeUnit.SECONDS)) {
                    "uTP candidate was released before wrong-swarm TCP candidate was rejected"
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

                sendUtp(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 20,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = UTP_SERVER_HANDSHAKE_SEQUENCE,
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
                            sequenceNumber = UTP_SERVER_HANDSHAKE_SEQUENCE,
                            acknowledgementNumber = handshakeData.header.sequenceNumber
                        ),
                        payload = handshakeCodec.encode(
                            swarmKey = SWARM_KEY,
                            peerId = UTP_SERVER_PEER_ID
                        )
                    )
                )

                val serverHandshakeAck = receiveUtpPacket()
                check(serverHandshakeAck.header.type == AceLiveUtpPacketType.STATE)
                check(serverHandshakeAck.header.connectionId == clientUtpSendConnectionId())
                check(serverHandshakeAck.header.acknowledgementNumber == UTP_SERVER_HANDSHAKE_SEQUENCE)

                val extended = receiveUtpPacket()
                check(extended.header.type == AceLiveUtpPacketType.DATA)
                check(extended.header.connectionId == clientUtpSendConnectionId())
                check(extended.header.sequenceNumber == 3)
                check(extended.header.acknowledgementNumber == UTP_SERVER_HANDSHAKE_SEQUENCE)
                check(extended.payload.size > 5)
                check((extended.payload[4].toInt() and 0xff) == 20)
                sendClientDataAck(receivedSyn, extended.header.sequenceNumber, 40)

                val interested = receiveUtpPacket()
                check(interested.header.type == AceLiveUtpPacketType.DATA)
                check(interested.header.connectionId == clientUtpSendConnectionId())
                check(interested.header.sequenceNumber == 4)
                check(interested.header.acknowledgementNumber == UTP_SERVER_HANDSHAKE_SEQUENCE)
                check(interested.payload.contentEquals(INTERESTED_FRAME))
                sendClientDataAck(receivedSyn, interested.header.sequenceNumber, 50)
                utpApplicationHandshakeSeen.countDown()

                sendUtp(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.DATA,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 60,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = UTP_SERVER_PEER_STATE_SEQUENCE,
                            acknowledgementNumber = interested.header.sequenceNumber
                        ),
                        payload = frame(
                            id = 11,
                            payload = compactLiveStatus(minPiece = 10, maxPiece = 12, position = 12)
                        ) + frame(id = 1)
                    )
                )

                val peerStateAck = receiveUtpPacket()
                check(peerStateAck.header.type == AceLiveUtpPacketType.STATE)
                check(peerStateAck.header.connectionId == clientUtpSendConnectionId())
                check(peerStateAck.header.acknowledgementNumber == UTP_SERVER_PEER_STATE_SEQUENCE)

                repeat(EXPECTED_CHUNK_REQUESTS) { index ->
                    val chunkRequest = receiveUtpPacket()
                    check(chunkRequest.header.type == AceLiveUtpPacketType.DATA)
                    check(chunkRequest.header.connectionId == clientUtpSendConnectionId())
                    check(chunkRequest.header.sequenceNumber == 5 + index)
                    check(chunkRequest.header.acknowledgementNumber == UTP_SERVER_PEER_STATE_SEQUENCE)
                    check(chunkRequest.payload.contentEquals(expectedChunkRequestFrame(index)))
                    chunkRequestPayloads += chunkRequest.payload.copyOf()
                    sendClientDataAck(
                        syn = receivedSyn,
                        acknowledgementNumber = chunkRequest.header.sequenceNumber,
                        timestampMicros = 70L + index * 10L
                    )
                }
                chunkRequestsSeen.countDown()
            } catch (error: Throwable) {
                fail(error)
            }
        }

        fun awaitTcpAccepted(): Boolean = tcpAccepted.await(3, TimeUnit.SECONDS)

        fun awaitTcpRejectedAndClosed(): Boolean = tcpRejectedAndClosed.await(3, TimeUnit.SECONDS)

        fun awaitUtpApplicationHandshake(): Boolean =
            utpApplicationHandshakeSeen.await(3, TimeUnit.SECONDS)

        fun awaitChunkRequests(): Boolean = chunkRequestsSeen.await(3, TimeUnit.SECONDS)

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("dual live session peer failed", it) }
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
            tcpRejectedAndClosed.countDown()
            utpApplicationHandshakeSeen.countDown()
            chunkRequestsSeen.countDown()
        }

        private fun clientUtpSendConnectionId(): Int =
            ((checkNotNull(utpSyn.get()).header.connectionId + 1) and 0xffff)

        private fun sendClientDataAck(
            syn: AceLiveUtpPacket,
            acknowledgementNumber: Int,
            timestampMicros: Long
        ) {
            sendUtp(
                AceLiveUtpPacket(
                    header = AceLiveUtpHeader(
                        type = AceLiveUtpPacketType.STATE,
                        connectionId = syn.header.connectionId,
                        timestampMicros = timestampMicros,
                        timestampDifferenceMicros = 10,
                        receiveWindowBytes = 64 * 1024L,
                        sequenceNumber = UTP_SERVER_PEER_STATE_SEQUENCE + 1,
                        acknowledgementNumber = acknowledgementNumber
                    )
                )
            )
        }

        private fun sendUtp(packet: AceLiveUtpPacket) {
            val target = checkNotNull(utpClientAddress.get())
            val bytes = AceLiveUtpCodec.encode(packet)
            udpSocket.send(DatagramPacket(bytes, bytes.size, target))
        }

        private fun receiveUtpPacket(): AceLiveUtpPacket =
            checkNotNull(AceLiveUtpCodec.decode(receiveRaw().bytes))

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
        const val PEER_ID = 77L
        const val UTP_SERVER_STATE_SEQUENCE = 500
        const val UTP_SERVER_HANDSHAKE_SEQUENCE = 501
        const val UTP_SERVER_PEER_STATE_SEQUENCE = 502
        const val EXPECTED_CHUNK_REQUESTS = 3
        val SWARM_KEY = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
            (0x11 + index).toByte()
        }
        val WRONG_SWARM_KEY = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
            (0x71 + index).toByte()
        }
        val LOCAL_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x31 + index).toByte()
        }
        val TCP_SERVER_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x41 + index).toByte()
        }
        val UTP_SERVER_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x51 + index).toByte()
        }
        val INTERESTED_FRAME = byteArrayOf(0, 0, 0, 1, 2)

        fun frame(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
            val bodyLength = 1 + payload.size
            return byteArrayOf(
                (bodyLength ushr 24).toByte(),
                (bodyLength ushr 16).toByte(),
                (bodyLength ushr 8).toByte(),
                bodyLength.toByte(),
                id.toByte()
            ) + payload
        }

        fun compactLiveStatus(minPiece: Long, maxPiece: Long, position: Long): ByteArray =
            ascii(
                "d1:ai1e1:bi0e1:ci1e1:di0e1:ei1e1:fi0e" +
                    "1:gi${position}e1:hi1e1:ii${minPiece}e1:ji${maxPiece}e" +
                    "1:ki0e1:li1e1:mi-1e1:ni2e1:oi0e1:pi0e1:qi1e" +
                    "1:ri${position}e1:si${position}e1:ti-1e1:ui1ee"
            )

        fun expectedChunkRequestFrame(chunkIndex: Int): ByteArray = byteArrayOf(
            0, 0, 0, 11,
            6,
            0, 0, 0, 0,
            0, 0, 0, 10,
            0, chunkIndex.toByte()
        )

        fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)
    }
}
