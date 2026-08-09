package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** Immutable 20-byte Mainline DHT node id. It is not an Ace content id or a swarm key. */
class AceLiveDhtNodeId private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is AceLiveDhtNodeId && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "AceLiveDhtNodeId(redacted)"

    companion object {
        const val BYTES: Int = 20
        private val random = SecureRandom()

        fun fromBytes(bytes: ByteArray): AceLiveDhtNodeId {
            require(bytes.size == BYTES) { "DHT node id must be exactly $BYTES bytes" }
            return AceLiveDhtNodeId(bytes)
        }

        fun random(): AceLiveDhtNodeId =
            fromBytes(ByteArray(BYTES).also(random::nextBytes))
    }
}

data class AceLiveDhtNodeContact(
    val nodeId: AceLiveDhtNodeId,
    val endpoint: AceLiveTcpPeerEndpoint
)

class AceLiveDhtGetPeersResponse(
    val remoteNodeId: AceLiveDhtNodeId,
    peers: List<AceLiveTcpPeerEndpoint>,
    nodes: List<AceLiveDhtNodeContact>
) {
    val peers: List<AceLiveTcpPeerEndpoint> = peers.toList()
    val nodes: List<AceLiveDhtNodeContact> = nodes.toList()
}

class AceLiveDhtProtocolException(message: String) : IllegalArgumentException(message)

/**
 * Bounded BEP-5/KRPC codec used only by the Ace Live DHT discovery adapter.
 *
 * This client performs lookup-only `get_peers` queries and has no inbound DHT listener. Every
 * outgoing query therefore carries BEP-43 `ro=1`, so remote nodes do not retain our ephemeral UDP
 * endpoint in their routing tables. `announce_peer` remains intentionally absent until the app owns
 * a real inbound peer-listener port.
 */
object AceLiveDhtCodec {
    const val DEFAULT_MAX_PACKET_BYTES: Int = 8 * 1024
    const val DEFAULT_MAX_PEERS: Int = 128
    const val DEFAULT_MAX_NODES: Int = 128
    const val MAX_TRANSACTION_ID_BYTES: Int = 8

    private const val COMPACT_PEER_BYTES = 6
    private const val COMPACT_NODE_BYTES = 26
    private const val MAX_BENCODE_DEPTH = 8
    private const val MAX_BENCODE_NODES = 1024
    private const val MAX_CONTAINER_ENTRIES = 512
    private const val MAX_STRING_BYTES = DEFAULT_MAX_PACKET_BYTES

    fun encodeGetPeersQuery(
        transactionId: ByteArray,
        nodeId: AceLiveDhtNodeId,
        swarmKey: AceLiveSwarmKey
    ): ByteArray {
        validateTransactionId(transactionId)
        val output = ByteArrayOutputStream(128)
        output.writeAscii("d1:ad2:id20:")
        output.write(nodeId.toByteArray())
        output.writeAscii("9:info_hash20:")
        output.write(swarmKey.toByteArray())
        output.writeAscii("e1:q9:get_peers2:roi1e1:t")
        output.writeAscii(transactionId.size.toString())
        output.write(':'.code)
        output.write(transactionId)
        output.writeAscii("1:y1:qe")
        return output.toByteArray()
    }

    fun decodeGetPeersResponse(
        bytes: ByteArray,
        expectedTransactionId: ByteArray,
        maxPeers: Int = DEFAULT_MAX_PEERS,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxPacketBytes: Int = DEFAULT_MAX_PACKET_BYTES
    ): AceLiveDhtGetPeersResponse {
        validateTransactionId(expectedTransactionId)
        require(maxPeers in 1..DEFAULT_MAX_PEERS) { "maxPeers must be in 1..$DEFAULT_MAX_PEERS" }
        require(maxNodes in 1..DEFAULT_MAX_NODES) { "maxNodes must be in 1..$DEFAULT_MAX_NODES" }
        require(maxPacketBytes in 128..65_507) { "maxPacketBytes must be in 128..65507" }
        if (bytes.isEmpty() || bytes.size > maxPacketBytes) {
            throw AceLiveDhtProtocolException("KRPC packet exceeds local byte bounds")
        }

        val root = BencodeParser(bytes).parseRoot() as? BValue.Dict
            ?: throw AceLiveDhtProtocolException("KRPC root must be a dictionary")
        val transactionId = root.byteString("t")
            ?: throw AceLiveDhtProtocolException("KRPC response is missing transaction id")
        if (!transactionId.contentEquals(expectedTransactionId)) {
            throw AceLiveDhtProtocolException("KRPC transaction id mismatch")
        }
        val type = root.byteString("y")?.ascii()
            ?: throw AceLiveDhtProtocolException("KRPC response is missing message type")
        if (type != "r") {
            throw AceLiveDhtProtocolException("KRPC message is not a response")
        }
        val response = root.values["r"] as? BValue.Dict
            ?: throw AceLiveDhtProtocolException("KRPC response dictionary is missing")
        val remoteIdBytes = response.byteString("id")
            ?: throw AceLiveDhtProtocolException("KRPC response is missing node id")
        if (remoteIdBytes.size != AceLiveDhtNodeId.BYTES) {
            throw AceLiveDhtProtocolException("KRPC node id must be exactly 20 bytes")
        }

        return AceLiveDhtGetPeersResponse(
            remoteNodeId = AceLiveDhtNodeId.fromBytes(remoteIdBytes),
            peers = parseCompactPeers(response.values["values"], maxPeers),
            nodes = parseCompactNodes(response.values["nodes"], maxNodes)
        )
    }

