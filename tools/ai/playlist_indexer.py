#!/usr/bin/env python3
"""Simple playlist indexer PoC

Takes a file with playlist URLs (one per line), deduplicates, probes each URL (HEAD/GET),
attempts to fetch a small sample and detect M3U playlists by counting `#EXTINF` lines.
Outputs a JSONL file with metadata per URL.

Usage:
    python tools/ai/playlist_indexer.py --input tools/ai/found_playlists.txt --out tools/ai/indexed_playlists.jsonl
"""
from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from urllib.parse import urlparse

import requests

SAMPLE_BYTES = 64 * 1024  # 64 KB


def probe_url(url: str, timeout: int = 10):
    result = {
        "url": url,
        "host": None,
        "status": None,
        "content_type": None,
        "content_length": None,
        "is_m3u": False,
        "m3u_entries": 0,
        "sample": None,
        "checked_at": int(time.time() * 1000),
    }
    try:
        parsed = urlparse(url)
        result["host"] = parsed.hostname
    except Exception:
        pass

    try:
        # Try HEAD first
        h = requests.head(url, timeout=timeout, allow_redirects=True, headers={"User-Agent": "myscanerIPTV-indexer/0.1"})
        result["status"] = h.status_code
        result["content_type"] = h.headers.get("Content-Type")
        result["content_length"] = h.headers.get("Content-Length")
        if h.status_code >= 400 or (h.headers.get("Content-Type") and "text" in h.headers.get("Content-Type", "").lower()):
            # fallback to GET sample
            pass
    except Exception:
        # HEAD may fail for some servers; continue to GET
        pass

    try:
        g = requests.get(url, timeout=timeout, stream=True, headers={"User-Agent": "myscanerIPTV-indexer/0.1"})
        result["status"] = g.status_code
        result["content_type"] = g.headers.get("Content-Type")
        result["content_length"] = g.headers.get("Content-Length")
        # read sample
        chunk = b""
        for data in g.iter_content(chunk_size=8192):
            if not data:
                break
            chunk += data
            if len(chunk) >= SAMPLE_BYTES:
                break
        try:
            text = chunk.decode("utf-8", errors="replace")
        except Exception:
            text = chunk.decode(errors="replace")
        result["sample"] = text[:8192]
        # detect m3u entries
        entries = sum(1 for _ in (line for line in text.splitlines() if line.strip().lower().startswith("#extinf")))
        result["m3u_entries"] = entries
        result["is_m3u"] = entries > 0 or (result["content_type"] and "mpegurl" in result["content_type"].lower())
    except Exception as e:
        result["error"] = str(e)
    return result


def index_file(input_path: str, out_path: str):
    raw = [l.strip() for l in open(input_path, "r", encoding="utf-8") if l.strip()]
    unique = []
    seen = set()
    for u in raw:
        if u in seen:
            continue
        seen.add(u)
        unique.append(u)

    with open(out_path, "w", encoding="utf-8") as out:
        for url in unique:
            meta = probe_url(url)
            out.write(json.dumps(meta, ensure_ascii=False) + "\n")
    return out_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--out", default="tools/ai/indexed_playlists.jsonl")
    args = parser.parse_args()

    out = index_file(args.input, args.out)
    print(f"Indexed -> {out} (generated at {datetime.utcnow().isoformat()}Z)")


if __name__ == "__main__":
    main()
