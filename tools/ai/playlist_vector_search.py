#!/usr/bin/env python3
"""Local vector-search PoC for indexed IPTV playlists.

This tool intentionally uses only Python's standard library. It builds small
hashing-based embeddings from URL/host/sample text and stores them in SQLite.
It is not a neural embedding model, but it gives a deterministic local vector
search baseline without adding heavy dependencies to the Android app.

Usage:
  python tools/ai/playlist_vector_search.py --db tools/ai/playlists_vectors.db --build-jsonl tools/ai/indexed_playlists.jsonl
  python tools/ai/playlist_vector_search.py --db tools/ai/playlists_vectors.db --query "russian sport hd" --top 20
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sqlite3
from typing import Dict, Iterable, List, Tuple

TOKEN_RE = re.compile(r"[a-zа-я0-9][a-zа-я0-9._:-]{1,}", re.IGNORECASE)
DEFAULT_DIMENSIONS = 384
MAX_SAMPLE_CHARS = 3000


def ensure_db(path: str) -> sqlite3.Connection:
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS playlist_vectors (
            url TEXT PRIMARY KEY,
            host TEXT,
            status INTEGER,
            content_type TEXT,
            m3u_entries INTEGER,
            text TEXT,
            vector_json TEXT,
            checked_at INTEGER
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_playlist_vectors_host ON playlist_vectors(host)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_playlist_vectors_entries ON playlist_vectors(m3u_entries)")
    conn.commit()
    return conn


def iter_tokens(text: str) -> Iterable[str]:
    lowered = text.lower()
    for match in TOKEN_RE.finditer(lowered):
        token = match.group(0).strip("._:-")
        if len(token) >= 2:
            yield token


def token_weight(token: str) -> float:
    if token in {"http", "https", "www", "m3u", "m3u8", "playlist", "index"}:
        return 0.35
    if token.endswith(".m3u") or token.endswith(".m3u8"):
        return 1.8
    return 1.0


def hash_bucket(token: str, dimensions: int) -> int:
    digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, "big") % dimensions


def build_text(row: dict) -> str:
    parts = [
        row.get("url") or "",
        row.get("host") or "",
        row.get("content_type") or "",
        row.get("sample") or "",
    ]
    return " ".join(parts)[:MAX_SAMPLE_CHARS]


def embed_text(text: str, dimensions: int = DEFAULT_DIMENSIONS) -> Dict[int, float]:
    vector: Dict[int, float] = {}
    for token in iter_tokens(text):
        bucket = hash_bucket(token, dimensions)
        vector[bucket] = vector.get(bucket, 0.0) + token_weight(token)

    norm = math.sqrt(sum(value * value for value in vector.values()))
    if norm <= 0:
        return {}
    return {bucket: value / norm for bucket, value in vector.items()}


def cosine(left: Dict[int, float], right: Dict[int, float]) -> float:
    if not left or not right:
        return 0.0
    if len(left) > len(right):
        left, right = right, left
    return sum(value * right.get(bucket, 0.0) for bucket, value in left.items())


def vector_to_json(vector: Dict[int, float]) -> str:
    return json.dumps([[bucket, round(value, 6)] for bucket, value in sorted(vector.items())])


def vector_from_json(raw: str) -> Dict[int, float]:
    try:
        return {int(bucket): float(value) for bucket, value in json.loads(raw)}
    except Exception:
        return {}


def import_jsonl(conn: sqlite3.Connection, jsonl_path: str, dimensions: int) -> int:
    inserted = 0
    with open(jsonl_path, "r", encoding="utf-8") as file:
        rows: List[Tuple] = []
        for line in file:
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue

            url = obj.get("url") or obj.get("uri")
            if not url:
                continue
            text = build_text(obj)
            vector = embed_text(text, dimensions=dimensions)
            rows.append(
                (
                    url,
                    obj.get("host"),
                    obj.get("status"),
                    obj.get("content_type"),
                    int(obj.get("m3u_entries") or 0),
                    text,
                    vector_to_json(vector),
                    int(obj.get("checked_at") or 0),
                )
            )
            if len(rows) >= 200:
                inserted += upsert_rows(conn, rows)
                rows = []
        if rows:
            inserted += upsert_rows(conn, rows)
    return inserted


def upsert_rows(conn: sqlite3.Connection, rows: List[Tuple]) -> int:
    conn.executemany(
        """
        INSERT INTO playlist_vectors(url, host, status, content_type, m3u_entries, text, vector_json, checked_at)
        VALUES(?,?,?,?,?,?,?,?)
        ON CONFLICT(url) DO UPDATE SET
            host=excluded.host,
            status=excluded.status,
            content_type=excluded.content_type,
            m3u_entries=excluded.m3u_entries,
            text=excluded.text,
            vector_json=excluded.vector_json,
            checked_at=excluded.checked_at
        """,
        rows,
    )
    conn.commit()
    return len(rows)


def search(conn: sqlite3.Connection, query: str, top: int, dimensions: int, host: str | None = None):
    query_vector = embed_text(query, dimensions=dimensions)
    sql = """
        SELECT url, host, status, content_type, m3u_entries, vector_json
        FROM playlist_vectors
    """
    params: Tuple = ()
    if host:
        sql += " WHERE host = ?"
        params = (host,)

    scored = []
    for url, row_host, status, content_type, m3u_entries, vector_raw in conn.execute(sql, params):
        score = cosine(query_vector, vector_from_json(vector_raw))
        quality_boost = min(int(m3u_entries or 0), 500) / 5000.0
        status_boost = 0.03 if status == 200 else 0.0
        scored.append((score + quality_boost + status_boost, url, row_host, status, content_type, m3u_entries))

    scored.sort(reverse=True, key=lambda row: row[0])
    return scored[:top]


def main() -> None:
    parser = argparse.ArgumentParser(description="Local hashing-vector search for indexed IPTV playlists")
    parser.add_argument("--db", required=True, help="SQLite DB path for vector index")
    parser.add_argument("--build-jsonl", help="Import JSONL produced by playlist_indexer.py")
    parser.add_argument("--query", help="Search query, for example 'russian sports hd'")
    parser.add_argument("--host", help="Optional host filter")
    parser.add_argument("--top", type=int, default=20, help="Number of results to show")
    parser.add_argument("--dimensions", type=int, default=DEFAULT_DIMENSIONS, help="Hashing vector dimensions")
    args = parser.parse_args()

    conn = ensure_db(args.db)

    if args.build_jsonl:
        count = import_jsonl(conn, args.build_jsonl, dimensions=args.dimensions)
        print(f"Indexed vectors: {count} rows -> {args.db}")

    if args.query:
        rows = search(conn, args.query, top=args.top, dimensions=args.dimensions, host=args.host)
        for score, url, host, status, content_type, m3u_entries in rows:
            print(f"{score:0.4f} | extinf={m3u_entries:4} | http={status or '-':3} | {host or '-':30} | {url}")


if __name__ == "__main__":
    main()
