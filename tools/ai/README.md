# PoC playlist crawler and index tools

These tools are intentionally outside the Android app. They help collect,
deduplicate, probe, rank and search public playlist candidates before importing
the best JSONL result into the app's Diagnostics screen.

## Quick workflow

1. Create `seeds.txt` with one URL per line as seed pages to scan.
2. Install lightweight crawler dependencies (prefer virtualenv):

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r tools/ai/requirements.txt
```

3. Crawl seed pages and collect candidate playlist URLs:

```bash
python tools/ai/playlist_crawler.py --seeds seeds.txt --out tools/ai/found_playlists.txt --max-depth 2 --same-host
```

4. Probe and index found playlists to JSONL:

```bash
python tools/ai/playlist_indexer.py --input tools/ai/found_playlists.txt --out tools/ai/indexed_playlists.jsonl
```

5. Import JSONL into SQLite for offline ranking/querying:

```bash
python tools/ai/playlist_db.py --db tools/ai/playlists.db --import-jsonl tools/ai/indexed_playlists.jsonl
python tools/ai/playlist_db.py --db tools/ai/playlists.db --top 50 --export-csv tools/ai/top_playlists.csv
```

6. Optional local vector-search PoC without ML dependencies:

```bash
python tools/ai/playlist_vector_search.py --db tools/ai/playlists_vectors.db --build-jsonl tools/ai/indexed_playlists.jsonl
python tools/ai/playlist_vector_search.py --db tools/ai/playlists_vectors.db --query "russian sport hd" --top 20
```

7. In the Android app, open `Диагностика -> Индекс -> Импорт JSONL` and select
   `tools/ai/indexed_playlists.jsonl`. Use filters by host/status/EXTINF count,
   then press `Проверить и добавить` for promising candidates.

The indexer produces `indexed_playlists.jsonl` with one JSON object per line
containing metadata such as `host`, `content_type`, `sample`, `m3u_entries`,
`status`, and `checked_at`.

What it does:
- Fetches seed pages, finds direct links to `.m3u`/`.m3u8` in page text or anchor hrefs.
- Optionally follows links on the same host up to `max-depth`.
- Saves discovered playlist URLs to output file.

## Tool roles

- `playlist_crawler.py`: scans seed pages and extracts playlist-like URLs.
- `playlist_indexer.py`: probes URLs and writes JSONL metadata.
- `playlist_db.py`: imports JSONL into SQLite and ranks top candidates.
- `playlist_vector_search.py`: builds deterministic hashing vectors in SQLite
  and searches by semantic-ish query text, without pulling models into Android.

## Next steps

- Add rate-limiting, robots.txt respect, and retries with backoff.
- Export vector-search results back to JSONL/CSV for easier Diagnostics import.
- Keep crawler/index/vector experiments in `tools/ai`; Android should only import
  prepared JSONL and validate URLs before adding playlists.
