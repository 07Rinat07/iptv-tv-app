# Stage 3: Import Pipeline

## Scope
Implemented full import baseline for playlist ingestion and channel health validation.

## Implemented capabilities
- Input modes:
  - URL import (`http/https`)
  - Raw M3U text import
  - Local file import (`path` or `content://` URI)
- M3U validation:
  - requires `#EXTM3U` header
  - parses `#EXTINF` metadata and channel URLs
  - validates URL-like schemes (`http`, `https`, `rtsp`, `rtmp`, `udp`, `magnet`, `acestream`, etc.)
  - reports warnings for broken records (missing URL, invalid URL, URL without `#EXTINF`)
- Deduplication policy:
  1. by normalized stream URL
  2. then by `tvg-id + name` (when `tvg-id` exists)
- Large playlist handling:
  - chunked DB writes (`500` rows per batch)
- Health checks:
  - `HEAD` probe with fallback `GET Range`
  - timeout and retry policy
  - status/content-type evaluation
  - persisted channel health states (`AVAILABLE`, `UNSTABLE`, `UNAVAILABLE`)
- UI integration:
  - `feature:importer` now supports all 3 import modes, shows import report and manual `Проверить` action.

## Key files
- `core/domain/src/main/kotlin/com/iptv/tv/core/domain/repository/PlaylistRepository.kt`
- `core/model/src/main/kotlin/com/iptv/tv/core/model/Models.kt`
- `core/parser/src/main/kotlin/com/iptv/tv/core/parser/M3uParser.kt`
- `core/database/src/main/java/com/iptv/tv/core/database/dao/Daos.kt`
- `core/data/src/main/java/com/iptv/tv/core/data/repository/Repositories.kt`
- `feature/importer/src/main/java/com/iptv/tv/feature/importer/ImporterViewModel.kt`
- `feature/importer/src/main/java/com/iptv/tv/feature/importer/ImporterScreen.kt`

## Verification
- `:core:parser:test`
- `:core:data:test`
- `:feature:importer:assembleDebug`
- `:app:assembleDebug`

## Notes
- Health checks are network-dependent; non-HTTP streams are marked as `UNSTABLE` by default.
- Provider/source-specific advanced import normalization can be extended in Stage 4+.
