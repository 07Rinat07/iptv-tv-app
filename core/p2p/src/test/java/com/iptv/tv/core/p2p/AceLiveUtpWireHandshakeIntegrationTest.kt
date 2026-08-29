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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUtpWireHandshakeIntegrationTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Test
    fun `full duplex established wire carries client handshake ack and remote payload`() = runBlocking {
        StrictWirePeer(loopback).use { peer ->
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

            val aceHandshake = ByteArray(66) { index -> (index + 1).toByte() }
            val sent = connection.send(aceHandshake)
            assertEquals(aceHandshake.size, sent.acceptedBytes)
            assertEquals(1, sent.transmissions.size)

            assertTrue(peer.awaitClientData())
            peer.throwIfFailed()
            assertArrayEquals(aceHandshake, peer.clientData.get()!!.payload)

            val ack = connection.receiveOnce()
            assertNotNull(ack)
            ack!!
            assertTrue(ack.deliveredBytes.isEmpty())
            assertEquals(setOf(2), ack.acknowledgedSequenceNumbers)
            assertFalse(ack.remoteClosed)

            assertTrue(peer.awaitServerDataSent())
            val inbound = connection.receiveOnce()
            assertNotNull(inbound)
            inbound!!
            assertArrayEquals(StrictWirePeer.SERVER_PAYLOAD, inbound.deliveredBytes)
            assertTrue(inbound.acknowledgedSequenceNumbers.isEmpty())
            assertFalse(inbound.remoteClosed)

            assertTrue(peer.awaitClientAck())
            peer.throwIfFailed()
            val clientAck = peer.clientAck.get()
            assertNotNull(clientAck)
            clientAck!!
            assertEquals(AceLiveUtpPacketType.STATE, clientAck.header.type)
            assertEquals(peer.clientSendConnectionId(), clientAck.header.connectionId)
            assertEquals(StrictWirePeer.SERVER_DATA_SEQUENCE, clientAck.header.acknowledgementNumber)

            connection.close()
        }
    }

    private class StrictWirePeer(
        bindAddress: InetAddress
    ) : Closeable {
        private val socket = DatagramSocket(InetSocketAddress(bindAddress, 0)).apply {
            soTimeout = 3_000
        }
        private val failure = AtomicReference<Throwable?>(null)
        private val syn = AtomicReference<AceLiveUtpPacket?>(null)
        private val clientAddress = AtomicReference<SocketAddress?>(null)
        val clientData = AtomicReference<AceLiveUtpPacket?>(null)
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
                check(data.payload.size == 66)
                clientData.set(data)
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
                        payload = SERVER_PAYLOAD
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
            val SERVER_PAYLOAD = byteArrayOf(9, 8, 7, 6)
        }
    }
}
