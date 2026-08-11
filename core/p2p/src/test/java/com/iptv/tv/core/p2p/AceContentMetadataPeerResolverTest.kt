package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentMetadataPeerResolverTest {
    @Test
    fun fetchesTransportDescriptorThroughSignedUtMetadataSession() = runBlocking {
        val contentId = AceLiveSwarmKey.parseHex("50bc2f512793f1e745fb5bd5b5a6afca199c2d19")!!
        val localPeerId = AceLiveNodeIdentity.peerId()
        val remotePeerId = AceLiveNodeIdentity.peerId()
        val transportBytes = testTransport()
        val peerHandshake = AceLivePeerHandshakeCodec().encode(
            contentId.toByteArray(),
            remotePeerId
        )
        val extendedHandshake = frame(
            id = 20,
            payload = byteArrayOf(0) + AceBencodeEncoder.encode(
                AceBencodeValue.Dictionary(
                    mapOf(
                        "m" to AceBencodeValue.Dictionary(
                            mapOf("ut_metadata" to AceBencodeValue.Integer(5))
                        ),
                        "metadata_size" to AceBencodeValue.Integer(transportBytes.size.toLong())
                    )
                )
            )
        )
        val metadataData = frame(
            id = 20,
            payload = byteArrayOf(2) + AceBencodeEncoder.encode(
                AceBencodeValue.Dictionary(
                    mapOf(
                        "msg_type" to AceBencodeValue.Integer(1),
                        "piece" to AceBencodeValue.Integer(0),
                        "total_size" to AceBencodeValue.Integer(transportBytes.size.toLong())
                    )
                )
            ) + transportBytes
        )
        val fake = ScriptedTransport(peerHandshake + extendedHandshake + metadataData)
        val resolver = AceContentMetadataPeerResolver(
            transportFactory = AceLiveTcpTransportFactory { _, _ -> fake }
        )

        val result = resolver.fetchFromPeer(
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9000),
            contentId = contentId,
            peerId = localPeerId,
            identity = AceLiveNodeIdentity.generate()
        )

        assertTrue(result is P2pResult.Success)
        val resolved = (result as P2pResult.Success).data
        assertEquals("Metadata Test", resolved.name)
        assertEquals(1_048_576, resolved.geometry.pieceLengthBytes)
        assertEquals(3, fake.writes.size)
        assertEquals(20, fake.writes[1][4].toInt() and 0xff)
        assertEquals(20, fake.writes[2][4].toInt() and 0xff)
        assertEquals(5, fake.writes[2][5].toInt() and 0xff)
    }

    private fun testTransport(): ByteArray {
        val descriptor = AceBencodeValue.Dictionary(
            mapOf(
                "authmethod" to bytes("RSA"),
                "bitrate" to AceBencodeValue.Integer(4_000_000),
                "chunk_length" to AceBencodeValue.Integer(16_384),
                "name" to bytes("Metadata Test"),
                "piece_length" to AceBencodeValue.Integer(1_048_576),
                "pubkey" to AceBencodeValue.Bytes(ByteArray(124) { index -> (index + 1).toByte() }),
                "trackers" to AceBencodeValue.ListValue(
                    listOf(bytes("udp://t1.torrentstream.org:2710/announce"))
                )
            )
        )
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(TRANSPORT_KEY, "AES"),
            IvParameterSpec(TRANSPORT_IV)
        )
        return "AceStreamTransport".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0, 2) +
            cipher.doFinal(AceBencodeEncoder.encode(descriptor))
    }

    private fun frame(id: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(1 + payload.size)
            .put(id.toByte())
            .put(payload)
            .array()

    private fun bytes(value: String) =
        AceBencodeValue.Bytes(value.toByteArray(StandardCharsets.UTF_8))

    private class ScriptedTransport(private val input: ByteArray) : AceLiveTcpTransport {
        val writes = mutableListOf<ByteArray>()
        private var offset = 0

        override suspend fun read(buffer: ByteArray): Int {
            if (offset >= input.size) return -1
            val count = minOf(buffer.size, input.size - offset)
            input.copyInto(buffer, 0, offset, offset + count)
            offset += count
            return count
        }

        override suspend fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        override suspend fun close() = Unit
    }

    private companion object {
        val TRANSPORT_KEY = byteArrayOf(
            0xa5.toByte(), 0x0c, 0x4e, 0x33, 0xa2.toByte(), 0xf4.toByte(), 0x8c.toByte(), 0xc5.toByte(),
            0x0c, 0xe2.toByte(), 0x75, 0xc9.toByte(), 0xff.toByte(), 0x3a, 0x31, 0xbf.toByte()
        )
        val TRANSPORT_IV = byteArrayOf(
            0x74, 0xe9.toByte(), 0xcd.toByte(), 0xd6.toByte(), 0x39, 0x1b, 0xcb.toByte(), 0xd5.toByte(),
            0x65, 0xf9.toByte(), 0x95.toByte(), 0x03, 0x31, 0x33, 0x29, 0xa3.toByte()
        )
    }
}
