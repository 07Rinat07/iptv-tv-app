from __future__ import annotations

import csv
import io
import json
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence

STRUCTURED_HEADER = "=== Structured diagnostics"
PERSISTENT_HEADER = "=== Persistent application log ==="

STRUCTURED_LINE_RE = re.compile(
    r"^(?P<created_at>.+?)\s+\|\s+(?P<status>[^|]+?)\s+\|\s+playlist=(?P<playlist>[^|]+?)\s+\|\s+(?P<message>.*)$"
)
CHANNEL_ID_RE = re.compile(r"\bchannelId=(?P<value>\d+)\b")
PLAYLIST_ID_RE = re.compile(r"\bplaylistId=(?P<value>\d+)\b")
STREAM_KIND_RE = re.compile(r"\bstreamKind=(?P<value>.+?)(?:,\s|$)")
STARTUP_MS_RE = re.compile(r"\bstartupMs=(?P<value>\d+)\b")


@dataclass(frozen=True)
class LogEvent:
    timestamp_ms: int
    status: str
    playlist_id: int | None
    message: str
    source_index: int


@dataclass
class PlaybackRequest:
    request_no: int
    requested_at_ms: int
    playlist_id: int | None
    channel_id: int | None
    stream_kind: str = "unknown"
    peer_connected_at_ms: int | None = None
    startup_buffer_ready_at_ms: int | None = None
    resolved_at_ms: int | None = None
    player_start_at_ms: int | None = None
    player_ready_at_ms: int | None = None
    player_reported_startup_ms: int | None = None
    completed_at_ms: int | None = None
    outcome: str = "pending"
    detail: str = ""

    def elapsed(self, timestamp_ms: int | None) -> int | None:
        if timestamp_ms is None:
            return None
        return max(0, timestamp_ms - self.requested_at_ms)

    @property
    def peer_ms(self) -> int | None:
        return self.elapsed(self.peer_connected_at_ms)

    @property
    def startup_buffer_ms(self) -> int | None:
        return self.elapsed(self.startup_buffer_ready_at_ms)

    @property
    def resolve_ms(self) -> int | None:
        return self.elapsed(self.resolved_at_ms)

    @property
    def player_start_ms(self) -> int | None:
        return self.elapsed(self.player_start_at_ms)

    @property
    def player_ready_ms(self) -> int | None:
        return self.elapsed(self.player_ready_at_ms)

    @property
    def total_ms(self) -> int | None:
        if self.player_ready_at_ms is not None:
            return self.player_ready_ms
        return self.elapsed(self.completed_at_ms)

    def as_row(self) -> dict[str, object]:
        return {
            "request": self.request_no,
            "requested_at_ms": self.requested_at_ms,
            "playlist_id": self.playlist_id,
            "channel_id": self.channel_id,
            "stream_kind": self.stream_kind,
            "peer_ms": self.peer_ms,
            "startup_buffer_ms": self.startup_buffer_ms,
            "resolve_ms": self.resolve_ms,
            "player_start_ms": self.player_start_ms,
            "player_ready_ms": self.player_ready_ms,
            "player_reported_startup_ms": self.player_reported_startup_ms,
            "total_ms": self.total_ms,
            "outcome": self.outcome,
            "detail": self.detail,
        }


@dataclass(frozen=True)
class AnalysisSummary:
    total_requests: int
    ready_requests: int
    superseded_requests: int
    failed_requests: int
    pending_requests: int
    ignored_duplicate_requests: int
    median_ready_ms: int | None
    p90_ready_ms: int | None
    max_ready_ms: int | None


@dataclass(frozen=True)
class PlaybackAnalysis:
    requests: list[PlaybackRequest]
    summary: AnalysisSummary


def _parse_timestamp_ms(raw: str) -> int:
    value = raw.strip()
    if re.fullmatch(r"\d+", value):
        numeric = int(value)
        return numeric * 1000 if numeric < 10_000_000_000 else numeric

    normalized = value.replace("Z", "+00:00")
    candidates = (
        lambda: datetime.fromisoformat(normalized),
        lambda: datetime.strptime(value, "%Y-%m-%d %H:%M:%S.%f"),
        lambda: datetime.strptime(value, "%Y-%m-%d %H:%M:%S"),
    )
    for parser in candidates:
        try:
            parsed = parser()
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return int(parsed.timestamp() * 1000)
        except ValueError:
            continue
    raise ValueError(f"Unsupported diagnostics timestamp: {raw!r}")


