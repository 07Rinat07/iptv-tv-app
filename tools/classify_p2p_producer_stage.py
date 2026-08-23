#!/usr/bin/env python3
"""Classify Ace Live field failures by the first missing producer transition.

Observational only: this tool never changes peer selection, DHT, request depth,
timeouts, buffering, or any runtime policy. Exported diagnostics are newest-first;
producer counters are cumulative, so current failures are classified from a bounded
observation window instead of lifetime maxima.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable

PRODUCER_STATUS = "embedded_ace_live_producer_boundary"
PRODUCER_GAP_STATUS = "embedded_ace_live_producer_gap"
CORRELATED_RUNTIME_STATUSES = (
    PRODUCER_STATUS,
    PRODUCER_GAP_STATUS,
    "embedded_ace_live_recovery",
    "embedded_ace_live_startup_timeline",
    "embedded_ace_live_peer_lifecycle",
    "embedded_ace_live_peer_discovery",
    "embedded_ace_live_peer_quality",
    "embedded_ace_live_buffer_pressure",
    "embedded_ace_live_loopback_http_lifecycle",
)
COUNTERS = (
    "scheduled",
    "selected",
    "sent",
    "request_timeout",
    "chunk_ingress",
    "chunk_accepted",
    "chunk_rejected",
    "piece_completed",
    "authenticated",
    "authentication_rejected",
    "ts_resync_output",
    "media_appended",
)
FIELD_PATTERN = re.compile(r"(?P<key>[A-Za-z_][A-Za-z0-9_]*)=(?P<value>[^\s,]+)")


@dataclass(frozen=True, order=True)
class RuntimeKey:
    startup_id: int
    runtime_id: int
    generation: int
    path: str


@dataclass(frozen=True)
class BoundarySnapshot:
    ordinal: int
    stage: str | None
    disposition: str | None
    session: int | None
    counters: dict[str, int]

    @property
    def progress(self) -> int:
        return sum(self.counters.values())


@dataclass
class RuntimeEvidence:
    key: RuntimeKey
    records: int = 0
    boundary_records: int = 0
    gap_records: int = 0
    correlated_records: int = 0
    active_gap_ordinals: list[int] = field(default_factory=list)
    snapshots: list[BoundarySnapshot] = field(default_factory=list)
    sessions: set[int] = field(default_factory=set)

    def absorb_boundary(self, fields: dict[str, str], ordinal: int) -> None:
        self.records += 1
        self.boundary_records += 1
        self.correlated_records += 1
        session = _parse_int(fields.get("session"))
        if session is not None:
            self.sessions.add(session)
        counters = {name: (_parse_int(fields.get(name)) or 0) for name in COUNTERS}
        disposition = fields.get("disposition")
        self.snapshots.append(
            BoundarySnapshot(
                ordinal=ordinal,
                stage=fields.get("stage"),
                disposition=disposition if disposition and disposition != "none" else None,
                session=session,
                counters=counters,
            )
        )

    def absorb_gap(self, fields: dict[str, str], ordinal: int) -> None:
        self.records += 1
        self.gap_records += 1
        self.correlated_records += 1
        if fields.get("state") == "active":
            self.active_gap_ordinals.append(ordinal)

    def absorb_correlated(self) -> None:
        self.records += 1
        self.correlated_records += 1


@dataclass(frozen=True)
class RuntimeClassification:
    key: RuntimeKey
    first_missing_transition: str
    action_boundary: str
    counters: dict[str, int]
    observation_deltas: dict[str, int]
    sessions: tuple[int, ...]
    records: int
    boundary_records: int
    gap_records: int
    last_stage: str | None
    last_disposition: str | None


def _parse_int(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None


def parse_fields(line: str) -> dict[str, str]:
    return {match.group("key"): match.group("value") for match in FIELD_PATTERN.finditer(line)}


def runtime_key_from_fields(fields: dict[str, str]) -> RuntimeKey | None:
    startup_id = _parse_int(fields.get("startup_id"))
    runtime_id = _parse_int(fields.get("runtime_id"))
    generation = _parse_int(fields.get("generation"))
    path = fields.get("path", "").strip()
    if startup_id is None or runtime_id is None or generation is None or not path:
        return None
    return RuntimeKey(startup_id=startup_id, runtime_id=runtime_id, generation=generation, path=path)


def collect_runtime_evidence(lines: Iterable[str]) -> tuple[dict[RuntimeKey, RuntimeEvidence], int]:
    runtimes: dict[RuntimeKey, RuntimeEvidence] = {}
    uncorrelated_records = 0
    for ordinal, line in enumerate(lines):
        marker = next((status for status in CORRELATED_RUNTIME_STATUSES if status in line), None)
        if marker is None:
            continue
        fields = parse_fields(line)
        key = runtime_key_from_fields(fields)
        if key is None:
            uncorrelated_records += 1
            continue
        evidence = runtimes.setdefault(key, RuntimeEvidence(key=key))
        if marker == PRODUCER_STATUS:
            evidence.absorb_boundary(fields, ordinal)
        elif marker == PRODUCER_GAP_STATUS:
            evidence.absorb_gap(fields, ordinal)
        else:
            evidence.absorb_correlated()
    return runtimes, uncorrelated_records


def _newest_snapshot(evidence: RuntimeEvidence) -> BoundarySnapshot | None:
    if not evidence.snapshots:
        return None
    # Counters are cumulative. Highest progress is newest regardless of whether the
    # caller supplied newest-first export rows or chronological logcat rows. Ties
    # keep the first row, matching SyncLogDao/buildLogsText newest-first exports.
    return max(evidence.snapshots, key=lambda snapshot: (snapshot.progress, -snapshot.ordinal))


def _window_deltas(evidence: RuntimeEvidence, newest: BoundarySnapshot) -> dict[str, int]:
    baseline = {name: 0 for name in COUNTERS}
    # After a runtime has already appended media, lifetime counters cannot classify
    # a later producer stall. Use the most recent earlier media_appended event as a
    # baseline and classify only new cumulative progress after that point.
    prior_media = [
        snapshot
        for snapshot in evidence.snapshots
        if snapshot.stage == "media_appended" and snapshot.progress < newest.progress
    ]
    if prior_media:
        baseline = max(prior_media, key=lambda snapshot: snapshot.progress).counters
    return {
        name: max(0, newest.counters[name] - baseline[name])
        for name in COUNTERS
    }


def _decision(counters: dict[str, int]) -> tuple[str, str]:
    if counters["scheduled"] == 0:
        return "scheduled", "scheduler-output-boundary"
    if counters["selected"] == 0:
        return "selected", "request-selection-readiness-window-ownership-boundary"
    if counters["sent"] == 0:
        return "sent", "local-write-routing-ownership-boundary"
    if counters["chunk_ingress"] == 0:
        return "chunk_ingress", "remote-ingress-boundary; alternate-peer-probe-candidate-only-with-field-evidence"
    if counters["chunk_accepted"] == 0 and counters["chunk_rejected"] > 0:
        return "chunk_accepted", "chunk-rejection-auth-protocol-boundary"
    if counters["chunk_accepted"] == 0:
        return "chunk_accepted", "chunk-acceptance-boundary"
    if counters["piece_completed"] == 0:
        return "piece_completed", "piece-assembly-completion-boundary"
    if counters["authenticated"] == 0:
        return "authenticated", "authentication-resync-output-boundary"
    if counters["ts_resync_output"] == 0:
        return "ts_resync_output", "authentication-resync-output-boundary"
    if counters["media_appended"] == 0:
        return "media_appended", "authentication-resync-output-boundary"
    return "player_ready_or_frame_audio", "player-ts-demux-boundary; do-not-change-p2p-acquisition"


def classify_runtime(evidence: RuntimeEvidence) -> RuntimeClassification:
    newest = _newest_snapshot(evidence)
    if newest is None:
        if evidence.active_gap_ordinals:
            missing = "scheduled"
            action = "scheduler-output-boundary"
        else:
            missing = "insufficient_boundary_evidence"
            action = "preserve-more-correlated-producer-evidence; do-not-change-runtime-policy"
        counters = {name: 0 for name in COUNTERS}
        deltas = dict(counters)
        last_stage = None
        last_disposition = None
    else:
        counters = dict(newest.counters)
        deltas = _window_deltas(evidence, newest)
        missing, action = _decision(deltas)
        last_stage = newest.stage
        dispositions = [snapshot for snapshot in evidence.snapshots if snapshot.disposition]
        last_disposition = (
            max(dispositions, key=lambda snapshot: (snapshot.progress, -snapshot.ordinal)).disposition
            if dispositions
            else None
        )

    return RuntimeClassification(
        key=evidence.key,
        first_missing_transition=missing,
        action_boundary=action,
        counters=counters,
        observation_deltas=deltas,
        sessions=tuple(sorted(evidence.sessions)),
        records=evidence.records,
        boundary_records=evidence.boundary_records,
        gap_records=evidence.gap_records,
        last_stage=last_stage,
        last_disposition=last_disposition,
    )


def classify_lines(lines: Iterable[str]) -> tuple[list[RuntimeClassification], int]:
    evidence_by_runtime, uncorrelated = collect_runtime_evidence(lines)
    classifications = [classify_runtime(evidence_by_runtime[key]) for key in sorted(evidence_by_runtime)]
    return classifications, uncorrelated


def _as_json(classifications: list[RuntimeClassification], uncorrelated_records: int) -> str:
    payload = {
        "uncorrelated_producer_records": uncorrelated_records,
        "runtimes": [
            {
                **asdict(item),
                "key": asdict(item.key),
            }
            for item in classifications
        ],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)


def _as_text(classifications: list[RuntimeClassification], uncorrelated_records: int) -> str:
    lines = [f"correlated_runtimes={len(classifications)} uncorrelated_records={uncorrelated_records}"]
    for item in classifications:
        key = item.key
        lines.append(
            " ".join(
                (
                    f"startup_id={key.startup_id}",
                    f"runtime_id={key.runtime_id}",
                    f"generation={key.generation}",
                    f"path={key.path}",
                    f"first_missing={item.first_missing_transition}",
                    f"action={item.action_boundary}",
                    f"last_stage={item.last_stage or 'none'}",
                    f"last_disposition={item.last_disposition or 'none'}",
                )
            )
        )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="Exported diagnostic/logcat text file")
    parser.add_argument("--json", action="store_true", dest="as_json", help="Emit machine-readable JSON")
    args = parser.parse_args()

    with args.log.open("r", encoding="utf-8", errors="replace") as source:
        classifications, uncorrelated = classify_lines(source)
    output = _as_json(classifications, uncorrelated) if args.as_json else _as_text(classifications, uncorrelated)
    print(output)
    return 0 if classifications else 2


if __name__ == "__main__":
    raise SystemExit(main())
