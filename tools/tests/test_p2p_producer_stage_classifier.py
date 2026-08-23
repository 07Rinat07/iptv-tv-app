import unittest

from tools.classify_p2p_producer_stage import classify_lines


PREFIX = "I/P2P/AceBoundary: embedded_ace_live_producer_boundary"
GAP_PREFIX = "I/P2P/AcePeer: embedded_ace_live_producer_gap"


def boundary_line(*, stage: str, startup: int = 100, runtime: int = 4, generation: int = 9, path: str = "direct_retry", **counts: int) -> str:
    all_counts = {
        "scheduled": 0,
        "selected": 0,
        "sent": 0,
        "request_timeout": 0,
        "chunk_ingress": 0,
        "chunk_accepted": 0,
        "chunk_rejected": 0,
        "piece_completed": 0,
        "authenticated": 0,
        "authentication_rejected": 0,
        "ts_resync_output": 0,
        "media_appended": 0,
    }
    all_counts.update(counts)
    counters = " ".join(f"{key}={value}" for key, value in all_counts.items())
    return (
        f"{PREFIX} session=2 stage={stage} peer=7 piece=55 disposition=none bytes=18800 "
        f"startup_id={startup} runtime_id={runtime} generation={generation} path={path} {counters}"
    )


def gap_line(*, state: str = "active", startup: int = 100, runtime: int = 4, generation: int = 9, path: str = "direct_retry") -> str:
    return (
        f"{GAP_PREFIX} state={state} discovered=4 connected=2 handshaked=2 windowUseful=2 "
        f"unchoked=2 producing=0 aggregate_bps=0 startup_id={startup} runtime_id={runtime} "
        f"generation={generation} path={path}"
    )


class ProducerStageClassifierTest(unittest.TestCase):
    def classify_one(self, *lines: str):
        classifications, uncorrelated = classify_lines(lines)
        self.assertEqual(0, uncorrelated)
        self.assertEqual(1, len(classifications))
        return classifications[0]

    def test_active_producer_gap_without_boundary_classifies_scheduler_output(self):
        result = self.classify_one(gap_line())
        self.assertEqual("scheduled", result.first_missing_transition)
        self.assertEqual("scheduler-output-boundary", result.action_boundary)
        self.assertEqual(0, result.boundary_records)
        self.assertEqual(1, result.gap_records)

    def test_boundary_record_with_zero_scheduled_still_classifies_scheduler_output(self):
        result = self.classify_one(boundary_line(stage="scheduled"))
        self.assertEqual("scheduled", result.first_missing_transition)

    def test_selected_and_sent_boundaries_follow_issue_159_order(self):
        selected = self.classify_one(boundary_line(stage="scheduled", scheduled=3))
        self.assertEqual("selected", selected.first_missing_transition)

        sent = self.classify_one(boundary_line(stage="selected", scheduled=3, selected=2))
        self.assertEqual("sent", sent.first_missing_transition)

    def test_sent_without_ingress_is_only_alternate_peer_probe_candidate(self):
        result = self.classify_one(boundary_line(stage="sent", scheduled=3, selected=2, sent=2))
        self.assertEqual("chunk_ingress", result.first_missing_transition)
        self.assertIn("alternate-peer-probe-candidate", result.action_boundary)

    def test_rejected_ingress_is_not_misclassified_as_no_ingress(self):
        result = self.classify_one(
            boundary_line(
                stage="chunk_rejected",
                scheduled=3,
                selected=2,
                sent=2,
                chunk_ingress=2,
                chunk_rejected=2,
            )
        )
        self.assertEqual("chunk_accepted", result.first_missing_transition)
        self.assertEqual("chunk-rejection-auth-protocol-boundary", result.action_boundary)

    def test_media_append_routes_to_player_boundary_without_widening_p2p(self):
        result = self.classify_one(
            boundary_line(
                stage="media_appended",
                scheduled=3,
                selected=3,
                sent=3,
                chunk_ingress=3,
                chunk_accepted=3,
                piece_completed=2,
                authenticated=2,
                ts_resync_output=2,
                media_appended=2,
            )
        )
        self.assertEqual("player_ready_or_frame_audio", result.first_missing_transition)
        self.assertIn("do-not-change-p2p-acquisition", result.action_boundary)

    def test_cumulative_records_use_maximum_counts_not_last_line_order(self):
        high = boundary_line(stage="sent", scheduled=4, selected=3, sent=2)
        stale = boundary_line(stage="scheduled", scheduled=1, selected=0, sent=0)
        result = self.classify_one(high, stale)
        self.assertEqual(4, result.counters["scheduled"])
        self.assertEqual(3, result.counters["selected"])
        self.assertEqual(2, result.counters["sent"])
        self.assertEqual("chunk_ingress", result.first_missing_transition)

    def test_distinct_runtime_correlations_are_not_merged(self):
        classifications, uncorrelated = classify_lines(
            [
                boundary_line(stage="scheduled", runtime=1, scheduled=1),
                boundary_line(stage="sent", runtime=2, scheduled=1, selected=1, sent=1),
            ]
        )
        self.assertEqual(0, uncorrelated)
        self.assertEqual([1, 2], [item.key.runtime_id for item in classifications])
        self.assertEqual(["selected", "chunk_ingress"], [item.first_missing_transition for item in classifications])

    def test_gap_without_active_state_and_without_boundary_is_insufficient_evidence(self):
        result = self.classify_one(gap_line(state="resolved"))
        self.assertEqual("insufficient_boundary_evidence", result.first_missing_transition)
        self.assertIn("do-not-change-runtime-policy", result.action_boundary)

    def test_uncorrelated_records_are_reported_and_not_guessed(self):
        line = f"{PREFIX} session=2 stage=sent scheduled=1 selected=1 sent=1"
        classifications, uncorrelated = classify_lines([line])
        self.assertEqual([], classifications)
        self.assertEqual(1, uncorrelated)


if __name__ == "__main__":
    unittest.main()
