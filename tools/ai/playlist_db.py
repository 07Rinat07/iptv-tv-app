#!/usr/bin/env python3
"""Playlist SQLite indexer and query tool.

Usage examples:
  # Import JSONL produced by playlist_indexer
  python tools/ai/playlist_db.py --import-jsonl tools/ai/indexed_playlists.jsonl --db tools/ai/playlists.db

  # List top candidates
  python tools/ai/playlist_db.py --db tools/ai/playlists.db --top 50

  # Query by host
  python tools/ai/playlist_db.py --db tools/ai/playlists.db --host example.com

This is a simple PoC for local indexing of discovered playlists.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
from datetime import datetime
from typing import Optional
from urllib.parse import urlparse, urlunparse, parse_qsl

EPHEMERAL_PARAMS = {
    'session', 'sid', 'token', 'auth', 'jwt', 'ts', 'timestamp', 'utm_source', 'utm_medium', 'utm_campaign'
}

NORMALIZE_REMOVE_EMPTY = True


def normalize_url(url: str) -> str:
    try:
        parts = urlparse(url)
        qs = parse_qsl(parts.query, keep_blank_values=True)
        filtered = [(k, v) for (k, v) in qs if k.lower() not in EPHEMERAL_PARAMS]
        query = '&'.join(f"{k}={v}" for k, v in filtered)
        # Optionally remove empty query
        if NORMALIZE_REMOVE_EMPTY and not query:
            query = ''
        new = parts._replace(query=query)
        return urlunparse(new)
    except Exception:
        return url.strip()


def ensure_db(path: str) -> sqlite3.Connection:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path)
    c = conn.cursor()
    c.execute('''
        CREATE TABLE IF NOT EXISTS playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT UNIQUE,
            normalized_url TEXT,
            host TEXT,
            status INTEGER,
            content_type TEXT,
            content_length TEXT,
            is_m3u INTEGER,
            m3u_entries INTEGER,
            sample TEXT,
            error TEXT,
            checked_at INTEGER
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_playlists_normalized ON playlists(normalized_url)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_playlists_host ON playlists(host)')
    conn.commit()
    return conn


def import_jsonl(conn: sqlite3.Connection, jsonl_path: str, batch: int = 100):
    c = conn.cursor()
    inserted = 0
    with open(jsonl_path, 'r', encoding='utf-8') as f:
        rows = []
        for line in f:
            try:
                obj = json.loads(line)
            except Exception:
                continue
            url = obj.get('url') or obj.get('uri')
            if not url:
                continue
            normalized = normalize_url(url)
            host = obj.get('host')
            status = obj.get('status')
            content_type = obj.get('content_type')
            content_length = obj.get('content_length')
            is_m3u = 1 if obj.get('is_m3u') else 0
            m3u_entries = int(obj.get('m3u_entries') or 0)
            sample = (obj.get('sample') or '')[:4000]
            error = obj.get('error')
            checked_at = int(obj.get('checked_at') or 0)
            rows.append((url, normalized, host, status, content_type, content_length, is_m3u, m3u_entries, sample, error, checked_at))
            if len(rows) >= batch:
                c.executemany('''
                    INSERT OR IGNORE INTO playlists(url, normalized_url, host, status, content_type, content_length, is_m3u, m3u_entries, sample, error, checked_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ''', rows)
                conn.commit()
                inserted += len(rows)
                rows = []
        if rows:
            c.executemany('''
                INSERT OR IGNORE INTO playlists(url, normalized_url, host, status, content_type, content_length, is_m3u, m3u_entries, sample, error, checked_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ''', rows)
            conn.commit()
            inserted += len(rows)
    return inserted


def top_candidates(conn: sqlite3.Connection, limit: int = 50):
    c = conn.cursor()
    # Rank by m3u_entries desc, status 200 first, then by checked_at desc
    q = '''
    SELECT url, normalized_url, host, status, content_type, m3u_entries, checked_at
    FROM playlists
    WHERE status IS NOT NULL
    ORDER BY m3u_entries DESC, CASE WHEN status=200 THEN 0 ELSE 1 END, checked_at DESC
    LIMIT ?
    '''
    c.execute(q, (limit,))
    return c.fetchall()


def query_host(conn: sqlite3.Connection, host: str, limit: int = 100):
    c = conn.cursor()
    c.execute('SELECT url, normalized_url, status, content_type, m3u_entries FROM playlists WHERE host=? ORDER BY m3u_entries DESC, checked_at DESC LIMIT ?', (host, limit))
    return c.fetchall()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--db', required=True, help='Path to sqlite DB')
    parser.add_argument('--import-jsonl', help='Import JSONL file produced by playlist_indexer')
    parser.add_argument('--top', type=int, help='Show top N candidates')
    parser.add_argument('--host', help='Filter by host')
    parser.add_argument('--export-csv', help='Export top candidates to CSV file')
    args = parser.parse_args()

    conn = ensure_db(args.db)
    if args.import_jsonl:
        count = import_jsonl(conn, args.import_jsonl)
        print(f'Imported approx {count} entries into {args.db}')

    if args.top:
        rows = top_candidates(conn, limit=args.top)
        for r in rows:
            url, normalized, host, status, content_type, m3u_entries, checked_at = r
            ts = datetime.utcfromtimestamp(checked_at/1000).isoformat()+'Z' if checked_at else '-'
            print(f'{m3u_entries:4d} | {status or "-":3} | {host or "-":30} | {ts} | {url}')
        if args.export_csv:
            import csv
            rows = top_candidates(conn, limit=args.top)
            with open(args.export_csv, 'w', encoding='utf-8', newline='') as csvf:
                w = csv.writer(csvf)
                w.writerow(['m3u_entries','status','host','checked_at','url'])
                for r in rows:
                    url, normalized, host, status, content_type, m3u_entries, checked_at = r
                    ts = datetime.utcfromtimestamp(checked_at/1000).isoformat()+'Z' if checked_at else ''
                    w.writerow([m3u_entries, status, host, ts, url])
            print(f'Exported CSV to {args.export_csv}')

    if args.host:
        rows = query_host(conn, args.host)
        for r in rows:
            url, normalized, status, content_type, m3u_entries = r
            print(f'{m3u_entries:4d} | {status or "-":3} | {content_type or "-":20} | {url}')


if __name__ == '__main__':
    main()
