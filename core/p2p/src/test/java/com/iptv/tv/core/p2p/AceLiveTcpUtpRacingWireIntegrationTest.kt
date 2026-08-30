package com.iptv.tv.core.p2p

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTcpUtpRacingWireIntegrationTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val handshakeCodec = AceLivePeerHandshakeCodec()

    @Test
    fun `racing factory qualifies real uTP handshake and routes winner traffic`() = runBlocking {
        StrictRaceUtpPeer(
            bindAddress = loopback,
            expectedSwarmKey = SWARM_KEY,
            serverPeerId = SERVER_PEER_ID
        ).use { peer ->
            val utpFactory = JvmAceLiveUtpTransportFactory(
                addressResolver = { listOf(loopback) },
                connectAddress = { address, port ->
                    AceLiveUtpSocketConnector(
                        policy = AceLiveUtpSocketPolicy(
                            initialSynTimeoutMillis = 500,
                            maxSynTimeoutMillis = 500,
                            maxSynAttempts = 1,
                            establishedReceiveTimeoutMillis = 1_000
                        )
                    ).connect(AceLiveUtpSocketEndpoint(address, port))
                        ?.let(::AceLiveUtpByteStreamTransport)
                }
            )
            val factory = AceLiveTcpUtpRacingTransportFactory(
                tcpConnect = { _, _ ->
                    throw IOException("TCP intentionally unavailable for real uTP qualification")
                },
                utpConnect = { endpoint, policy ->
                    utpFactory.connect(endpoint, policy)
                }
            )
            val policy = AceLiveTcpConnectionPolicy(
                connectTimeoutMillis = 1_500,
                readTimeoutMillis = 1_500,
                handshakeTimeoutMillis = 1_500,
                writeTimeoutMillis = 1_500
            )
            val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", peer.port)
            val raced = factory.connect(endpoint, policy)

            try {
                assertTrue(peer.awaitStateSent())
                peer.throwIfFailed()

                val clientHandshakeBytes = handshakeCodec.encode(
                    swarmKey = SWARM_KEY,
                    peerId = CLIENT_PEER_ID
                )
                raced.write(clientHandshakeBytes)

                assertTrue(peer.awaitClientHandshake())
                peer.throwIfFailed()
                val clientHandshake = checkNotNull(peer.clientHandshake.get())
                assertArrayEquals(SWARM_KEY, clientHandshake.swarmKeyBytes())
                assertArrayEquals(CLIENT_PEER_ID, clientHandshake.peerIdBytes())

                val serverHandshakeBytes = readExactly(
                    transport = raced,
                    byteCount = AceLivePeerHandshakeCodec.HANDSHAKE_BYTES
                )
                val serverHandshake = handshakeCodec.decode(
                    buffer = serverHandshakeBytes,
                    expectedSwarmKey = SWARM_KEY
                )
                assertTrue(serverHandshake is AceLivePeerHandshakeDecodeResult.Decoded)
                serverHandshake as AceLivePeerHandshakeDecodeResult.Decoded
                assertEquals(AceLivePeerHandshakeCodec.HANDSHAKE_BYTES, serverHandshake.consumedBytes)
                assertArrayEquals(SWARM_KEY, serverHandshake.handshake.swarmKeyBytes())
                assertArrayEquals(SERVER_PEER_ID, serverHandshake.handshake.peerIdBytes())

                assertTrue(peer.awaitClientAck())
                peer.throwIfFailed()
                val clientAck = checkNotNull(peer.clientAck.get())
                assertEquals(AceLiveUtpPacketType.STATE, clientAck.header.type)
                assertEquals(peer.clientSendConnectionId(), clientAck.header.connectionId)
                assertEquals(StrictRaceUtpPeer.SERVER_DATA_SEQUENCE, clientAck.header.acknowledgementNumber)

                raced.write(APPLICATION_PAYLOAD)

                assertTrue(peer.awaitApplicationData())
                peer.throwIfFailed()
                val applicationData = checkNotNull(peer.applicationData.get())
                assertArrayEquals(APPLICATION_PAYLOAD, applicationData.payload)
                assertEquals(AceLiveUtpPacketType.DATA, applicationData.header.type)
                assertEquals(peer.clientSendConnectionId(), applicationData.header.connectionId)
                assertEquals(3, applicationData.header.sequenceNumber)
                assertEquals(
                    StrictRaceUtpPeer.SERVER_DATA_SEQUENCE,
                    applicationData.header.acknowledgementNumber
                )
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
                -1 -> error("Qualified uTP peer closed after $offset of $byteCount handshake bytes")
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

    private class StrictRaceUtpPeer(
        bindAddress: InetAddress,
        expectedSwarmKey: ByteArray,
        serverPeerId: ByteArray
    ) : Closeable {
        private val expectedSwarmKey = expectedSwarmKey.copyOf()
        private val serverHandshake = AceLivePeerHandshakeCodec().encode(
            swarmKey = expectedSwarmKey,
            peerId = serverPeerId
        )
        private val handshakeCodec = AceLivePeerHandshakeCodec()
        private val socket = DatagramSocket(InetSocketAddress(bindAddress, 0)).apply {
            soTimeout = 3_000
        }
        private val failure = AtomicReference<Throwable?>(null)
        private val syn = AtomicReference<AceLiveUtpPacket?>(null)
        private val clientAddress = AtomicReference<SocketAddress?>(null)
        val clientHandshake = AtomicReference<AceLivePeerHandshake?>(null)
        val clientAck = AtomicReference<AceLiveUtpPacket?>(null)
        val applicationData = AtomicReference<AceLiveUtpPacket?>(null)
        private val stateSent = CountDownLatch(1)
        private val clientHandshakeSeen = CountDownLatch(1)
        private val clientAckSeen = CountDownLatch(1)
        private val applicationDataSeen = CountDownLatch(1)
        val port: Int = socket.localPort

        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "utp-racing-wire-peer"
        ) {
            try {
                val synDatagram = receiveRaw()
                val receivedSyn = checkNotNull(AceLiveUtpCodec.decode(synDatagram.bytes))
                check(receivedSyn.header.type == AceLiveUtpPacketType.SYN)
                check(receivedSyn.header.sequenceNumber == 1)
                syn.set(receivedSyn)
                clientAddress.set(synDatagram.source)

                send(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 10,
                            timestampDifferenceMicros = 0,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = SERVER_STATE_SEQUENCE,
                            acknowledgementNumber = receivedSyn.header.sequenceNumber
                        )
                    )
                )
                stateSent.countDown()

                val handshakeDatagram = receiveRaw()
                val handshakeData = checkNotNull(AceLiveUtpCodec.decode(handshakeDatagram.bytes))
                check(handshakeData.header.type == AceLiveUtpPacketType.DATA)
                check(handshakeData.header.connectionId == clientSendConnectionId())
                check(handshakeData.header.sequenceNumber == 2)
                check(handshakeData.header.acknowledgementNumber == SERVER_STATE_SEQUENCE)
                check(handshakeData.payload.size == AceLivePeerHandshakeCodec.HANDSHAKE_BYTES)

                val decodedClientHandshake = handshakeCodec.decode(
                    buffer = handshakeData.payload,
                    expectedSwarmKey = expectedSwarmKey
                )
                check(decodedClientHandshake is AceLivePeerHandshakeDecodeResult.Decoded)
                check(decodedClientHandshake.consumedBytes == AceLivePeerHandshakeCodec.HANDSHAKE_BYTES)
                clientHandshake.set(decodedClientHandshake.handshake)
                clientHandshakeSeen.countDown()

                send(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 20,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = SERVER_DATA_SEQUENCE,
                            acknowledgementNumber = handshakeData.header.sequenceNumber
                        )
                    )
                )
                send(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.DATA,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 30,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = SERVER_DATA_SEQUENCE,
                            acknowledgementNumber = handshakeData.header.sequenceNumber
                        ),
                        payload = serverHandshake
                    )
                )

                val ackDatagram = receiveRaw()
                val ack = checkNotNull(AceLiveUtpCodec.decode(ackDatagram.bytes))
                check(ack.header.type == AceLiveUtpPacketType.STATE)
                check(ack.header.connectionId == clientSendConnectionId())
                check(ack.header.acknowledgementNumber == SERVER_DATA_SEQUENCE)
                clientAck.set(ack)
                clientAckSeen.countDown()

                val applicationDatagram = receiveRaw()
                val appData = checkNotNull(AceLiveUtpCodec.decode(applicationDatagram.bytes))
                check(appData.header.type == AceLiveUtpPacketType.DATA)
                check(appData.header.connectionId == clientSendConnectionId())
                check(appData.header.sequenceNumber == 3)
                check(appData.header.acknowledgementNumber == SERVER_DATA_SEQUENCE)
                check(appData.payload.contentEquals(APPLICATION_PAYLOAD))
                applicationData.set(appData)
                applicationDataSeen.countDown()

                send(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 40,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = SERVER_DATA_SEQUENCE + 1,
                            acknowledgementNumber = appData.header.sequenceNumber
                        )
                    )
                )
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                stateSent.countDown()
                clientHandshakeSeen.countDown()
                clientAckSeen.countDown()
                applicationDataSeen.countDown()
            }
        }

        fun clientSendConnectionId(): Int =
            ((checkNotNull(syn.get()).header.connectionId + 1) and 0xffff)

        fun awaitStateSent(): Boolean = stateSent.await(3, TimeUnit.SECONDS)

        fun awaitClientHandshake(): Boolean = clientHandshakeSeen.await(3, TimeUnit.SECONDS)

        fun awaitClientAck(): Boolean = clientAckSeen.await(3, TimeUnit.SECONDS)

        fun awaitApplicationData(): Boolean = applicationDataSeen.await(3, TimeUnit.SECONDS)

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("strict racing uTP peer failed", it) }
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }

        private fun send(packet: AceLiveUtpPacket) {
            val target = checkNotNull(clientAddress.get())
            val bytes = AceLiveUtpCodec.encode(packet)
            socket.send(DatagramPacket(bytes, bytes.size, target))
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

        private data class ReceivedDatagram(
            val bytes: ByteArray,
            val source: SocketAddress
        )

        companion object {
            const val SERVER_STATE_SEQUENCE = 500
            const val SERVER_DATA_SEQUENCE = 501
        }
    }

    private companion object {
        const val MAX_READ_ATTEMPTS = 6
        val SWARM_KEY = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
            (0x11 + index).toByte()
        }
        val CLIENT_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x31 + index).toByte()
        }
        val SERVER_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x51 + index).toByte()
        }
        val APPLICATION_PAYLOAD = byteArrayOf(0x55, 0x54, 0x50, 0x2D, 0x4F, 0x4B)
    }
}
