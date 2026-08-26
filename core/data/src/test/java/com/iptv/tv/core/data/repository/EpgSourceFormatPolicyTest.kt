package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSourceFormatPolicyTest {
    @Test
    fun classifiesCommonXmlTvPreambles() {
        val samples = listOf(
            "<tv></tv>",
            "  \n\t<tv generator-info-name=\"provider\"></tv>",
            "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv></tv>",
            "<?xml version=\"1.0\"?>\n<!-- guide -->\n<tv></tv>",
            "<?xml version=\"1.0\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv></tv>"
        )

        samples.forEach { sample ->
            assertEquals(
                sample,
                EpgSourceFormat.XMLTV,
                EpgSourceFormatPolicy.classify(sample.toByteArray(Charsets.UTF_8))
            )
        }
    }

    @Test
    fun classifiesNonXmlTvInputsFailClosed() {
        assertEquals(
            EpgSourceFormat.HTML,
            EpgSourceFormatPolicy.classify("<!doctype html><html>error</html>".toByteArray())
        )
        assertEquals(
            EpgSourceFormat.XML_OTHER,
            EpgSourceFormatPolicy.classify("<?xml version=\"1.0\"?><error>denied</error>".toByteArray())
        )
        assertEquals(
            EpgSourceFormat.TEXT_OTHER,
            EpgSourceFormatPolicy.classify("Access denied".toByteArray())
        )
        assertEquals(
            EpgSourceFormat.GZIP,
            EpgSourceFormatPolicy.classify(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00))
        )
        assertEquals(
            EpgSourceFormat.BINARY_OR_UNKNOWN,
            EpgSourceFormatPolicy.classify(byteArrayOf(0x00, 0x01, 0x02))
        )
        assertEquals(EpgSourceFormat.EMPTY, EpgSourceFormatPolicy.classify(byteArrayOf()))
    }

    @Test
    fun xmlTvLookingMalformedDocumentIsNotSilentlyRewritten() {
        val malformed = "<tv><channel id=\"x\"><display-name>A & B</display-name></channel></tv>"

        assertEquals(
            EpgSourceFormat.XMLTV,
            EpgSourceFormatPolicy.classify(malformed.toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun prefixInspectionPushesAllBytesBackBeforeParserReads() {
        val payload = (
            "<?xml version=\"1.0\"?>\n" +
                "<tv><channel id=\"1\"><display-name>One</display-name></channel></tv>"
            ).toByteArray(Charsets.UTF_8)

        val inspected = EpgSourceFormatPolicy.requireXmlTv(ByteArrayInputStream(payload))

        assertArrayEquals(payload, inspected.readBytes())
    }

    @Test
    fun productionEnvelopeRejectsHtmlBeforeXmlParser() {
        val secretBody = "<!doctype html><html><body>token=do-not-log</body></html>"
        val error = assertThrows(EpgMalformedXmlException::class.java) {
            EpgBoundedInputStream(
                input = ByteArrayInputStream(secretBody.toByteArray(Charsets.UTF_8)),
                maxBytes = EpgInputSafetyPolicy.MAX_INPUT_BYTES
            )
        }

        assertTrue(error.message.orEmpty().contains("format=HTML"))
        assertFalse(error.message.orEmpty().contains("do-not-log"))
    }

    @Test
    fun productionEnvelopePreservesValidXmlTvAndStillCountsBodyOnce() {
        val payload = "<tv><channel id=\"1\"/></tv>".toByteArray(Charsets.UTF_8)
        val stream = EpgBoundedInputStream(
            input = ByteArrayInputStream(payload),
            maxBytes = EpgInputSafetyPolicy.MAX_INPUT_BYTES
        )

        assertArrayEquals(payload, stream.readBytes())
    }
}
