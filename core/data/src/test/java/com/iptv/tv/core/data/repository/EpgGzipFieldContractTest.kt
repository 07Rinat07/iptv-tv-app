package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgGzipFieldContractTest {
    @Test
    fun rawGzipXmlTvWithBomAndDeclarationIsDecodedWithoutLosingPrefix() {
        val xml = (
            "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<tv><channel id=\"history\"><display-name>History HD</display-name></channel></tv>"
            ).toByteArray(Charsets.UTF_8)
        val gzip = gzip(xml)

        val decoded = EpgBoundedInputStream(
            input = ByteArrayInputStream(gzip),
            maxBytes = EpgInputSafetyPolicy.MAX_INPUT_BYTES
        ).use { it.readBytes() }

        assertArrayEquals(xml, decoded)
    }

    @Test
    fun compressedSourceEnvelopeIsStillEnforcedBeforeDecodedEnvelope() {
        val xml = buildString {
            append("<tv><channel id=\"field\"><display-name>")
            var state = 0x12345678
            repeat(50_000) {
                state = state * 1_103_515_245 + 12_345
                append(('A'.code + ((state ushr 16) and 25)).toChar())
            }
            append("</display-name></channel></tv>")
        }.toByteArray(Charsets.UTF_8)
        val gzip = gzip(xml)
        val rawLimit = EpgSourceFormatPolicy.PREFIX_BYTES.toLong()
        assertTrue("fixture must exceed the raw source limit", gzip.size > rawLimit)

        val error = assertThrows(EpgInputLimitExceededException::class.java) {
            EpgBoundedInputStream(
                input = ByteArrayInputStream(gzip),
                maxBytes = rawLimit,
                maxDecodedBytes = EpgInputSafetyPolicy.MAX_DECODED_XMLTV_BYTES
            ).use { it.readBytes() }
        }

        assertTrue(error.maxBytes == rawLimit)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(input) }
        return output.toByteArray()
    }
}
