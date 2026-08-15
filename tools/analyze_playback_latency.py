#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from playback_latency import analyze_file, render_csv, render_json, render_table


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Analyze a Diagnostics TXT export and correlate primary playback requests with "
            "resolve, localhost loopback, Media3 buffering/READY/first-frame timing."
        )
    )
    parser.add_argument("log_file", type=Path, help="Diagnostics TXT export")
    parser.add_argument("--csv", type=Path, help="Also write per-request CSV")
    parser.add_argument("--json", type=Path, help="Also write machine-readable JSON")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    analysis = analyze_file(args.log_file)
    print(render_table(analysis))

    if args.csv:
        args.csv.write_text(render_csv(analysis), encoding="utf-8")
    if args.json:
        args.json.write_text(render_json(analysis), encoding="utf-8")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
