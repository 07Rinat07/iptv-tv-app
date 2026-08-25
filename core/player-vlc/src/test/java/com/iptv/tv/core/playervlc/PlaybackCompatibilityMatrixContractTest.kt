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
        "redirect_mode",
        "request_headers",
        "video_codec",
        "audio_codecs",
        "profile_level",
        "resolution_fps",
        "source_kind",
        "device_gate",
        "evidence_status",
        "run_id",
        "device_model",
        "android_api",
        "app_build",
        "sample_revision",
        "evidence_artifact",
        "actual_backend",
        "decoder_mode",
        "first_frame",
        "first_audio",
        "fallback_reason",
        "channel_switch",
        "multi_audio_result",
        "notes"
    )

    private val baselineSampleIds = setOf(
        "http_ts_h264_aac",
        "https_ts_h265_eac3",
        "http_ts_mpeg2_ac3",
        "http_ts_h264_he_aac",
        "hls_h264_aac",
        "hls_h265_ac3",
        "mp4_h264_aac",
        "mkv_mpeg4_mp3",
        "webm_vp9_opus",
        "webm_av1_opus",
        "http_ts_h264_mp2",
        "http_ts_h264_multi_audio",
        "loopback_ts_h264_aac",
        "https_ts_h264_he_aac_arm64",
        "redirect_http_to_https_h264_aac",
        "https_ts_h264_headers"
    )

    private val provenanceColumns = listOf(
        "run_id",
        "device_model",
        "android_api",
        "app_build",
        "sample_revision",
        "evidence_artifact"
    )

    private val resultColumns = listOf(
        "actual_backend",
        "decoder_mode",
        "first_frame",
        "first_audio",
        "fallback_reason",
        "channel_switch",
        "multi_audio_result"
    )

    @Test
    fun `matrix schema ids and profile levels are stable`() {
        val matrix = loadMatrix()

        assertEquals(expectedHeader, matrix.header)
        assertTrue(matrix.rows.isNotEmpty())
        val ids = matrix.rows.map { it.getValue("sample_id") }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue("Baseline sample ids must remain addressable", ids.toSet().containsAll(baselineSampleIds))

        matrix.rows.forEach { row ->
            expectedHeader.forEach { column ->
                assertTrue("${row["sample_id"]}: $column must not be blank", row.getValue(column).isNotBlank())
            }
            val profileLevel = row.getValue("profile_level")
            val separator = profileLevel.indexOf('@')
            assertTrue(
                "${row.getValue("sample_id")}: profile_level must use PROFILE@LEVEL",
                separator > 0 && separator < profileLevel.lastIndex
            )
        }
    }

    @Test
    fun `matrix covers issue 211 codec delivery redirect and header targets`() {
        val rows = loadMatrix().rows
        val videoCodecs = rows.map { it.getValue("video_codec") }.toSet()
        val audioCodecs = rows.flatMap { it.audioCodecs() }.toSet()
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
        assertTrue(rows.any { it.getValue("redirect_mode") != "NONE" })
        assertTrue(rows.any { it.getValue("request_headers") != "NONE" })
    }

    @Test
    fun `arm64 minimum acceptance keeps required video and audio targets`() {
        val arm64Rows = loadMatrix().rows.filter { it.getValue("device_gate") == "ARM64_TV_BOX" }
        val videoCodecs = arm64Rows.map { it.getValue("video_codec") }.toSet()
        val audioCodecs = arm64Rows.flatMap { it.audioCodecs() }.toSet()

        assertTrue(videoCodecs.containsAll(setOf("H264_AVC", "H265_HEVC", "MPEG2_VIDEO")))
        assertTrue(audioCodecs.containsAll(setOf("AAC", "HE_AAC", "AC3", "E_AC3")))
    }

    @Test
    fun `evidence states remain fail closed and executed rows require provenance`() {
        val rows = loadMatrix().rows
        val allowedEvidenceStatuses = setOf("NOT_RUN", "PASS", "FAIL", "UNSUPPORTED_CAPABILITY")
        val allowedBackends = setOf("MEDIA3", "LIBVLC", "NOT_STARTED")
        val allowedDecoderModes = setOf("HARDWARE", "SOFTWARE", "MIXED", "UNKNOWN", "NOT_APPLICABLE")
        val allowedBinaryResults = setOf("PASS", "FAIL", "NOT_APPLICABLE")

        rows.forEach { row ->
            val sampleId = row.getValue("sample_id")
            val status = row.getValue("evidence_status")
            assertTrue("$sampleId: unsupported evidence_status $status", status in allowedEvidenceStatuses)

            if (status == "NOT_RUN") {
                (provenanceColumns + resultColumns).forEach { column ->
                    assertEquals("$sampleId: $column", "PENDING", row.getValue(column))
                }
                return@forEach
            }

            provenanceColumns.forEach { column ->
                assertTrue("$sampleId: executed row requires $column", row.getValue(column) != "PENDING")
            }
            resultColumns.forEach { column ->
                assertTrue("$sampleId: executed row requires $column", row.getValue(column) != "PENDING")
            }

            assertTrue(row.getValue("actual_backend") in allowedBackends)
            assertTrue(row.getValue("decoder_mode") in allowedDecoderModes)
            assertTrue(row.getValue("first_frame") in allowedBinaryResults)
            assertTrue(row.getValue("first_audio") in allowedBinaryResults)
            assertTrue(row.getValue("channel_switch") in allowedBinaryResults)
            assertTrue(row.getValue("multi_audio_result") in allowedBinaryResults)

            if (status == "PASS") {
                assertTrue(row.getValue("actual_backend") in setOf("MEDIA3", "LIBVLC"))
                assertEquals("PASS", row.getValue("first_frame"))
                assertEquals("PASS", row.getValue("first_audio"))
            }

            val isMultiAudio = row.audioCodecs().size > 1
            when {
                isMultiAudio && status != "UNSUPPORTED_CAPABILITY" -> {
                    assertTrue(row.getValue("multi_audio_result") in setOf("PASS", "FAIL"))
                }
                !isMultiAudio -> assertEquals("NOT_APPLICABLE", row.getValue("multi_audio_result"))
            }
        }
    }

    @Test
    fun `matrix includes multi audio selection and bounded loopback targets`() {
        val rows = loadMatrix().rows
        val multiAudio = rows.single { it.getValue("sample_id") == "http_ts_h264_multi_audio" }
        assertTrue(multiAudio.audioCodecs().size > 1)

        val loopback = rows.single { it.getValue("source_kind") == "P2P_LOOPBACK" }
        assertEquals("LOOPBACK_HTTP", loopback.getValue("protocol"))
        assertEquals("MPEG_TS", loopback.getValue("container"))
        assertFalse(loopback.getValue("notes").isBlank())
    }

    private fun Map<String, String>.audioCodecs(): List<String> =
        getValue("audio_codecs").split('+')

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