def parse_structured_events(text: str) -> list[LogEvent]:
    events: list[LogEvent] = []
    in_structured_section = False

    for source_index, raw_line in enumerate(text.splitlines()):
        line = raw_line.strip("\ufeff")
        if line.startswith(STRUCTURED_HEADER):
            in_structured_section = True
            continue
        if line.startswith(PERSISTENT_HEADER):
            in_structured_section = False
            continue
        if not in_structured_section or not line.strip():
            continue

        match = STRUCTURED_LINE_RE.match(line)
        if not match:
            continue

        playlist_raw = match.group("playlist").strip()
        playlist_id = int(playlist_raw) if playlist_raw.isdigit() else None
        events.append(
            LogEvent(
                timestamp_ms=_parse_timestamp_ms(match.group("created_at")),
                status=match.group("status").strip(),
                playlist_id=playlist_id,
                message=match.group("message").strip(),
                source_index=source_index,
            )
        )

    # Diagnostics exports are newest-first. For equal timestamps, larger source indexes
    # are older and therefore must be processed first.
    events.sort(key=lambda event: (event.timestamp_ms, -event.source_index))
    return events


def _extract_int(pattern: re.Pattern[str], message: str) -> int | None:
    match = pattern.search(message)
    return int(match.group("value")) if match else None


def _extract_stream_kind(message: str) -> str | None:
    match = STREAM_KIND_RE.search(message)
    return match.group("value").strip() if match else None


def _latest_pending(
    requests: Sequence[PlaybackRequest],
    *,
    channel_id: int | None = None,
    require_started: bool = False,
) -> PlaybackRequest | None:
    for request in reversed(requests):
        if request.outcome != "pending":
            continue
        if channel_id is not None and request.channel_id != channel_id:
            continue
        if require_started and request.player_start_at_ms is None:
            continue
        return request
    return None


def _finish_superseded(request: PlaybackRequest, timestamp_ms: int) -> None:
    if request.outcome == "pending":
        request.outcome = "superseded"
        request.completed_at_ms = timestamp_ms
        request.detail = "new primary playback request arrived before READY"


def analyze_events(events: Iterable[LogEvent]) -> PlaybackAnalysis:
    requests: list[PlaybackRequest] = []
    ignored_duplicates = 0

    for event in events:
        status = event.status
        channel_id = _extract_int(CHANNEL_ID_RE, event.message)

        if status == "player_play_request":
            previous = _latest_pending(requests)
            if previous is not None:
                _finish_superseded(previous, event.timestamp_ms)

            message_playlist = _extract_int(PLAYLIST_ID_RE, event.message)
            requests.append(
                PlaybackRequest(
                    request_no=len(requests) + 1,
                    requested_at_ms=event.timestamp_ms,
                    playlist_id=message_playlist if message_playlist is not None else event.playlist_id,
                    channel_id=channel_id,
                )
            )
            continue

        if status == "player_play_request_ignored":
            ignored_duplicates += 1
            continue

        if status == "player_resolve_ok":
            request = _latest_pending(requests, channel_id=channel_id)
            if request is not None:
                request.resolved_at_ms = event.timestamp_ms
                stream_kind = _extract_stream_kind(event.message)
                if stream_kind:
                    request.stream_kind = stream_kind
            continue

        if status == "player_start":
            request = _latest_pending(requests, channel_id=channel_id)
            if request is not None:
                request.player_start_at_ms = event.timestamp_ms
            continue

        if status == "player_ready":
            request = _latest_pending(requests, require_started=True)
            if request is not None:
                request.player_ready_at_ms = event.timestamp_ms
                request.player_reported_startup_ms = _extract_int(STARTUP_MS_RE, event.message)
                request.completed_at_ms = event.timestamp_ms
                request.outcome = "ready"
            continue

        if status == "player_resolve_error":
            request = _latest_pending(requests, channel_id=channel_id)
            if request is not None:
                request.completed_at_ms = event.timestamp_ms
                request.outcome = "resolve_error"
                request.detail = event.message[:240]
            continue

        if status in {"player_play_request_stale", "player_start_ignored"}:
            request = _latest_pending(requests, channel_id=channel_id)
            if request is not None:
                request.completed_at_ms = event.timestamp_ms
                request.outcome = "stale"
                request.detail = event.message[:240]
            continue

        if "peer_connected" in status:
            request = _latest_pending(requests)
            if request is not None and request.peer_connected_at_ms is None:
                request.peer_connected_at_ms = event.timestamp_ms
            continue

        if "startup_buffer_ready" in status or "first_media_byte" in status:
            request = _latest_pending(requests)
            if request is not None and request.startup_buffer_ready_at_ms is None:
                request.startup_buffer_ready_at_ms = event.timestamp_ms
            continue

        if status in {
            "embedded_ace_live_resolved",
            "embedded_ace_live_infohash_resolved",
            "embedded_ace_live_transport_resolved",
            "embedded_ace_live_metadata_infohash_resolved",
            "embedded_p2p_resolved",
            "embedded_p2p_content_id_resolved",
        }:
            request = _latest_pending(requests)
            if request is not None and request.resolved_at_ms is None:
                request.resolved_at_ms = event.timestamp_ms
            continue

    ready_latencies = sorted(
        latency
        for request in requests
        if request.outcome == "ready"
        for latency in [request.player_ready_ms]
        if latency is not None
    )

    summary = AnalysisSummary(
        total_requests=len(requests),
        ready_requests=sum(request.outcome == "ready" for request in requests),
        superseded_requests=sum(request.outcome in {"superseded", "stale"} for request in requests),
        failed_requests=sum(request.outcome == "resolve_error" for request in requests),
        pending_requests=sum(request.outcome == "pending" for request in requests),
        ignored_duplicate_requests=ignored_duplicates,
        median_ready_ms=_percentile_nearest_rank(ready_latencies, 0.50),
        p90_ready_ms=_percentile_nearest_rank(ready_latencies, 0.90),
        max_ready_ms=max(ready_latencies) if ready_latencies else None,
    )
    return PlaybackAnalysis(requests=requests, summary=summary)


