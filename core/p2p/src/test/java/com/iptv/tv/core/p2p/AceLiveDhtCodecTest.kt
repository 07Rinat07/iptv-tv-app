package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AceLiveDhtCodecTest {
    @Test
    fun `get peers query uses bounded canonical KRPC layout`() {
        val transactionId = "xy".toByteArray(StandardCharsets.US_ASCII)
        val query = AceLiveDhtCodec.encodeGetPeersQuery(
            transactionId = transactionId,
            nodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 'A'.code.toByte() }),
            swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 'B'.code.toByte() })
        )

        assertEquals(
            "d1:ad2:id20:AAAAAAAAAAAAAAAAAAAA9:info_hash20:BBBBBBBBBBBBBBBBBBBBee" +
                "1:q9:get_peers1:t2:xy1:y1:qe",
            String(query, StandardCharsets.US_ASCII)
        )
    }

    @Test
    fun `response parses compact peers and nodes`() {
        val transactionId = byteArrayOf(0x12, 0x34)
        val remoteId = ByteArray(20) { 1 }
        val nextId = ByteArray(20) { 2 }
        val peer = compactEndpoint(8, 8, 8, 8, 8621)
        val nodes = nextId + compactEndpoint(1, 1, 1, 1, 6881)

        val decoded = AceLiveDhtCodec.decodeGetPeersResponse(
            bytes = response(transactionId, remoteId, values = listOf(peer), nodes = nodes),
            expectedTransactionId = transactionId
        )

        assertEquals(AceLiveDhtNodeId.fromBytes(remoteId), decoded.remoteNodeId)
        assertEquals(listOf(AceLiveTcpPeerEndpoint("8.8.8.8", 8621)), decoded.peers)
        assertEquals(1, decoded.nodes.size)
        assertEquals(AceLiveDhtNodeId.fromBytes(nextId), decoded.nodes.single().nodeId)
        assertEquals(AceLiveTcpPeerEndpoint("1.1.1.1", 6881), decoded.nodes.single().endpoint)
    }

    @Test
    fun `transaction mismatch is rejected`() {
        expectProtocolFailure {
            AceLiveDhtCodec.decodeGetPeersResponse(
                bytes = response(byteArrayOf(1, 2), ByteArray(20) { 3 }),
                expectedTransactionId = byteArrayOf(9, 9)
            )
        }
    }

    @Test
    fun `malformed compact node vector is rejected`() {
        expectProtocolFailure {
            AceLiveDhtCodec.decodeGetPeersResponse(
                bytes = response(
                    transactionId = byteArrayOf(1, 2),
                    remoteId = ByteArray(20) { 3 },
                    nodes = ByteArray(25)
                ),
                expectedTransactionId = byteArrayOf(1, 2)
            )
        }
    }

    @Test
    fun `peer and node result counts are capped`() {
        val transactionId = byteArrayOf(1, 2)
        val values = (0 until 4).map { compactEndpoint(8, 8, 4, it + 1, 8000 + it) }
        val nodes = ByteArrayOutputStream().apply {
            repeat(4) { index ->
                write(ByteArray(20) { (index + 1).toByte() })
                write(compactEndpoint(1, 1, 1, index + 1, 9000 + index))
            }
        }.toByteArray()

        val decoded = AceLiveDhtCodec.decodeGetPeersResponse(
            bytes = response(transactionId, ByteArray(20) { 7 }, values, nodes),
            expectedTransactionId = transactionId,
            maxPeers = 2,
            maxNodes = 2
        )

        assertEquals(2, decoded.peers.size)
        assertEquals(2, decoded.nodes.size)
    }

    @Test
    fun `trailing bencode bytes are rejected`() {
        val transactionId = byteArrayOf(1, 2)
        val valid = response(transactionId, ByteArray(20) { 4 })
        expectProtocolFailure {
            AceLiveDhtCodec.decodeGetPeersResponse(
                bytes = valid + byteArrayOf('x'.code.toByte()),
                expectedTransactionId = transactionId
            )
        }
    }

    @Test
    fun `node id owns defensive copy`() {
        val source = ByteArray(20) { it.toByte() }
        val nodeId = AceLiveDhtNodeId.fromBytes(source)
        source.fill(99)
        val copy = nodeId.toByteArray()
        copy.fill(88)

        assertArrayEquals(ByteArray(20) { it.toByte() }, nodeId.toByteArray())
        assertTrue(nodeId.toString().contains("redacted"))
    }

    private fun response(
        transactionId: ByteArray,
        remoteId: ByteArray,
        values: List<ByteArray> = emptyList(),
        nodes: ByteArray? = null
    ): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("d1:rd2:id20:")
        write(remoteId)
        if (nodes != null) {
            writeAscii("5:nodes${nodes.size}:")
            write(nodes)
        }
        if (values.isNotEmpty()) {
            writeAscii("6:valuesl")
            values.forEach { compact ->
                writeAscii("${compact.size}:")
                write(compact)
            }
            writeAscii("e")
        }
        writeAscii("e1:t${transactionId.size}:")
        write(transactionId)
        writeAscii("1:y1:re")
    }.toByteArray()

    private fun compactEndpoint(a: Int, b: Int, c: Int, d: Int, port: Int): ByteArray =
        byteArrayOf(
            a.toByte(), b.toByte(), c.toByte(), d.toByte(),
            (port ushr 8).toByte(), port.toByte()
        )

    private fun expectProtocolFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected AceLiveDhtProtocolException")
        } catch (_: AceLiveDhtProtocolException) {
            // Expected.
        }
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}
