package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EpgGzipSafetyRegressionTest {
    @Test
    fun skippedBytesCountTowardHardInputLimit() {
        val stream = EpgBoundedInputStream(
            input = ByteArrayInputStream(ByteArray(16) { it.toByte() }),
            maxBytes = 8L,
            validateXmlTvPrefix = false
        )

        assertEquals(8L, stream.skip(8L))
        val error = assertThrows(EpgInputLimitExceededException::class.java) {
            stream.skip(1L)
        }
        assertEquals(8L, error.maxBytes)
    }

    @Test
    fun lateGzipCorruptionIsReportedAsMalformedAfterSuccessfulPrefixDecode() {
        val xml = buildString {
            append("<tv><channel id=\"field\"><display-name>")
            var state = 0x13579BDF
            repeat(80_000) {
                state = state * 1_103_515_245 + 12_345
                append(('A'.code + ((state ushr 16) and 25)).toChar())
            }
            append("</display-name></channel></tv>")
        }.toByteArray(Charsets.UTF_8)
        val corrupted = gzip(xml).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        val stream = EpgBoundedInputStream(
            input = ByteArrayInputStream(corrupted),
            maxBytes = EpgInputSafetyPolicy.MAX_INPUT_BYTES
        )

        val error = assertThrows(EpgMalformedXmlException::class.java) {
            stream.readBytes()
        }
        assertEquals(
            EpgFailureKind.MALFORMED,
            classifyEpgFailure(error)
        )
    }

    private fun gzip(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(input) }
        return output.toByteArray()
    }
}
