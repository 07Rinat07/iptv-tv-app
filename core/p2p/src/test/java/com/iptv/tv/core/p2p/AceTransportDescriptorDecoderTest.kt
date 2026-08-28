package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceTransportDescriptorDecoderTest {
    @Test
    fun decodesEncryptedLiveDescriptorAndDerivesSwarmKey() {
        val descriptor = AceBencodeValue.Dictionary(
            mapOf(
                "authmethod" to bytes("RSA"),
                "bitrate" to AceBencodeValue.Integer(8_000_000),
                "chunk_length" to AceBencodeValue.Integer(16_384),
                "name" to bytes("Test Channel"),
                "piece_length" to AceBencodeValue.Integer(1_048_576),
                "pubkey" to AceBencodeValue.Bytes(ByteArray(124) { index -> (index + 1).toByte() }),
                "trackers" to AceBencodeValue.ListValue(
                    listOf(
                        bytes("udp://t1.torrentstream.org:2710/announce"),
                        bytes("https://tracker.example/announce")
                    )
                ),
                "metatrackers" to AceBencodeValue.ListValue(
                    listOf(bytes("https://meta.example/discovery"))
                ),
                "startup_nodes" to AceBencodeValue.ListValue(
                    listOf(bytes("8.8.8.8:8621"))
                )
            )
        )
        val transport = encryptTransport(AceBencodeEncoder.encode(descriptor))

        val result = AceTransportDescriptorDecoder.decodeLive(transport)

        assertTrue(result is P2pResult.Success)
        val live = (result as P2pResult.Success).data
        assertEquals("Test Channel", live.name)
        assertEquals(1_048_576, live.geometry.pieceLengthBytes)
        assertEquals(16_384, live.geometry.chunkLengthBytes)
        assertEquals(8_000_000, live.geometry.bitrate)
        assertArrayEquals(ByteArray(124) { index -> (index + 1).toByte() }, live.publicKeyDer)
        assertEquals(
            listOf(
                "ace-startup:8.8.8.8:8621",
                "ace-metatracker:https://meta.example/discovery",
                "udp://t1.torrentstream.org:2710/announce",
                "https://tracker.example/announce"
            ),
            live.trackers
        )
        assertEquals(40, live.swarmKey.toHex().length)
    }

    @Test
    fun acceptsScalarLegacyDiscoveryFieldsAndDropsMalformedStartupNodes() {
        val descriptor = AceBencodeValue.Dictionary(
            mapOf(
                "authmethod" to bytes("RSA"),
                "bitrate" to AceBencodeValue.Integer(1_000_000),
                "chunk_length" to AceBencodeValue.Integer(16_384),
                "name" to bytes("Legacy Fields"),
                "piece_length" to AceBencodeValue.Integer(524_288),
                "pubkey" to AceBencodeValue.Bytes(ByteArray(124) { 7 }),
                "tracker" to bytes("http://tracker.example:8630/announce"),
                "metatracker" to bytes("http://meta.example/list"),
                "startup-node" to bytes("not-an-endpoint")
            )
        )

        val result = AceTransportDescriptorDecoder.decodeLive(
            encryptTransport(AceBencodeEncoder.encode(descriptor))
        )

        assertTrue(result is P2pResult.Success)
        val live = (result as P2pResult.Success).data
        assertEquals(
            listOf(
                "ace-metatracker:http://meta.example/list",
                "http://tracker.example:8630/announce"
            ),
            live.trackers
        )
    }

    @Test
    fun rejectsDescriptorGeometryThatCouldOverAllocate() {
        val descriptor = AceBencodeValue.Dictionary(
            mapOf(
                "authmethod" to bytes("RSA"),
                "bitrate" to AceBencodeValue.Integer(1),
                "chunk_length" to AceBencodeValue.Integer(16_384),
                "name" to bytes("Oversized"),
                "piece_length" to AceBencodeValue.Integer(16L * 1024L * 1024L),
                "pubkey" to AceBencodeValue.Bytes(ByteArray(124) { 1 })
            )
        )

        val result = AceTransportDescriptorDecoder.decodeLive(
            encryptTransport(AceBencodeEncoder.encode(descriptor))
        )

        assertTrue(result is P2pResult.Error)
    }

    private fun bytes(value: String) =
        AceBencodeValue.Bytes(value.toByteArray(StandardCharsets.UTF_8))

    private fun encryptTransport(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(TRANSPORT_KEY, "AES"),
            IvParameterSpec(TRANSPORT_IV)
        )
        return "AceStreamTransport".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0, 2) +
            cipher.doFinal(plaintext)
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
