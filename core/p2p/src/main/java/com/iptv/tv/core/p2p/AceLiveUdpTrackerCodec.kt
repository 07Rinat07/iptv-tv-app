package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AceLiveTrackerProtocolException(message: String) : IllegalArgumentException(message)

/** Pure BEP-15 wire codec used by Ace Live UDP tracker discovery. */
object AceLiveUdpTrackerCodec {
    const val CONNECT_REQUEST_BYTES: Int = 16
    const val ANNOUNCE_REQUEST_BYTES: Int = 98
    const val ANNOUNCE_RESPONSE_HEADER_BYTES: Int = 20
    const val COMPACT_IPV4_PEER_BYTES: Int = 6
    const val PEER_ID_BYTES: Int = 20

    private const val PROTOCOL_ID: Long = 0x41727101980L
    private const val ACTION_CONNECT: Int = 0
    private const val ACTION_ANNOUNCE: Int = 1
    private const val ACTION_ERROR: Int = 3
    private const val EVENT_STARTED: Int = 2

    fun encodeConnectRequest(transactionId: Int): ByteArray =
        ByteBuffer.allocate(CONNECT_REQUEST_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(PROTOCOL_ID)
            .putInt(ACTION_CONNECT)
            .putInt(transactionId)
            .array()

    fun decodeConnectResponse(bytes: ByteArray, expectedTransactionId: Int): Long {
        if (bytes.size < CONNECT_REQUEST_BYTES) {
            throw AceLiveTrackerProtocolException("BEP-15 connect response is truncated")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val action = buffer.int
        val transactionId = buffer.int
        if (transactionId != expectedTransactionId) {
            throw AceLiveTrackerProtocolException("BEP-15 connect transaction mismatch")
        }
        if (action == ACTION_ERROR) {
            throw AceLiveTrackerProtocolException("BEP-15 tracker rejected connect")
        }
        if (action != ACTION_CONNECT) {
            throw AceLiveTrackerProtocolException("Unexpected BEP-15 connect action")
        }
        return buffer.long
    }

    fun encodeAnnounceRequest(
        connectionId: Long,
        transactionId: Int,
        swarmKey: AceLiveSwarmKey,
        peerId: ByteArray,
        announcePort: Int,
        key: Int,
        numWant: Int,
        downloadedBytes: Long = 0,
        leftBytes: Long = Long.MAX_VALUE,
        uploadedBytes: Long = 0
    ): ByteArray {
        require(peerId.size == PEER_ID_BYTES) { "peerId must be exactly $PEER_ID_BYTES bytes" }
        require(announcePort in 1..65535) { "announcePort must be in 1..65535" }
        require(numWant in 1..MAX_NUM_WANT) { "numWant must be in 1..$MAX_NUM_WANT" }
        require(downloadedBytes >= 0 && leftBytes >= 0 && uploadedBytes >= 0) {
            "BEP-15 transfer counters must be non-negative"
        }

        return ByteBuffer.allocate(ANNOUNCE_REQUEST_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(connectionId)
            .putInt(ACTION_ANNOUNCE)
            .putInt(transactionId)
            .put(swarmKey.toByteArray())
            .put(peerId.copyOf())
            .putLong(downloadedBytes)
            .putLong(leftBytes)
            .putLong(uploadedBytes)
            .putInt(EVENT_STARTED)
            .putInt(0) // default IP
            .putInt(key)
            .putInt(numWant)
            .putShort(announcePort.toShort())
            .array()
    }

    fun decodeAnnounceResponse(
        bytes: ByteArray,
        expectedTransactionId: Int,
        maxPeers: Int,
        maxResponseBytes: Int
    ): List<AceLiveTcpPeerEndpoint> {
        require(maxPeers in 1..MAX_PARSED_PEERS) { "maxPeers must be in 1..$MAX_PARSED_PEERS" }
        require(maxResponseBytes >= ANNOUNCE_RESPONSE_HEADER_BYTES) {
            "maxResponseBytes is too small"
        }
        if (bytes.size > maxResponseBytes) {
            throw AceLiveTrackerProtocolException("BEP-15 announce response exceeds local byte cap")
        }
        if (bytes.size < 8) {
            throw AceLiveTrackerProtocolException("BEP-15 announce response is truncated")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val action = buffer.int
        val transactionId = buffer.int
        if (transactionId != expectedTransactionId) {
            throw AceLiveTrackerProtocolException("BEP-15 announce transaction mismatch")
        }
        if (action == ACTION_ERROR) {
            throw AceLiveTrackerProtocolException("BEP-15 tracker rejected announce")
        }
        if (action != ACTION_ANNOUNCE) {
            throw AceLiveTrackerProtocolException("Unexpected BEP-15 announce action")
        }
        if (bytes.size < ANNOUNCE_RESPONSE_HEADER_BYTES) {
            throw AceLiveTrackerProtocolException("BEP-15 announce header is truncated")
        }

        buffer.position(ANNOUNCE_RESPONSE_HEADER_BYTES)
        val peerBytes = bytes.size - ANNOUNCE_RESPONSE_HEADER_BYTES
        if (peerBytes % COMPACT_IPV4_PEER_BYTES != 0) {
            throw AceLiveTrackerProtocolException("Malformed BEP-15 compact peer list")
        }
        val peerCount = peerBytes / COMPACT_IPV4_PEER_BYTES
        if (peerCount > maxPeers) {
            throw AceLiveTrackerProtocolException("BEP-15 peer count exceeds local cap")
        }

        return buildList(peerCount) {
            repeat(peerCount) {
                val a = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val c = buffer.get().toInt() and 0xFF
                val d = buffer.get().toInt() and 0xFF
                val port = buffer.short.toInt() and 0xFFFF
                if (port == 0) {
                    throw AceLiveTrackerProtocolException("BEP-15 peer advertises port 0")
                }
                add(AceLiveTcpPeerEndpoint("$a.$b.$c.$d", port))
            }
        }
    }

    const val MAX_NUM_WANT: Int = 200
    const val MAX_PARSED_PEERS: Int = 512
}
