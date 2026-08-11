package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerWireCodecTest {
    private val codec = AceLivePeerWireCodec()

    @Test
    fun chunkRequestEncodesCompleteVerifiedPeerFrame() {
        val request = AceLiveChunkRequest(
            peerId = 7,
            piece = 0x005067f8,
            chunkIndex = 3,
            beginBytes = 49_152,
            expectedPayloadBytes = 16_384
        )

        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 11,
                6,
                0, 0, 0, 0,
                0, 0x50, 0x67, 0xf8.toByte(),
                0, 3
            ),
            codec.encodeChunkRequestFrame(request)
        )
    }

    @Test
    fun keepAliveDecodesFromZeroLengthPrefix() {
        val result = codec.decodeNext(byteArrayOf(0, 0, 0, 0))

        val decoded = result as AceLivePeerFrameDecodeResult.Decoded
        assertEquals(AceLivePeerWireMessage.KeepAlive, decoded.message)
        assertEquals(4, decoded.consumedBytes)
    }

    @Test
    fun chokeAndUnchokeRequireEmptyPayload() {
        val choke = codec.decodeNext(frame(id = 0)) as AceLivePeerFrameDecodeResult.Decoded
        val unchoke = codec.decodeNext(frame(id = 1)) as AceLivePeerFrameDecodeResult.Decoded
        val malformedChoke = codec.decodeNext(frame(id = 0, payload = byteArrayOf(1))) as
            AceLivePeerFrameDecodeResult.Decoded

        assertEquals(AceLivePeerWireMessage.Choke, choke.message)
        assertEquals(AceLivePeerWireMessage.Unchoke, unchoke.message)
        assertTrue(malformedChoke.message is AceLivePeerWireMessage.Unknown)
    }

    @Test
    fun haveDecodesUnsignedLivePiece() {
        val decoded = codec.decodeNext(
            frame(id = 4, payload = byteArrayOf(0xf1.toByte(), 0x23, 0x45, 0x67))
        ) as AceLivePeerFrameDecodeResult.Decoded

        assertEquals(0xf1234567L, (decoded.message as AceLivePeerWireMessage.Have).piece)
    }

    @Test
    fun streamHaveDecodesStreamAndUnsignedLivePiece() {
        val decoded = codec.decodeNext(
            frame(
                id = 10,
                payload = byteArrayOf(
                    0, 0, 0, 2,
                    0xf1.toByte(), 0x23, 0x45, 0x67
                )
            )
        ) as AceLivePeerFrameDecodeResult.Decoded
        val have = decoded.message as AceLivePeerWireMessage.StreamHave

        assertEquals(2L, have.streamIndex)
        assertEquals(0xf1234567L, have.piece)
    }

    @Test
    fun extendedHaveAlsoDecodesStreamAndLivePiece() {
        val decoded = codec.decodeNext(
            frame(
                id = 4,
                payload = byteArrayOf(0, 0, 0, 0, 0x08, 0x54, 0x5a, 0xd5.toByte())
            )
        ) as AceLivePeerFrameDecodeResult.Decoded
        val have = decoded.message as AceLivePeerWireMessage.StreamHave

        assertEquals(0L, have.streamIndex)
        assertEquals(139_746_005L, have.piece)
    }

    @Test
    fun compactLiveStatusDecodesAvailabilityWindow() {
        val payload = ascii(
            "d1:ai1e1:bi0e1:ci42095e1:di0e1:ei4931992e1:fi0e" +
                "1:gi149337677e1:hi1029e1:ii149337257e1:ji149337676e" +
                "1:ki0e1:li6735e1:mi-1e1:ni2e1:oi0e1:pi0e1:qi1e" +
                "1:ri149337677e1:si149337677e1:ti-1e1:ui1ee"
        )

        val decoded = codec.decodeNext(frame(id = 11, payload = payload)) as
            AceLivePeerFrameDecodeResult.Decoded
        val status = decoded.message as AceLivePeerWireMessage.LiveStatus

        assertEquals(149_337_257L, status.minPiece)
        assertEquals(149_337_676L, status.maxPiece)
        assertEquals(149_337_677L, status.position)
    }

    @Test
    fun unrelatedStatusIdPayloadRemainsUnknown() {
        val decoded = codec.decodeNext(
            frame(id = 11, payload = ascii("d1:ii10e1:ji12ee"))
        ) as AceLivePeerFrameDecodeResult.Decoded

        assertTrue(decoded.message is AceLivePeerWireMessage.Unknown)
    }

    @Test
    fun livePieceDecodesAceCoordinatesHeaderAndData() {
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1_782_925_464.8243976)
        val payload = byteArrayOf(
            0, 0, 0, 0,
            0, 0x50, 0x67, 0xf8.toByte()
        ) + header + byteArrayOf(0, 7) + byteArrayOf(1, 2, 3, 4)

        val result = codec.decodeNext(frame(id = 7, payload = payload)) as
            AceLivePeerFrameDecodeResult.Decoded
        val chunk = result.message as AceLivePeerWireMessage.LiveChunk

        assertEquals(0L, chunk.streamIndex)
        assertEquals(0x005067f8L, chunk.piece)
        assertArrayEquals(header, chunk.pieceHeader)
        assertEquals(7, chunk.chunkIndex)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), chunk.data)
        assertEquals(4 + 1 + payload.size, result.consumedBytes)
    }

    @Test
    fun livePiecePreservesUnsignedU32PieceNumber() {
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)
        val payload = byteArrayOf(
            0, 0, 0, 0,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()
        ) + header + byteArrayOf(0, 0) + byteArrayOf(1)

        val decoded = codec.decodeNext(frame(id = 7, payload = payload)) as
            AceLivePeerFrameDecodeResult.Decoded
        val chunk = decoded.message as AceLivePeerWireMessage.LiveChunk

        assertEquals(0xffff_ffffL, chunk.piece)
    }

    @Test
    fun tooShortLivePieceIsPreservedAsUnknown() {
        val result = codec.decodeNext(frame(id = 7, payload = ByteArray(17))) as
            AceLivePeerFrameDecodeResult.Decoded

        val unknown = result.message as AceLivePeerWireMessage.Unknown
        assertEquals(7, unknown.id)
        assertEquals(17, unknown.payload.size)
    }

    @Test
    fun unknownVendorMessageIsPreservedWithoutDisconnectSemantics() {
        val result = codec.decodeNext(frame(id = 99, payload = byteArrayOf(4, 5, 6))) as
            AceLivePeerFrameDecodeResult.Decoded
        val unknown = result.message as AceLivePeerWireMessage.Unknown

        assertEquals(99, unknown.id)
        assertArrayEquals(byteArrayOf(4, 5, 6), unknown.payload)
    }

    @Test
    fun partialPrefixAndBodyReportRequiredTotalBytes() {
        assertEquals(
            AceLivePeerFrameDecodeResult.NeedMoreData(4),
            codec.decodeNext(byteArrayOf(0, 0))
        )
        assertEquals(
            AceLivePeerFrameDecodeResult.NeedMoreData(9),
            codec.decodeNext(byteArrayOf(0, 0, 0, 5, 7, 0))
        )
    }

    @Test
    fun hostileOversizedLengthIsRejectedFromPrefixAlone() {
        val tooLarge = AceLivePeerWireCodec.DEFAULT_MAX_FRAME_LENGTH_BYTES + 1
        val prefix = byteArrayOf(
            (tooLarge ushr 24).toByte(),
            (tooLarge ushr 16).toByte(),
            (tooLarge ushr 8).toByte(),
            tooLarge.toByte()
        )

        assertEquals(
            AceLivePeerFrameDecodeResult.Rejected(AceLivePeerFrameRejectReason.FRAME_TOO_LARGE),
            codec.decodeNext(prefix)
        )
    }

    @Test
    fun decoderConsumesOnlyFirstFrameFromCombinedBuffer() {
        val first = frame(id = 1)
        val second = frame(id = 0)
        val combined = first + second

        val decoded = codec.decodeNext(combined) as AceLivePeerFrameDecodeResult.Decoded

        assertEquals(AceLivePeerWireMessage.Unchoke, decoded.message)
        assertEquals(first.size, decoded.consumedBytes)
    }

    private fun frame(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val bodyLength = 1 + payload.size
        return byteArrayOf(
            (bodyLength ushr 24).toByte(),
            (bodyLength ushr 16).toByte(),
            (bodyLength ushr 8).toByte(),
            bodyLength.toByte(),
            id.toByte()
        ) + payload
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)
}
