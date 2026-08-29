package com.iptv.tv.feature.diagnostics

import com.iptv.tv.core.model.SyncLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentForensicReportBuilderTest {
    @Test
    fun classifiesDiscoveryWithoutCandidates() {
        val report = TorrentForensicReportBuilder.build(
            listOf(
                log(1, "embedded_ace_live_startup_timeline", "phase=transport_selection, elapsed_ms=0"),
                log(2, "embedded_ace_live_startup_timeline", "phase=discovery_completed, elapsed_ms=250"),
                log(
                    3,
                    "embedded_ace_live_peer_discovery",
                    "phase=initial, elapsedMs=250, peers=0, tracker=SUCCEEDED/0, dht=SUCCEEDED/0, dhtQueries=12, dhtFailed=1"
                )
            )
        )

        assertTrue(report.contains("first_missing_stage: first_candidate"))
        assertTrue(report.contains("tracker=SUCCEEDED/0"))
    }

    @Test
    fun classifiesQualifiedPeerWithoutMedia() {
        val report = TorrentForensicReportBuilder.build(
            listOf(
                log(1, "embedded_ace_live_startup_timeline", "phase=transport_selection, elapsed_ms=0"),
                log(2, "embedded_ace_live_startup_timeline", "phase=discovery_completed, elapsed_ms=100"),
                log(3, "embedded_ace_live_startup_timeline", "phase=first_candidate, elapsed_ms=110"),
                log(4, "embedded_ace_live_startup_timeline", "phase=connected, elapsed_ms=200"),
                log(5, "embedded_ace_live_startup_timeline", "phase=handshake, elapsed_ms=260"),
                log(6, "embedded_ace_live_startup_timeline", "phase=useful_window, elapsed_ms=300"),
                log(
                    7,
                    "embedded_ace_live_peer_quality",
                    "discovered=6 connected=2 handshaked=2 windowUseful=1 unchoked=1 producing=0 aggregate_bps=0 startup_id=123 runtime_id=1 generation=1 path=direct"
                ),
                log(
                    8,
                    "embedded_ace_live_producer_gap",
                    "state=active discovered=6 connected=2 handshaked=2 windowUseful=1 unchoked=1 producing=0 aggregate_bps=0"
                )
            )
        )

        assertTrue(report.contains("first_missing_stage: first_media"))
        assertTrue(report.contains("producing=0"))
        assertTrue(report.contains("producer_gap: state=active"))
    }

    @Test
    fun reportsSuccessfulPathThroughFirstVideoFrame() {
        val phases = listOf(
            "transport_selection",
            "discovery_completed",
            "first_candidate",
            "connected",
            "handshake",
            "useful_window",
            "first_media",
            "buffer_ready",
            "http_reader_open",
            "http_first_read"
        )
        val logs = phases.mapIndexed { index, phase ->
            log(index.toLong() + 1, "embedded_ace_live_startup_timeline", "phase=$phase, elapsed_ms=${index * 100}")
        }.toMutableList()
        logs += log(20, "player_p2p_boundary", "event=load_started, sessionId=7, elapsed_ms=900")
        logs += log(21, "player_p2p_boundary", "event=ready, sessionId=7, elapsed_ms=1000")
        logs += log(22, "player_p2p_boundary", "event=first_video_frame, sessionId=7, elapsed_ms=1100")

        val report = TorrentForensicReportBuilder.build(logs)

        assertTrue(report.contains("first_missing_stage: none"))
        assertTrue(report.contains("first_video_frame"))
    }

    @Test
    fun aggregatesTransportRaceEvidencePerAttempt() {
        val report = TorrentForensicReportBuilder.build(
            listOf(
                log(1, "embedded_ace_live_startup_timeline", "phase=transport_selection, elapsed_ms=0"),
                log(
                    2,
                    "embedded_ace_live_transport_race",
                    "winner=tcp elapsed_ms=40 tcp_connected_ms=10 tcp_outcome=qualified_winner tcp_terminal_ms=40 " +
                        "utp_connected_ms=25 utp_outcome=handshake_rejected utp_terminal_ms=30 startup_id=1 runtime_id=1 generation=1 path=direct"
                ),
                log(
                    3,
                    "embedded_ace_live_transport_race",
                    "winner=utp elapsed_ms=60 tcp_connected_ms=20 tcp_outcome=handshake_rejected tcp_terminal_ms=50 " +
                        "utp_connected_ms=30 utp_outcome=qualified_winner utp_terminal_ms=60 startup_id=1 runtime_id=1 generation=1 path=direct"
                ),
                log(
                    4,
                    "embedded_ace_live_transport_race",
                    "winner=none elapsed_ms=100 tcp_connected_ms=none tcp_outcome=connect_failed tcp_terminal_ms=100 " +
                        "utp_connected_ms=none utp_outcome=connect_failed utp_terminal_ms=100 startup_id=1 runtime_id=1 generation=1 path=direct"
                )
            )
        )

        assertTrue(report.contains("transport_race: samples=3 malformed_samples=0 tcp_wins=1 utp_wins=1 no_winner=1"))
        assertTrue(report.contains("avg_elapsed_ms=66 p50_elapsed_ms=60"))
        assertTrue(report.contains("tcp_connect_avg_ms=15 utp_connect_avg_ms=27"))
        assertTrue(report.contains("tcp_outcomes=connect_failed:1,handshake_rejected:1,qualified_winner:1"))
        assertTrue(report.contains("utp_outcomes=connect_failed:1,handshake_rejected:1,qualified_winner:1"))
    }

    @Test
    fun malformedTransportRaceEvidenceIsBoundedAndDoesNotCrashReport() {
        val report = TorrentForensicReportBuilder.build(
            listOf(
                log(1, "embedded_ace_live_startup_timeline", "phase=transport_selection, elapsed_ms=0"),
                log(2, "embedded_ace_live_transport_race", "winner=tcp tcp_outcome=qualified_winner")
            )
        )

        assertTrue(report.contains("transport_race: samples=0 malformed_samples=1"))
    }

    @Test
    fun laterDirectPlaybackDoesNotCompletePreviousTorrentAttempt() {
        val phases = listOf(
            "transport_selection",
            "discovery_completed",
            "first_candidate",
            "connected",
            "handshake",
            "useful_window",
            "first_media",
            "buffer_ready",
            "http_reader_open",
            "http_first_read"
        )
        val logs = phases.mapIndexed { index, phase ->
            log(index.toLong() + 1, "embedded_ace_live_startup_timeline", "phase=$phase, elapsed_ms=${index * 100}")
        }.toMutableList()
        logs += log(20, "player_play_request", "channelId=22, playlistId=10, requestedPlayer=INTERNAL, forceAce=false")
        logs += log(21, "player_ready", "Internal ready, startupMs=50, sessionId=100")

        val report = TorrentForensicReportBuilder.build(logs)

        assertTrue(report.contains("first_missing_stage: media3_load_started"))
        assertFalse(report.contains("first_missing_stage: first_frame_or_audio"))
    }

    @Test
    fun redactsSensitiveTorrentIdentifiersFromFailureSummary() {
        val report = TorrentForensicReportBuilder.build(
            listOf(
                log(1, "embedded_ace_live_startup_timeline", "phase=transport_selection, elapsed_ms=0"),
                log(
                    2,
                    "embedded_ace_live_resolve_error",
                    "failed content=0123456789abcdef0123456789abcdef01234567 url=https://example.test/path?access_token=secret"
                )
            )
        )

        assertFalse(report.contains("0123456789abcdef0123456789abcdef01234567"))
        assertFalse(report.contains("secret"))
        assertTrue(report.contains("<redacted-40hex>"))
    }

    private fun log(id: Long, status: String, message: String) = SyncLog(
        id = id,
        playlistId = 10L,
        status = status,
        message = message,
        createdAt = 1_000L + id * 10L
    )
}
