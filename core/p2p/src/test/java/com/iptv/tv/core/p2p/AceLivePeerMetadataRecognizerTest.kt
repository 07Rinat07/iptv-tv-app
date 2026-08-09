package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerMetadataRecognizerTest {
    private val recognizer = AceLivePeerMetadataRecognizer()

    @Test
    fun recognizesNestedMiWindowByContent() {
        val payload = ascii(
            "d2:mid20:distance_from_sourcei3e9:max_piecei120e9:min_piecei100e8:positioni118eee"
        )

        val result = recognizer.recognize(payload) as AceLivePeerMetadataRecognition.Recognized

        assertEquals(100L, result.window.minPiece)
        assertEquals(120L, result.window.maxPiece)
        assertEquals(118L, result.window.position)
        assertEquals(3L, result.window.distanceFromSource)
        assertTrue(result.window.minPieceExplicit)
    }

    @Test
    fun recognizesOneByteExtendedEnvelopeWithoutUsingMessageId() {
        val payload = byteArrayOf(0) + ascii("d2:mid9:max_piecei42eee")

        val result = recognizer.recognize(payload) as AceLivePeerMetadataRecognition.Recognized

        assertEquals(42L, result.window.minPiece)
        assertEquals(42L, result.window.maxPiece)
        assertFalse(result.window.minPieceExplicit)
    }

    @Test
    fun missingMinPieceUsesOnlyAdvertisedHeadConservatively() {
        val result = recognizer.recognize(ascii("d9:max_piecei77ee")) as
            AceLivePeerMetadataRecognition.Recognized

        assertEquals(77L, result.window.minPiece)
        assertEquals(77L, result.window.maxPiece)
    }

    @Test
    fun unrelatedBencodeDictionaryIsNotMisclassified() {
        assertEquals(
            AceLivePeerMetadataRecognition.NotRecognized,
            recognizer.recognize(ascii("d3:fooi1ee"))
        )
    }

    @Test
    fun malformedCandidateIsRejectedWithoutThrowing() {
        assertEquals(
            AceLivePeerMetadataRecognition.Rejected(
                AceLivePeerMetadataRejectReason.MALFORMED_BENCODE
            ),
            recognizer.recognize(ascii("d9:max_piecei42e"))
        )
    }

    @Test
    fun invalidWindowIsRejected() {
        assertEquals(
            AceLivePeerMetadataRecognition.Rejected(
                AceLivePeerMetadataRejectReason.INVALID_WINDOW
            ),
            recognizer.recognize(ascii("d9:max_piecei10e9:min_piecei11ee"))
        )
    }

    @Test
    fun invalidOptionalPositionAndDistanceAreRejected() {
        val invalidPayloads = listOf(
            "d9:max_piecei10e8:positioni-1ee",
            "d9:max_piecei10e8:positioni4294967296ee",
            "d20:distance_from_sourcei-1e9:max_piecei10ee",
            "d20:distance_from_sourcei4294967296e9:max_piecei10ee"
        )

        invalidPayloads.forEach { payload ->
            assertEquals(
                AceLivePeerMetadataRecognition.Rejected(
                    AceLivePeerMetadataRejectReason.INVALID_WINDOW
                ),
                recognizer.recognize(ascii(payload))
            )
        }
    }

    @Test
    fun payloadLimitIsEnforcedBeforeParsing() {
        val bounded = AceLivePeerMetadataRecognizer(maxPayloadBytes = 8)

        assertEquals(
            AceLivePeerMetadataRecognition.Rejected(
                AceLivePeerMetadataRejectReason.PAYLOAD_TOO_LARGE
            ),
            bounded.recognize(ascii("d9:max_piecei42ee"))
        )
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)
}
