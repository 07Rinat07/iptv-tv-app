package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgGzipSourcePolicyTest {
    @Test
    fun productionEnvelopeDecodesRawGzipXmlTvStreaming() {
        val xml = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
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
    fun gzipPayloadMustContainXmlTvAfterDecode() {
        val secretBody = "<!doctype html><html><body>token=do-not-log</body></html>"
        val gzip = gzip(secretBody.toByteArray(Charsets.UTF_8))

        val error = assertThrows(EpgMalformedXmlException::class.java) {
            EpgBoundedInputStream(
                input = ByteArrayInputStream(gzip),
                maxBytes = EpgInputSafetyPolicy.MAX_INPUT_BYTES
            )
        }

        assertTrue(error.message.orEmpty().contains("format=HTML"))
        assertFalse(error.message.orEmpty().contains("do-not-log"))
    }

    @Test
    fun decodedGzipExpansionRemainsBounded() {
        val xml = buildString {
            append("<tv>")
            repeat(4_000) { index ->
                append("<channel id=\"")
                append(index)
                append("\"><display-name>Channel ")
                append(index)
                append("</display-name></channel>")
            }
            append("</tv>")
        }.toByteArray(Charsets.UTF_8)
        val gzip = gzip(xml)
        val decodedLimit = 12L * 1024L

        val error = assertThrows(EpgInputLimitExceededException::class.java) {
            EpgBoundedInputStream(
                input = ByteArrayInputStream(gzip),
                maxBytes = EpgInputSafetyPolicy.MAX_INPUT_BYTES,
                maxDecodedBytes = decodedLimit
            ).use { it.readBytes() }
        }

        assertTrue(error.maxBytes == decodedLimit)
    }

    @Test
    fun productionSafetySeparatesSourceAndDecodedEnvelopes() {
        assertTrue(EpgInputSafetyPolicy.MAX_INPUT_BYTES == 128L * 1024L * 1024L)
        assertTrue(EpgInputSafetyPolicy.MAX_DECODED_XMLTV_BYTES == 256L * 1024L * 1024L)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(input) }
        return output.toByteArray()
    }
}
