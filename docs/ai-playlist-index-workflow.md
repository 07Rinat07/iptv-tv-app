# AI Playlist Index Workflow

This workflow keeps experimental indexing and vector search outside the Android
app. The app imports a prepared JSONL file in Diagnostics and validates each URL
before adding it to playlists.

## 1. Crawl candidates

Create a seed file:

```text
https://example.com/iptv-links
https://github.com/search?q=iptv+m3u
```

Run the crawler:

```bash
python tools/ai/playlist_crawler.py \
  --seeds seeds.txt \
  --out tools/ai/found_playlists.txt \
  --max-depth 2 \
  --same-host
```

## 2. Probe and create JSONL

```bash
python tools/ai/playlist_indexer.py \
  --input tools/ai/found_playlists.txt \
  --out tools/ai/indexed_playlists.jsonl
```

The JSONL file is the handoff format for the Android app. Each line contains
metadata such as URL, host, HTTP status, content type, sample text and EXTINF
count.

## 3. Build SQLite ranking index

```bash
python tools/ai/playlist_db.py \
  --db tools/ai/playlists.db \
  --import-jsonl tools/ai/indexed_playlists.jsonl

python tools/ai/playlist_db.py \
  --db tools/ai/playlists.db \
  --top 50 \
  --export-csv tools/ai/top_playlists.csv
```

Use this for deterministic ranking by HTTP status and M3U density.

## 4. Optional vector search PoC

```bash
python tools/ai/playlist_vector_search.py \
  --db tools/ai/playlists_vectors.db \
  --build-jsonl tools/ai/indexed_playlists.jsonl

python tools/ai/playlist_vector_search.py \
  --db tools/ai/playlists_vectors.db \
  --query "russian sport hd" \
  --top 20
```

This uses local hashing vectors, not a neural model. It is useful as a cheap
baseline for assistant-style ranking without heavy dependencies.

## 5. Import into Android Diagnostics

1. Build and open the app.
2. Go to `Диагностика`.
3. Open the `Индекс` tab.
4. Press `Импорт JSONL`.
5. Select `indexed_playlists.jsonl`.
6. Filter by host, HTTP 200 and minimum EXTINF count.
7. Press `Проверить и добавить` on a candidate.

Diagnostics validates the URL before importing, so the app does not blindly
trust the offline crawler output.

## PR split suggestion

- `tools/ai`: crawler/indexer/SQLite/vector-search PoC and workflow docs.
- `diagnostics`: tabbed Diagnostics UI, JSONL import, filters and assistant PoC.
- `player/logging`: player recovery/log export and shared `core:utils` logger.
- `build`: JDK 17 build note and Gradle/module wiring.
