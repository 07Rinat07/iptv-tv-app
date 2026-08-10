package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * BEP-5 `get_peers` query encoder for an Ace Content ID lookup target.
 *
 * The DHT wire field is named `info_hash` by BEP-5, but the value carried here remains an
 * [AceContentIdDhtKey]. This codec deliberately does not construct [AceLiveSwarmKey], a BitTorrent
 * magnet or a normal BT infohash from the Content ID.
 */
object AceContentIdDhtCodec {
    fun encodeGetPeersQuery(
        transactionId: ByteArray,
        nodeId: AceLiveDhtNodeId,
        contentId: AceContentIdDhtKey
    ): ByteArray {
        require(transactionId.isNotEmpty() &&
            transactionId.size <= AceLiveDhtCodec.MAX_TRANSACTION_ID_BYTES
        ) {
            "transactionId must contain 1..${AceLiveDhtCodec.MAX_TRANSACTION_ID_BYTES} bytes"
        }

        return ByteArrayOutputStream(128).apply {
            writeAscii("d1:ad2:id20:")
            write(nodeId.toByteArray())
            writeAscii("9:info_hash20:")
            write(contentId.toByteArray())
            writeAscii("e1:q9:get_peers2:roi1e1:t")
            writeAscii(transactionId.size.toString())
            write(':'.code)
            write(transactionId)
            writeAscii("1:y1:qe")
        }.toByteArray()
    }

    fun decodeGetPeersResponse(
        bytes: ByteArray,
        expectedTransactionId: ByteArray,
        maxPeers: Int = AceLiveDhtCodec.DEFAULT_MAX_PEERS,
        maxNodes: Int = AceLiveDhtCodec.DEFAULT_MAX_NODES,
        maxPacketBytes: Int = AceLiveDhtCodec.DEFAULT_MAX_PACKET_BYTES
    ): AceLiveDhtGetPeersResponse = AceLiveDhtCodec.decodeGetPeersResponse(
        bytes = bytes,
        expectedTransactionId = expectedTransactionId,
        maxPeers = maxPeers,
        maxNodes = maxNodes,
        maxPacketBytes = maxPacketBytes
    )

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}
