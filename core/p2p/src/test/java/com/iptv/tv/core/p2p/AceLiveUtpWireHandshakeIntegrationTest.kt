package com.iptv.tv.core.p2p

import java.io.Closeable
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUtpWireHandshakeIntegrationTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val handshakeCodec = AceLivePeerHandshakeCodec()

    @Test
    fun `full duplex byte stream carries production Ace handshake and uTP ack`() = runBlocking {
        StrictWirePeer(
            bindAddress = loopback,
            expectedSwarmKey = SWARM_KEY,
            serverPeerId = SERVER_PEER_ID
        ).use { peer ->
            val connection = AceLiveUtpSocketConnector(
                policy = AceLiveUtpSocketPolicy(
                    initialSynTimeoutMillis = 500,
                    maxSynTimeoutMillis = 500,
                    maxSynAttempts = 1,
                    establishedReceiveTimeoutMillis = 1_000
                )
            ).connect(AceLiveUtpSocketEndpoint(loopback, peer.port))

            assertNotNull(connection)
            connection!!
            assertTrue(peer.awaitStateSent())
            peer.throwIfFailed()

            val transport = AceLiveUtpByteStreamTransport(connection)
            val clientHandshakeBytes = handshakeCodec.encode(
                swarmKey = SWARM_KEY,
                peerId = CLIENT_PEER_ID
            )
            transport.write(clientHandshakeBytes)

            assertTrue(peer.awaitClientData())
            peer.throwIfFailed()
            assertArrayEquals(clientHandshakeBytes, peer.clientData.get()!!.payload)

            val clientHandshake = peer.clientHandshake.get()
            assertNotNull(clientHandshake)
            clientHandshake!!
            assertArrayEquals(SWARM_KEY, clientHandshake.swarmKeyBytes())
            assertArrayEquals(CLIENT_PEER_ID, clientHandshake.peerIdBytes())
            assertArrayEquals(
                AceLivePeerHandshakeCodec.extensionProtocolReservedBytes(),
                clientHandshake.reservedBytes()
            )

            assertTrue(peer.awaitServerDataSent())
            val serverHandshakeBytes = readExactly(
                transport = transport,
                byteCount = AceLivePeerHandshakeCodec.HANDSHAKE_BYTES
            )
            val decodedServerHandshake = handshakeCodec.decode(
                buffer = serverHandshakeBytes,
                expectedSwarmKey = SWARM_KEY
            )
            assertTrue(decodedServerHandshake is AceLivePeerHandshakeDecodeResult.Decoded)
            decodedServerHandshake as AceLivePeerHandshakeDecodeResult.Decoded
            assertEquals(AceLivePeerHandshakeCodec.HANDSHAKE_BYTES, decodedServerHandshake.consumedBytes)
            assertArrayEquals(SWARM_KEY, decodedServerHandshake.handshake.swarmKeyBytes())
            assertArrayEquals(SERVER_PEER_ID, decodedServerHandshake.handshake.peerIdBytes())
            assertArrayEquals(
                AceLivePeerHandshakeCodec.extensionProtocolReservedBytes(),
                decodedServerHandshake.handshake.reservedBytes()
            )

            assertTrue(peer.awaitClientAck())
            peer.throwIfFailed()
            val clientAck = peer.clientAck.get()
            assertNotNull(clientAck)
            clientAck!!
            assertEquals(AceLiveUtpPacketType.STATE, clientAck.header.type)
            assertEquals(peer.clientSendConnectionId(), clientAck.header.connectionId)
            assertEquals(StrictWirePeer.SERVER_DATA_SEQUENCE, clientAck.header.acknowledgementNumber)

            transport.close()
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
                -1 -> error("Remote uTP peer closed after $offset of $byteCount handshake bytes")
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
            "Expected $byteCount handshake bytes from uTP byte stream, received $offset"
        }
        return output
    }

    private class StrictWirePeer(
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
        val clientData = AtomicReference<AceLiveUtpPacket?>(null)
        val clientHandshake = AtomicReference<AceLivePeerHandshake?>(null)
        val clientAck = AtomicReference<AceLiveUtpPacket?>(null)
        private val stateSent = CountDownLatch(1)
        private val clientDataSeen = CountDownLatch(1)
        private val serverDataSent = CountDownLatch(1)
        private val clientAckSeen = CountDownLatch(1)
        val port: Int = socket.localPort

        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "utp-strict-wire-peer"
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

                val dataDatagram = receiveRaw()
                val data = checkNotNull(AceLiveUtpCodec.decode(dataDatagram.bytes))
                check(data.header.type == AceLiveUtpPacketType.DATA)
                check(data.header.connectionId == clientSendConnectionId())
                check(data.header.sequenceNumber == 2)
                check(data.header.acknowledgementNumber == SERVER_STATE_SEQUENCE)
                check(data.payload.size == AceLivePeerHandshakeCodec.HANDSHAKE_BYTES)
                val decodedClientHandshake = handshakeCodec.decode(
                    buffer = data.payload,
                    expectedSwarmKey = expectedSwarmKey
                )
                check(decodedClientHandshake is AceLivePeerHandshakeDecodeResult.Decoded)
                check(decodedClientHandshake.consumedBytes == AceLivePeerHandshakeCodec.HANDSHAKE_BYTES)
                clientData.set(data)
                clientHandshake.set(decodedClientHandshake.handshake)
                clientDataSeen.countDown()

                send(
                    AceLiveUtpPacket(
                        header = AceLiveUtpHeader(
                            type = AceLiveUtpPacketType.STATE,
                            connectionId = receivedSyn.header.connectionId,
                            timestampMicros = 20,
                            timestampDifferenceMicros = 10,
                            receiveWindowBytes = 64 * 1024L,
                            sequenceNumber = SERVER_DATA_SEQUENCE,
                            acknowledgementNumber = data.header.sequenceNumber
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
                            acknowledgementNumber = data.header.sequenceNumber
                        ),
                        payload = serverHandshake
                    )
                )
                serverDataSent.countDown()

                val ackDatagram = receiveRaw()
                val ack = checkNotNull(AceLiveUtpCodec.decode(ackDatagram.bytes))
                clientAck.set(ack)
                clientAckSeen.countDown()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                stateSent.countDown()
                clientDataSeen.countDown()
                serverDataSent.countDown()
                clientAckSeen.countDown()
            }
        }

        fun clientSendConnectionId(): Int =
            ((checkNotNull(syn.get()).header.connectionId + 1) and 0xffff)

        fun awaitStateSent(): Boolean = stateSent.await(3, TimeUnit.SECONDS)

        fun awaitClientData(): Boolean = clientDataSeen.await(3, TimeUnit.SECONDS)

        fun awaitServerDataSent(): Boolean = serverDataSent.await(3, TimeUnit.SECONDS)

        fun awaitClientAck(): Boolean = clientAckSeen.await(3, TimeUnit.SECONDS)

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("strict uTP peer failed", it) }
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
        const val MAX_READ_ATTEMPTS = 4
        val SWARM_KEY = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
            (0x20 + index).toByte()
        }
        val CLIENT_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x40 + index).toByte()
        }
        val SERVER_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x60 + index).toByte()
        }
    }
}
