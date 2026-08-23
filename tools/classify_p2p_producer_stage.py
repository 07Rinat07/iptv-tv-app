#!/usr/bin/env python3
"""Classify Ace Live field failures by the first missing producer transition.

This tool is observational only. It does not change peer selection, DHT, request depth,
timeouts, buffering, or any other runtime policy. It consumes exported diagnostics from
the V4d producer-boundary instrumentation and applies the decision order from Issue #159.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable

PRODUCER_STATUS = "embedded_ace_live_producer_boundary"
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


@dataclass
class RuntimeEvidence:
    key: RuntimeKey
    counters: dict[str, int] = field(default_factory=lambda: {name: 0 for name in COUNTERS})
    sessions: set[int] = field(default_factory=set)
    records: int = 0
    last_stage: str | None = None
    last_disposition: str | None = None

    def absorb(self, fields: dict[str, str]) -> None:
        self.records += 1
        self.last_stage = fields.get("stage", self.last_stage)
        disposition = fields.get("disposition")
        if disposition and disposition != "none":
            self.last_disposition = disposition
        session = _parse_int(fields.get("session"))
        if session is not None:
            self.sessions.add(session)
        for counter in COUNTERS:
            value = _parse_int(fields.get(counter))
            if value is not None:
                self.counters[counter] = max(self.counters[counter], value)


@dataclass(frozen=True)
class RuntimeClassification:
    key: RuntimeKey
    first_missing_transition: str
    action_boundary: str
    counters: dict[str, int]
    sessions: tuple[int, ...]
    records: int
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
    for line in lines:
        if PRODUCER_STATUS not in line:
            continue
        fields = parse_fields(line)
        key = runtime_key_from_fields(fields)
        if key is None:
            uncorrelated_records += 1
            continue
        evidence = runtimes.setdefault(key, RuntimeEvidence(key=key))
        evidence.absorb(fields)
    return runtimes, uncorrelated_records


def classify_runtime(evidence: RuntimeEvidence) -> RuntimeClassification:
    c = evidence.counters
    if c["scheduled"] == 0:
        missing = "scheduled"
        action = "scheduler-output-boundary"
    elif c["selected"] == 0:
        missing = "selected"
        action = "request-selection-readiness-window-ownership-boundary"
    elif c["sent"] == 0:
        missing = "sent"
        action = "local-write-routing-ownership-boundary"
    elif c["chunk_ingress"] == 0:
        missing = "chunk_ingress"
        action = "remote-ingress-boundary; alternate-peer-probe-candidate-only-with-field-evidence"
    elif c["chunk_accepted"] == 0 and c["chunk_rejected"] > 0:
        missing = "chunk_accepted"
        action = "chunk-rejection-auth-protocol-boundary"
    elif c["chunk_accepted"] == 0:
        missing = "chunk_accepted"
        action = "chunk-acceptance-boundary"
    elif c["piece_completed"] == 0:
        missing = "piece_completed"
        action = "piece-assembly-completion-boundary"
    elif c["authenticated"] == 0:
        missing = "authenticated"
        action = "authentication-resync-output-boundary"
    elif c["ts_resync_output"] == 0:
        missing = "ts_resync_output"
        action = "authentication-resync-output-boundary"
    elif c["media_appended"] == 0:
        missing = "media_appended"
        action = "authentication-resync-output-boundary"
    else:
        missing = "player_ready_or_frame_audio"
        action = "player-ts-demux-boundary; do-not-change-p2p-acquisition"

    return RuntimeClassification(
        key=evidence.key,
        first_missing_transition=missing,
        action_boundary=action,
        counters=dict(c),
        sessions=tuple(sorted(evidence.sessions)),
        records=evidence.records,
        last_stage=evidence.last_stage,
        last_disposition=evidence.last_disposition,
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
