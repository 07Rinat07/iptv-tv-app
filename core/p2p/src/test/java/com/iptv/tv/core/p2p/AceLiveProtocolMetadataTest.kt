package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveProtocolMetadataTest {
    @Test
    fun geometry_derivesChunkCountWithoutAssumingExactDivisibility() {
        val geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 65_537,
            chunkLengthBytes = 16_384,
            bitrate = 8_375
        )

        assertEquals(5, geometry.chunksPerPiece)
    }

    @Test(expected = IllegalArgumentException::class)
    fun geometry_rejectsNonPositivePieceLength() {
        AceLiveTransportGeometry(
            pieceLengthBytes = 0,
            chunkLengthBytes = 16_384,
            bitrate = 8_375
        )
    }

    @Test
    fun pieceHeader_decodesKnownBigEndianF64TimestampVector() {
        val header = hex("41da91522634c2ee")

        val seconds = AceLivePieceHeaderCodec.decodeUnixSeconds(header)

        assertTrue(seconds != null)
        assertEquals(1_782_925_464.8243976, seconds!!, 0.000001)
    }

    @Test
    fun pieceHeader_roundTripsTimestampAsExactlyEightBytes() {
        val seconds = 1_782_925_514.0239532

        val encoded = AceLivePieceHeaderCodec.encodeUnixSeconds(seconds)
        val decoded = AceLivePieceHeaderCodec.decodeUnixSeconds(encoded)

        assertEquals(AceLivePieceHeaderCodec.HEADER_SIZE_BYTES, encoded.size)
        assertEquals(seconds, decoded!!, 0.0)
        assertArrayEquals(encoded, AceLivePieceHeaderCodec.encodeUnixSeconds(decoded))
    }

    @Test
    fun pieceHeader_rejectsWrongSizeAndNonFiniteValues() {
        assertNull(AceLivePieceHeaderCodec.decodeUnixSeconds(ByteArray(7)))
        assertNull(AceLivePieceHeaderCodec.decodeUnixSeconds(hex("7ff8000000000000")))
    }

    @Test
    fun metadataDiagnostics_doNotExposeTrackerOrPublicKeyValues() {
        val metadata = AceLiveTransportMetadata(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 1_048_576,
                chunkLengthBytes = 16_384,
                bitrate = 902_408
            ),
            authMethod = "RSA",
            publicKeyDerBase64 = "VERY_SECRET_LOOKING_BUT_PUBLIC_KEY_BYTES",
            trackers = listOf(
                "udp://tracker.example:9006/announce",
                "udp://tracker2.example:1337/announce"
            ),
            allowPublicTrackers = true,
            permanent = true
        )

        val summary = metadata.diagnosticSummary()

        assertTrue(summary.contains("piece_bytes=1048576"))
        assertTrue(summary.contains("chunk_bytes=16384"))
        assertTrue(summary.contains("chunks_per_piece=64"))
        assertTrue(summary.contains("bitrate=902408"))
        assertTrue(summary.contains("auth=RSA"))
        assertTrue(summary.contains("pubkey=present"))
        assertTrue(summary.contains("trackers=2"))
        assertTrue(!summary.contains("tracker.example"))
        assertTrue(!summary.contains("VERY_SECRET_LOOKING"))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