def _percentile_nearest_rank(values: Sequence[int], fraction: float) -> int | None:
    if not values:
        return None
    rank = max(1, int(len(values) * fraction + 0.999999))
    return values[min(rank - 1, len(values) - 1)]


def analyze_text(text: str) -> PlaybackAnalysis:
    return analyze_events(parse_structured_events(text))


def analyze_file(path: str | Path) -> PlaybackAnalysis:
    return analyze_text(Path(path).read_text(encoding="utf-8", errors="replace"))


def render_table(analysis: PlaybackAnalysis) -> str:
    headers = [
        "#",
        "playlist",
        "channel",
        "kind",
        "peer",
        "buffer",
        "resolve",
        "start",
        "ready",
        "outcome",
    ]
    rows: list[list[str]] = []
    for request in analysis.requests:
        rows.append(
            [
                str(request.request_no),
                _fmt(request.playlist_id),
                _fmt(request.channel_id),
                request.stream_kind,
                _fmt_ms(request.peer_ms),
                _fmt_ms(request.startup_buffer_ms),
                _fmt_ms(request.resolve_ms),
                _fmt_ms(request.player_start_ms),
                _fmt_ms(request.player_ready_ms),
                request.outcome,
            ]
        )

    widths = [len(header) for header in headers]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], len(cell))

    def format_row(row: Sequence[str]) -> str:
        return "  ".join(cell.ljust(widths[index]) for index, cell in enumerate(row)).rstrip()

    lines = [format_row(headers), format_row(["-" * width for width in widths])]
    lines.extend(format_row(row) for row in rows)

    summary = analysis.summary
    lines.extend(
        [
            "",
            (
                "summary: "
                f"requests={summary.total_requests}, ready={summary.ready_requests}, "
                f"superseded={summary.superseded_requests}, failed={summary.failed_requests}, "
                f"pending={summary.pending_requests}, ignored_duplicates={summary.ignored_duplicate_requests}"
            ),
            (
                "ready latency: "
                f"median={_fmt_ms(summary.median_ready_ms)}, "
                f"p90={_fmt_ms(summary.p90_ready_ms)}, max={_fmt_ms(summary.max_ready_ms)}"
            ),
        ]
    )
    return "\n".join(lines)


def render_csv(analysis: PlaybackAnalysis) -> str:
    output = io.StringIO()
    rows = [request.as_row() for request in analysis.requests]
    fieldnames = list(rows[0].keys()) if rows else list(PlaybackRequest(0, 0, None, None).as_row().keys())
    writer = csv.DictWriter(output, fieldnames=fieldnames, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue()


def render_json(analysis: PlaybackAnalysis) -> str:
    payload = {
        "summary": asdict(analysis.summary),
        "requests": [request.as_row() for request in analysis.requests],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


def _fmt(value: object | None) -> str:
    return "-" if value is None else str(value)


def _fmt_ms(value: int | None) -> str:
    return "-" if value is None else f"{value}ms"