    private fun parseCompactPeers(value: BValue?, maxPeers: Int): List<AceLiveTcpPeerEndpoint> {
        if (value == null) return emptyList()
        val list = value as? BValue.ListValue
            ?: throw AceLiveDhtProtocolException("KRPC values must be a list")
        val peers = ArrayList<AceLiveTcpPeerEndpoint>(minOf(list.values.size, maxPeers))
        for (entry in list.values) {
            if (peers.size >= maxPeers) break
            val compact = (entry as? BValue.Bytes)?.value
                ?: throw AceLiveDhtProtocolException("KRPC peer value must be a byte string")
            if (compact.size != COMPACT_PEER_BYTES) {
                throw AceLiveDhtProtocolException("Compact IPv4 peer must be exactly 6 bytes")
            }
            compactPeer(compact, 0)?.let(peers::add)
        }
        return peers
    }

    private fun parseCompactNodes(value: BValue?, maxNodes: Int): List<AceLiveDhtNodeContact> {
        if (value == null) return emptyList()
        val compact = (value as? BValue.Bytes)?.value
            ?: throw AceLiveDhtProtocolException("KRPC nodes must be a byte string")
        if (compact.size % COMPACT_NODE_BYTES != 0) {
            throw AceLiveDhtProtocolException("Compact IPv4 node list length must be a multiple of 26")
        }
        val count = minOf(compact.size / COMPACT_NODE_BYTES, maxNodes)
        val nodes = ArrayList<AceLiveDhtNodeContact>(count)
        repeat(count) { index ->
            val offset = index * COMPACT_NODE_BYTES
            val nodeId = AceLiveDhtNodeId.fromBytes(compact.copyOfRange(offset, offset + 20))
            val endpoint = compactPeer(compact, offset + 20) ?: return@repeat
            nodes += AceLiveDhtNodeContact(nodeId = nodeId, endpoint = endpoint)
        }
        return nodes
    }

    private fun compactPeer(bytes: ByteArray, offset: Int): AceLiveTcpPeerEndpoint? {
        val portOffset = offset + 4
        val port = ((bytes[portOffset].toInt() and 0xff) shl 8) or
            (bytes[portOffset + 1].toInt() and 0xff)
        if (port == 0) return null
        val host = buildString {
            repeat(4) { index ->
                if (index > 0) append('.')
                append(bytes[offset + index].toInt() and 0xff)
            }
        }
        return AceLiveTcpPeerEndpoint(host = host, port = port)
    }

    private fun validateTransactionId(transactionId: ByteArray) {
        require(transactionId.isNotEmpty() && transactionId.size <= MAX_TRANSACTION_ID_BYTES) {
            "transactionId must contain 1..$MAX_TRANSACTION_ID_BYTES bytes"
        }
    }

    private sealed interface BValue {
        data class Bytes(val value: ByteArray) : BValue
        data class Integer(val value: Long) : BValue
        data class ListValue(val values: List<BValue>) : BValue
        data class Dict(val values: Map<String, BValue>) : BValue {
            fun byteString(key: String): ByteArray? = (values[key] as? Bytes)?.value
        }
    }

    private class BencodeParser(private val bytes: ByteArray) {
        private var offset = 0
        private var parsedNodes = 0

        fun parseRoot(): BValue {
            val value = parseValue(depth = 0)
            if (offset != bytes.size) {
                throw AceLiveDhtProtocolException("Trailing bytes after KRPC bencode value")
            }
            return value
        }

