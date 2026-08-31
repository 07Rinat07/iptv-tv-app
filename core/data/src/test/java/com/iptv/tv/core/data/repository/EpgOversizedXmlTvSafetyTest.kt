package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgOversizedXmlTvSafetyTest {
    @Test
    fun productionDecodedScanBudgetExceedsFormer256MiBBoundary() {
        val formerBoundary = 256L * 1024L * 1024L

        assertTrue(EpgInputSafetyPolicy.MAX_DECODED_XMLTV_BYTES > formerBoundary)
        assertEquals(128L * 1024L * 1024L, EpgInputSafetyPolicy.MAX_INPUT_BYTES)
        assertTrue(EpgInputSafetyPolicy.MAX_GZIP_EXPANSION_RATIO > 1L)
    }

    @Test
    fun gzipXmlTvCanStreamPastFormerAggregateBoundaryWhenExpansionIsSane() {
        val formerScaledBoundary = 32L * 1024L
        val xml = xmlTvWithPseudoRandomDisplayName(chars = 48 * 1024)
        val compressed = gzip(xml)

        val decoded = EpgBoundedInputStream(
            input = ByteArrayInputStream(compressed),
            maxBytes = 256L * 1024L,
            maxDecodedBytes = 64L * 1024L,
            maxExpansionRatio = 64L
        ).use { it.readBytes() }

        assertTrue(decoded.size.toLong() > formerScaledBoundary)
        assertEquals(xml.toList(), decoded.toList())
    }

    @Test
    fun gzipExpansionBombFailsOnRatioBeforeAbsoluteDecodedBudget() {
        val xml = (
            "<tv><channel id=\"bomb\"><display-name>" +
                "A".repeat(48 * 1024) +
                "</display-name></channel></tv>"
            ).toByteArray(Charsets.UTF_8)
        val compressed = gzip(xml)

        val error = assertThrows(EpgExpansionLimitExceededException::class.java) {
            EpgBoundedInputStream(
                input = ByteArrayInputStream(compressed),
                maxBytes = 64L * 1024L,
                maxDecodedBytes = 1024L * 1024L,
                maxExpansionRatio = 2L
            ).use { it.readBytes() }
        }

        assertEquals(2L, error.maxExpansionRatio)
        assertTrue(error.decodedBytes < 1024L * 1024L)
        assertEquals(EpgFailureKind.MALFORMED, classifyEpgFailure(error))
    }

    @Test
    fun absoluteDecodedScanBudgetStillFailsClosed() {
        val xml = xmlTvWithPseudoRandomDisplayName(chars = 48 * 1024)
        val compressed = gzip(xml)

        val error = assertThrows(EpgInputLimitExceededException::class.java) {
            EpgBoundedInputStream(
                input = ByteArrayInputStream(compressed),
                maxBytes = 256L * 1024L,
                maxDecodedBytes = 16L * 1024L,
                maxExpansionRatio = 64L
            ).use { it.readBytes() }
        }

        assertEquals(16L * 1024L, error.maxBytes)
    }

    private fun xmlTvWithPseudoRandomDisplayName(chars: Int): ByteArray {
        val text = buildString(chars + 96) {
            append("<tv><channel id=\"field\"><display-name>")
            var state = 0x13579BDF
            repeat(chars) {
                state = state * 1_103_515_245 + 12_345
                append(('A'.code + ((state ushr 16) and 25)).toChar())
            }
            append("</display-name></channel></tv>")
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(input) }
        return output.toByteArray()
    }
}
