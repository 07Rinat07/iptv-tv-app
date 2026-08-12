package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpgXmlTvStreamingParserTest {
    @Test
    fun parsesValidXmlTvWithoutWholeBodyBuffer() {
        val xml = """
            <tv>
              <channel id="demo"><display-name>Demo TV</display-name></channel>
              <programme channel="demo" start="20260812100000 +0000" stop="20260812110000 +0000">
                <title>Morning</title><desc>Description</desc><category>News</category>
              </programme>
            </tv>
        """.trimIndent()

        val parsed = EpgXmlTvStreamingParser.parse(ByteArrayInputStream(xml.toByteArray()))

        assertEquals(setOf("Demo TV"), parsed.channelDisplayNames["demo"])
        assertEquals(1, parsed.programsByChannel["demo"]?.size)
        assertEquals("Morning", parsed.programsByChannel["demo"]?.single()?.title)
        assertEquals("demo", parsed.channelIdByDisplayNameKey["demotv"])
    }

    @Test
    fun rejectsMalformedXmlTvAsIOException() {
        val malformed = "<tv><channel id=\"demo\"><display-name>A & B</display-name></channel></tv>"

        val error = assertFailsWith<IOException> {
            EpgXmlTvStreamingParser.parse(ByteArrayInputStream(malformed.toByteArray()))
        }

        assertTrue(error.message.orEmpty().contains("Invalid XMLTV format"))
    }

    @Test
    fun rejectsInputBeyondHardByteLimit() {
        val xml = "<tv>" + " ".repeat(2_000) + "</tv>"

        val error = assertFailsWith<IOException> {
            EpgXmlTvStreamingParser.parse(
                ByteArrayInputStream(xml.toByteArray()),
                EpgXmlTvLimits(maxBytes = 512)
            )
        }

        assertTrue(error.message.orEmpty().contains("exceeded"))
    }

    @Test
    fun capsRetainedProgramsPerChannel() {
        val programmes = (0 until 10).joinToString("") { index ->
            val startHour = 10 + index
            val stopHour = 11 + index
            "<programme channel=\"demo\" start=\"20260812${startHour.toString().padStart(2, '0')}0000 +0000\" stop=\"20260812${stopHour.toString().padStart(2, '0')}0000 +0000\"><title>P$index</title></programme>"
        }
        val xml = "<tv><channel id=\"demo\"><display-name>Demo</display-name></channel>$programmes</tv>"

        val parsed = EpgXmlTvStreamingParser.parse(
            ByteArrayInputStream(xml.toByteArray()),
            EpgXmlTvLimits(maxProgramsPerChannel = 3, maxProgramsTotal = 20)
        )

        assertEquals(3, parsed.programsByChannel["demo"]?.size)
    }

    @Test
    fun truncatesLargeTextFieldsBeforeRetention() {
        val title = "X".repeat(5_000)
        val xml = "<tv><programme channel=\"demo\" start=\"20260812100000 +0000\" stop=\"20260812110000 +0000\"><title>$title</title></programme></tv>"

        val parsed = EpgXmlTvStreamingParser.parse(
            ByteArrayInputStream(xml.toByteArray()),
            EpgXmlTvLimits(maxTextChars = 64)
        )

        assertEquals(64, parsed.programsByChannel["demo"]?.single()?.title?.length)
    }
}
