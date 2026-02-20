#!/usr/bin/env python3
"""
Build a lightweight training dataset for scanner query optimization from app logs.

Input format (one event per line):
<timestamp> | <status> | playlist=<id-or--> | <message>

Example:
1771500505471 | scanner_start | playlist=- | attempt=6, query=m3u., provider=ALL, mode=AUTO, ...
1771500526543 | scanner_fatal | playlist=- | attempt=6, throwable=UnknownHostException: ...
1771500526677 | scanner_finish | playlist=- | attempt=6, durationMs=21243, status=ERROR, results=0
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional


LINE_RE = re.compile(
    r"^\s*(?P<ts>\d+)\s*\|\s*(?P<status>[a-zA-Z0-9_]+)\s*\|\s*playlist=(?P<playlist>[^|]*)\|\s*(?P<msg>.*)$"
)
ATTEMPT_RE = re.compile(r"attempt=(\d+)")
QUERY_RE = re.compile(r"query=([^,|]+)")
PROVIDER_RE = re.compile(r"provider=([A-Z_]+)")
MODE_RE = re.compile(r"mode=([A-Z_]+)")
PRESET_RE = re.compile(r"preset=([a-zA-Z0-9_\\-]+)")
FOUND_RE = re.compile(r"(?:results=|Найдено\s+)(\d+)")


@dataclass
class AttemptState:
    attempt_id: int
    started_at: int = 0
    query: str = ""
    provider: str = "ALL"
    mode: str = "AUTO"
    preset: str = "-"
    found: int = 0
    errors: List[str] = field(default_factory=list)
    statuses: List[str] = field(default_factory=list)
    raw_messages: List[str] = field(default_factory=list)


def parse_attempt_id(message: str) -> Optional[int]:
    match = ATTEMPT_RE.search(message)
    if not match:
        return None
    return int(match.group(1))


def classify_error(message: str) -> str:
    lowered = message.lower()
    if "unknownhostexception" in lowered or "unable to resolve host" in lowered:
        return "dns"
    if "timeout" in lowered or "timed out" in lowered:
        return "timeout"
    if "http 429" in lowered or "rate" in lowered:
        return "rate_limit"
    if "http 403" in lowered:
        return "http_forbidden"
    if "http 401" in lowered:
        return "http_unauthorized"
    if "ssl" in lowered:
        return "tls_ssl"
    if "network io" in lowered:
        return "network_io"
    return "other"


def extract_first(pattern: re.Pattern[str], text: str, default: str = "") -> str:
    match = pattern.search(text)
    if not match:
        return default
    return match.group(1).strip()


def parse_found_count(message: str) -> int:
    match = FOUND_RE.search(message)
    if not match:
        return 0
    try:
        return int(match.group(1))
    except ValueError:
        return 0


def normalize_query(raw: str) -> str:
    return re.sub(r"\s+", " ", raw.strip()).strip(" ,.;")


def parse_lines(lines: List[str]) -> List[dict]:
    attempts: Dict[int, AttemptState] = {}
    emitted: List[dict] = []

    for line in lines:
        m = LINE_RE.match(line)
        if not m:
            continue

        ts = int(m.group("ts"))
        status = m.group("status").strip()
        msg = m.group("msg").strip()

        attempt_id = parse_attempt_id(msg)

        if status == "scanner_start":
            if attempt_id is None:
                continue
            query = normalize_query(extract_first(QUERY_RE, msg))
            provider = extract_first(PROVIDER_RE, msg, default="ALL")
            mode = extract_first(MODE_RE, msg, default="AUTO")
            preset = extract_first(PRESET_RE, msg, default="-")
            attempts[attempt_id] = AttemptState(
                attempt_id=attempt_id,
                started_at=ts,
                query=query,
                provider=provider,
                mode=mode,
                preset=preset,
                statuses=[status],
                raw_messages=[msg],
            )
            continue

        if attempt_id is None or attempt_id not in attempts:
            continue

        state = attempts[attempt_id]
        state.statuses.append(status)
        state.raw_messages.append(msg)

        if status in {"scanner_ok", "scanner_finish", "scanner_step_ok"}:
            state.found = max(state.found, parse_found_count(msg))

        if status in {
            "scanner_error",
            "scanner_fatal",
            "scanner_step_error",
            "scanner_provider_error",
            "scanner_provider_timeout",
            "scanner_fail_fast",
            "scanner_timeout",
        }:
            state.errors.append(msg)

        if status == "scanner_finish":
            label = "success" if state.found > 0 else ("failed" if state.errors else "empty")
            primary_error = state.errors[-1] if state.errors else ""
            error_type = classify_error(primary_error) if primary_error else ""
            emitted.append(
                {
                    "attempt": state.attempt_id,
                    "started_at": state.started_at,
                    "finished_at": ts,
                    "query": state.query,
                    "provider": state.provider,
                    "mode": state.mode,
                    "preset": state.preset,
                    "found": state.found,
                    "label": label,
                    "error_type": error_type,
                    "error": primary_error[:1000],
                    "statuses": state.statuses,
                }
            )
            attempts.pop(attempt_id, None)

    return emitted


def build_summary(entries: List[dict]) -> str:
    if not entries:
        return "# AI training summary\n\nНет завершённых попыток scanner_finish в логе.\n"

    total = len(entries)
    successes = [x for x in entries if x["label"] == "success"]
    failures = [x for x in entries if x["label"] == "failed"]
    empties = [x for x in entries if x["label"] == "empty"]

    success_queries = Counter(x["query"] for x in successes if x["query"])
    fail_types = Counter(x["error_type"] for x in failures if x["error_type"])
    providers = Counter(x["provider"] for x in entries)
    modes = Counter(x["mode"] for x in entries)

    lines = [
        "# AI training summary",
        "",
        f"- Всего попыток: {total}",
        f"- Успешных (found>0): {len(successes)}",
        f"- Пустых (found=0, без явной ошибки): {len(empties)}",
        f"- Ошибок: {len(failures)}",
        "",
        "## Топ успешных запросов",
    ]
    if success_queries:
        for query, count in success_queries.most_common(10):
            lines.append(f"- `{query}`: {count}")
    else:
        lines.append("- нет данных")

    lines += [
        "",
        "## Частые причины ошибок",
    ]
    if fail_types:
        for reason, count in fail_types.most_common(10):
            lines.append(f"- `{reason}`: {count}")
    else:
        lines.append("- нет данных")

    lines += [
        "",
        "## Распределение по provider",
    ]
    for provider, count in providers.most_common():
        lines.append(f"- `{provider}`: {count}")

    lines += [
        "",
        "## Распределение по mode",
    ]
    for mode, count in modes.most_common():
        lines.append(f"- `{mode}`: {count}")

    lines += [
        "",
        "## Рекомендация по обучению",
        "- Используйте только запросы с `label=success` для пополнения шаблонов `LocalAiQueryAssistant`.",
        "- Для `dns/timeout` не обучайте AI-формулировки, сначала исправьте сеть/DNS/прокси.",
        "- Новые AI-варианты добавляйте партиями по 3-5 и проверяйте успех до/после.",
    ]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Build scanner AI training dataset from exported logs")
    parser.add_argument("--input", required=True, help="Path to exported logs txt")
    parser.add_argument(
        "--out-jsonl",
        default="tools/ai/training_dataset.jsonl",
        help="Output JSONL path"
    )
    parser.add_argument(
        "--out-summary",
        default="tools/ai/training_summary.md",
        help="Output Markdown summary path"
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        raise SystemExit(f"Input file not found: {input_path}")

    lines = input_path.read_text(encoding="utf-8", errors="replace").splitlines()
    entries = parse_lines(lines)

    out_jsonl = Path(args.out_jsonl)
    out_jsonl.parent.mkdir(parents=True, exist_ok=True)
    with out_jsonl.open("w", encoding="utf-8") as f:
        for row in entries:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    out_summary = Path(args.out_summary)
    out_summary.parent.mkdir(parents=True, exist_ok=True)
    out_summary.write_text(build_summary(entries), encoding="utf-8")

    print(f"Done. Parsed attempts: {len(entries)}")
    print(f"JSONL: {out_jsonl}")
    print(f"Summary: {out_summary}")


if __name__ == "__main__":
    main()
