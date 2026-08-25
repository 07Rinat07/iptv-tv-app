package com.iptv.tv.core.playervlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCompatibilityMatrixContractTest {
    private val expectedHeader = listOf(
        "sample_id",
        "protocol",
        "container",
        "video_codec",
        "audio_codecs",
        "profile_level",
        "resolution_fps",
        "source_kind",
        "device_gate",
        "evidence_status",
        "actual_backend",
        "decoder_mode",
        "first_frame",
        "first_audio",
        "fallback_reason",
        "channel_switch",
        "notes"
    )

    @Test
    fun `matrix schema is stable and rows are complete`() {
        val matrix = loadMatrix()

        assertEquals(expectedHeader, matrix.header)
        assertTrue(matrix.rows.isNotEmpty())
        assertEquals(
            matrix.rows.size,
            matrix.rows.map { it.getValue("sample_id") }.distinct().size
        )
        matrix.rows.forEach { row ->
            expectedHeader.forEach { column ->
                assertTrue("${row["sample_id"]}: $column must not be blank", row.getValue(column).isNotBlank())
            }
        }
    }

    @Test
    fun `matrix covers issue 211 codec and delivery targets`() {
        val rows = loadMatrix().rows
        val videoCodecs = rows.map { it.getValue("video_codec") }.toSet()
        val audioCodecs = rows
            .flatMap { it.getValue("audio_codecs").split('+') }
            .toSet()
        val deliveries = rows
            .map { "${it.getValue("protocol")}/${it.getValue("container")}" }
            .toSet()

        assertTrue(
            videoCodecs.containsAll(
                setOf("H264_AVC", "H265_HEVC", "MPEG2_VIDEO", "MPEG4_PART2", "VP9", "AV1")
            )
        )
        assertTrue(
            audioCodecs.containsAll(
                setOf("AAC", "HE_AAC", "MP2", "MP3", "AC3", "E_AC3", "OPUS")
            )
        )
        assertTrue(
            deliveries.containsAll(
                setOf(
                    "HTTP/MPEG_TS",
                    "HTTPS/MPEG_TS",
                    "HLS/HLS",
                    "HTTPS/MP4",
                    "HTTPS/MKV",
                    "HTTPS/WEBM",
                    "LOOPBACK_HTTP/MPEG_TS"
                )
            )
        )
    }

    @Test
    fun `unexecuted targets cannot masquerade as device evidence`() {
        val rows = loadMatrix().rows
        val pendingEvidenceColumns = listOf(
            "actual_backend",
            "decoder_mode",
            "first_frame",
            "first_audio",
            "fallback_reason",
            "channel_switch"
        )

        rows.forEach { row ->
            assertEquals("NOT_RUN", row.getValue("evidence_status"))
            pendingEvidenceColumns.forEach { column ->
                assertEquals("${row.getValue("sample_id")}: $column", "PENDING", row.getValue(column))
            }
        }
    }

    @Test
    fun `matrix includes multi audio and bounded loopback targets`() {
        val rows = loadMatrix().rows

        assertTrue(rows.any { '+' in it.getValue("audio_codecs") })
        val loopback = rows.single { it.getValue("source_kind") == "P2P_LOOPBACK" }
        assertEquals("LOOPBACK_HTTP", loopback.getValue("protocol"))
        assertEquals("MPEG_TS", loopback.getValue("container"))
        assertFalse(loopback.getValue("notes").isBlank())
    }

    private fun loadMatrix(): Matrix {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("playback_compatibility_matrix.tsv")
        ) { "Missing playback_compatibility_matrix.tsv test resource" }
        val lines = stream.bufferedReader().use { reader ->
            reader.readLines().filter(String::isNotBlank)
        }
        check(lines.size >= 2) { "Compatibility matrix must contain a header and at least one row" }
        val header = lines.first().split('\t')
        val rows = lines.drop(1).mapIndexed { index, line ->
            val values = line.split('\t')
            check(values.size == header.size) {
                "Compatibility matrix row ${index + 2} has ${values.size} columns; expected ${header.size}"
            }
            header.zip(values).toMap()
        }
        return Matrix(header = header, rows = rows)
    }

    private data class Matrix(
        val header: List<String>,
        val rows: List<Map<String, String>>
    )
}