        private fun parseValue(depth: Int): BValue {
            if (depth > MAX_BENCODE_DEPTH) {
                throw AceLiveDhtProtocolException("Bencode nesting exceeds local depth cap")
            }
            parsedNodes += 1
            if (parsedNodes > MAX_BENCODE_NODES) {
                throw AceLiveDhtProtocolException("Bencode node count exceeds local cap")
            }
            if (offset >= bytes.size) throw AceLiveDhtProtocolException("Unexpected end of bencode")
            return when (val marker = bytes[offset].toInt().toChar()) {
                'd' -> parseDict(depth)
                'l' -> parseList(depth)
                'i' -> parseInteger()
                in '0'..'9' -> BValue.Bytes(parseByteString())
                else -> throw AceLiveDhtProtocolException("Invalid bencode marker: $marker")
            }
        }

        private fun parseDict(depth: Int): BValue.Dict {
            offset += 1
            val values = LinkedHashMap<String, BValue>()
            var entries = 0
            while (true) {
                if (offset >= bytes.size) throw AceLiveDhtProtocolException("Unterminated bencode dictionary")
                if (bytes[offset].toInt().toChar() == 'e') {
                    offset += 1
                    return BValue.Dict(values)
                }
                entries += 1
                if (entries > MAX_CONTAINER_ENTRIES) {
                    throw AceLiveDhtProtocolException("Bencode dictionary exceeds local entry cap")
                }
                val key = parseByteString().asciiOrNull()
                    ?: throw AceLiveDhtProtocolException("Bencode dictionary key must be ASCII")
                if (values.containsKey(key)) {
                    throw AceLiveDhtProtocolException("Duplicate bencode dictionary key")
                }
                values[key] = parseValue(depth + 1)
            }
        }

        private fun parseList(depth: Int): BValue.ListValue {
            offset += 1
            val values = ArrayList<BValue>()
            while (true) {
                if (offset >= bytes.size) throw AceLiveDhtProtocolException("Unterminated bencode list")
                if (bytes[offset].toInt().toChar() == 'e') {
                    offset += 1
                    return BValue.ListValue(values)
                }
                if (values.size >= MAX_CONTAINER_ENTRIES) {
                    throw AceLiveDhtProtocolException("Bencode list exceeds local entry cap")
                }
                values += parseValue(depth + 1)
            }
        }

        private fun parseInteger(): BValue.Integer {
            offset += 1
            val end = bytes.indexOfByte('e'.code.toByte(), offset)
            if (end < 0) throw AceLiveDhtProtocolException("Unterminated bencode integer")
            val raw = bytes.copyOfRange(offset, end).asciiOrNull()
                ?: throw AceLiveDhtProtocolException("Invalid bencode integer")
            if (raw.isEmpty() || raw == "-0" || (raw.startsWith('0') && raw.length > 1) ||
                raw.startsWith("-0")
            ) {
                throw AceLiveDhtProtocolException("Non-canonical bencode integer")
            }
            val value = raw.toLongOrNull()
                ?: throw AceLiveDhtProtocolException("Bencode integer is out of range")
            offset = end + 1
            return BValue.Integer(value)
        }

        private fun parseByteString(): ByteArray {
            val colon = bytes.indexOfByte(':'.code.toByte(), offset)
            if (colon < 0) throw AceLiveDhtProtocolException("Missing bencode byte-string colon")
            val lengthText = bytes.copyOfRange(offset, colon).asciiOrNull()
                ?: throw AceLiveDhtProtocolException("Invalid bencode byte-string length")
            if (lengthText.isEmpty() || (lengthText.startsWith('0') && lengthText.length > 1)) {
                throw AceLiveDhtProtocolException("Non-canonical bencode byte-string length")
            }
            val length = lengthText.toIntOrNull()
                ?: throw AceLiveDhtProtocolException("Bencode byte-string length is out of range")
            if (length < 0 || length > MAX_STRING_BYTES) {
                throw AceLiveDhtProtocolException("Bencode byte string exceeds local cap")
            }
            val start = colon + 1
            val end = start + length
            if (end < start || end > bytes.size) {
                throw AceLiveDhtProtocolException("Truncated bencode byte string")
            }
            offset = end
            return bytes.copyOfRange(start, end)
        }
    }

    private fun ByteArray.ascii(): String = String(this, StandardCharsets.US_ASCII)

    private fun ByteArray.asciiOrNull(): String? {
        if (any { (it.toInt() and 0xff) > 0x7f }) return null
        return ascii()
    }

    private fun ByteArray.indexOfByte(value: Byte, start: Int): Int {
        for (index in start until size) {
            if (this[index] == value) return index
        }
        return -1
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}
