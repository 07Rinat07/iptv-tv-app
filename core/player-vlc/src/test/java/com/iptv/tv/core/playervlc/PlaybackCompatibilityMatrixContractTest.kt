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
        "startup_result",
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
        "startup_result",
        "first_frame",
        "first_audio",
        "fallback_reason",
        "channel_switch",
        "multi_audio_result"
    )

    private val forbiddenEvidencePlaceholders = setOf(
        "PENDING",
        "UNKNOWN",
        "NOT_APPLICABLE",
        "NONE",
        "N/A",
        "NA",
        "TBD",
        "UNSPECIFIED",
        "NULL",
        "-"
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

        rows.forEach { row ->
            assertTrue(
                "${row.getValue("sample_id")}: unsupported redirect_mode",
                row.getValue("redirect_mode") in setOf("NONE", "HTTP_TO_HTTPS")
            )
            assertTrue(
                "${row.getValue("sample_id")}: unsupported request_headers",
                row.getValue("request_headers") in setOf("NONE", "USER_AGENT+REFERER")
            )
        }

        val redirectTarget = rows.single {
            it.getValue("sample_id") == "redirect_http_to_https_h264_aac"
        }
        assertEquals("HTTP", redirectTarget.getValue("protocol"))
        assertEquals("MPEG_TS", redirectTarget.getValue("container"))
        assertEquals("HTTP_TO_HTTPS", redirectTarget.getValue("redirect_mode"))
        assertEquals("NONE", redirectTarget.getValue("request_headers"))

        val headerTarget = rows.single {
            it.getValue("sample_id") == "https_ts_h264_headers"
        }
        assertEquals("HTTPS", headerTarget.getValue("protocol"))
        assertEquals("MPEG_TS", headerTarget.getValue("container"))
        assertEquals("NONE", headerTarget.getValue("redirect_mode"))
        assertEquals("USER_AGENT+REFERER", headerTarget.getValue("request_headers"))
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
    fun `evidence states remain fail closed and executed rows require concrete provenance`() {
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

            assertConcreteProvenance(row)
            resultColumns.forEach { column ->
                assertTrue("$sampleId: executed row requires $column", row.getValue(column) != "PENDING")
            }

            assertTrue(row.getValue("actual_backend") in allowedBackends)
            assertTrue(row.getValue("decoder_mode") in allowedDecoderModes)
            assertTrue(row.getValue("startup_result") in allowedBinaryResults)
            assertTrue(row.getValue("first_frame") in allowedBinaryResults)
            assertTrue(row.getValue("first_audio") in allowedBinaryResults)
            assertTrue(row.getValue("channel_switch") in allowedBinaryResults)
            assertTrue(row.getValue("multi_audio_result") in allowedBinaryResults)

            val isMultiAudio = row.audioCodecs().size > 1
            if (status == "PASS") {
                assertTrue(row.getValue("actual_backend") in setOf("MEDIA3", "LIBVLC"))
                assertEquals("PASS", row.getValue("startup_result"))
                assertEquals("PASS", row.getValue("first_frame"))
                assertEquals("PASS", row.getValue("first_audio"))
                if (isMultiAudio) {
                    assertEquals("PASS", row.getValue("multi_audio_result"))
                }
            }

            when {
                !isMultiAudio -> assertEquals("NOT_APPLICABLE", row.getValue("multi_audio_result"))
                status == "UNSUPPORTED_CAPABILITY" -> {
                    assertEquals("NOT_APPLICABLE", row.getValue("multi_audio_result"))
                }
                status != "PASS" -> {
                    assertTrue(row.getValue("multi_audio_result") in setOf("PASS", "FAIL"))
                }
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

    private fun assertConcreteProvenance(row: Map<String, String>) {
        val sampleId = row.getValue("sample_id")
        provenanceColumns.forEach { column ->
            val value = row.getValue(column)
            assertTrue(
                "$sampleId: executed row requires concrete $column",
                value.length >= 3 && value.uppercase() !in forbiddenEvidencePlaceholders
            )
        }

        assertTrue(
            "$sampleId: run_id must be a namespaced run reference",
            Regex("""[A-Za-z0-9][A-Za-z0-9._-]*:[A-Za-z0-9][A-Za-z0-9._:/-]*""")
                .matches(row.getValue("run_id"))
        )
        assertTrue(
            "$sampleId: android_api must use API_<level>",
            Regex("""API_\d+""").matches(row.getValue("android_api"))
        )
        assertTrue(
            "$sampleId: app_build must identify an exact git revision or version",
            Regex("""(?:git:[0-9a-fA-F]{7,40}|version:[A-Za-z0-9._+-]+)""")
                .matches(row.getValue("app_build"))
        )
        assertTrue(
            "$sampleId: sample_revision must be a sha256 digest",
            Regex("""sha256:[0-9a-fA-F]{64}""").matches(row.getValue("sample_revision"))
        )
        assertTrue(
            "$sampleId: evidence_artifact must be a stable URI reference",
            Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""").matches(row.getValue("evidence_artifact"))
        )
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
