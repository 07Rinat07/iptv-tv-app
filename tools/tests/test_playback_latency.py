from __future__ import annotations

import sys
from pathlib import Path
import unittest

TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from playback_latency import analyze_text, parse_structured_events, render_csv, render_json


class PlaybackLatencyAnalyzerTest(unittest.TestCase):
    def test_parses_newest_first_export_and_correlates_ready_request(self) -> None:
        text = """=== Structured diagnostics (latest 120 rows) ===
1710000005000 | player_ready | playlist=1 | Internal ready, startupMs=1000, sessionId=2
1710000004000 | player_start | playlist=1 | Internal playback start: channelId=20
1710000003500 | player_resolve_ok | playlist=1 | channelId=20, streamKind=IPTV поток (прямой URL)
1710000003000 | player_play_request | playlist=1 | channelId=20, playlistId=1, requestedPlayer=INTERNAL, forceAce=false
1710000002500 | player_ready | playlist=1 | Internal ready, startupMs=500, sessionId=1
1710000002000 | player_start | playlist=1 | Internal playback start: channelId=10
1710000001800 | player_resolve_ok | playlist=1 | channelId=10, streamKind=IPTV поток (прямой URL)
1710000001000 | player_play_request | playlist=1 | channelId=10, playlistId=1, requestedPlayer=INTERNAL, forceAce=false

=== Persistent application log ===
[2026-08-13 16:00:00.000] INFO/App: ignored by structured parser
"""

        events = parse_structured_events(text)
        self.assertEqual("player_play_request", events[0].status)
        self.assertEqual(10, int(events[0].message.split("channelId=")[1].split(",")[0]))

        analysis = analyze_text(text)
        self.assertEqual(2, analysis.summary.total_requests)
        self.assertEqual(2, analysis.summary.ready_requests)
        self.assertEqual(1500, analysis.requests[0].player_ready_ms)
        self.assertEqual(2000, analysis.requests[1].player_ready_ms)
        self.assertEqual(1500, analysis.summary.median_ready_ms)
        self.assertEqual(2000, analysis.summary.p90_ready_ms)
        self.assertEqual(2000, analysis.summary.max_ready_ms)

    def test_marks_previous_pending_request_superseded_on_rapid_zap(self) -> None:
        text = """=== Structured diagnostics (latest 120 rows) ===
1710000002200 | player_start | playlist=1 | Internal playback start: channelId=2
1710000002100 | player_resolve_ok | playlist=1 | channelId=2, streamKind=IPTV поток (прямой URL)
1710000002000 | player_play_request | playlist=1 | channelId=2, playlistId=1, requestedPlayer=INTERNAL, forceAce=false
1710000001000 | player_play_request | playlist=1 | channelId=1, playlistId=1, requestedPlayer=INTERNAL, forceAce=false
"""

        analysis = analyze_text(text)
        first, second = analysis.requests
        self.assertEqual("superseded", first.outcome)
        self.assertEqual(1000, first.total_ms)
        self.assertEqual("pending", second.outcome)
        self.assertEqual(1, analysis.summary.superseded_requests)
        self.assertEqual(1, analysis.summary.pending_requests)

    def test_tracks_p2p_peer_buffer_resolve_and_ready_milestones(self) -> None:
        text = """=== Structured diagnostics (latest 120 rows) ===
1710000020000 | player_ready | playlist=7 | Internal ready, startupMs=1500, sessionId=9
1710000018500 | player_start | playlist=7 | Internal playback start: channelId=77
1710000018000 | player_resolve_ok | playlist=7 | channelId=77, streamKind=Ace Stream / Torrent TV
1710000017000 | embedded_ace_live_resolved | playlist=7 | Autonomous Ace Live stream prepared
1710000016000 | ace_live_startup_buffer_ready | playlist=7 | bytes=1048576
1710000015000 | ace_live_peer_connected | playlist=7 | peer=1.2.3.4:8621
1710000010000 | player_play_request | playlist=7 | channelId=77, playlistId=7, requestedPlayer=INTERNAL, forceAce=false
"""

        analysis = analyze_text(text)
        request = analysis.requests[0]
        self.assertEqual(5000, request.peer_ms)
        self.assertEqual(6000, request.startup_buffer_ms)
        self.assertEqual(8000, request.resolve_ms)
        self.assertEqual(8500, request.player_start_ms)
        self.assertEqual(10000, request.player_ready_ms)
        self.assertEqual(1500, request.player_reported_startup_ms)
        self.assertEqual("ready", request.outcome)
        self.assertIn("Ace Stream", request.stream_kind)

    def test_resolve_error_and_duplicate_request_are_counted(self) -> None:
        text = """=== Structured diagnostics (latest 120 rows) ===
1710000003000 | player_resolve_error | playlist=2 | channelId=5, reason=no peers
1710000002500 | player_play_request_ignored | playlist=2 | Duplicate internal playback request ignored: channelId=5
1710000002000 | player_play_request | playlist=2 | channelId=5, playlistId=2, requestedPlayer=INTERNAL, forceAce=false
"""

        analysis = analyze_text(text)
        self.assertEqual("resolve_error", analysis.requests[0].outcome)
        self.assertEqual(1000, analysis.requests[0].total_ms)
        self.assertEqual(1, analysis.summary.failed_requests)
        self.assertEqual(1, analysis.summary.ignored_duplicate_requests)

    def test_csv_and_json_exports_include_machine_readable_latency(self) -> None:
        text = """=== Structured diagnostics (latest 120 rows) ===
1710000003000 | player_ready | playlist=1 | Internal ready, startupMs=500, sessionId=1
1710000002500 | player_start | playlist=1 | Internal playback start: channelId=10
1710000002000 | player_resolve_ok | playlist=1 | channelId=10, streamKind=IPTV поток (прямой URL)
1710000001000 | player_play_request | playlist=1 | channelId=10, playlistId=1, requestedPlayer=INTERNAL, forceAce=false
"""
        analysis = analyze_text(text)

        csv_text = render_csv(analysis)
        json_text = render_json(analysis)

        self.assertIn("player_ready_ms", csv_text)
        self.assertIn("2000", csv_text)
        self.assertIn('"ready_requests": 1', json_text)
        self.assertIn('"channel_id": 10', json_text)

    def test_accepts_iso_timestamp_format(self) -> None:
        text = """=== Structured diagnostics (latest 120 rows) ===
2026-08-13 16:00:01.000 | player_start | playlist=1 | Internal playback start: channelId=1
2026-08-13 16:00:00.000 | player_play_request | playlist=1 | channelId=1, playlistId=1, requestedPlayer=INTERNAL, forceAce=false
"""
        analysis = analyze_text(text)
        self.assertEqual(1000, analysis.requests[0].player_start_ms)


if __name__ == "__main__":
    unittest.main()
