# Stage 1: Architecture Scaffold

## Why Hilt
Hilt is selected as DI framework because it natively integrates with Android components, WorkManager, and scales well for a multi-module TV app with many repositories/workers.

## Module graph
- `app`: Application, root navigation, activity, DI bootstrap.
- `core:common`: Result wrappers, dispatcher contracts.
- `core:model`: Shared business models and enums.
- `core:domain`: Repository interfaces + use case contracts.
- `core:database`: Room entities/DAO/database.
- `core:network`: Retrofit APIs for GitHub/GitLab/Bitbucket and scanner data source.
- `core:parser`: M3U parser + validation baseline.
- `core:player`: Internal/VLC player contracts + buffer presets.
- `core:engine`: Engine Stream client contract/scaffold.
- `core:designsystem`: Shared Compose theme for TV UI.
- `core:data`: Repository implementations, mapping, DataStore settings.
- `sync`: WorkManager workers/scheduler.
- `feature:*`: Independent feature entry points (home/scanner/importer/playlists/editor/favorites/history/player/settings/diagnostics).

## Clean Architecture boundaries
- Domain layer does not depend on Android framework.
- Data layer implements domain interfaces and depends on network/db/parser/engine.
- UI feature modules depend on domain contracts and model types.
- App module coordinates navigation and startup orchestration only.

## Stage 1 delivered flow
Navigation skeleton includes the required core scenario path:
`Home -> Scanner -> Importer -> Playlists -> Player`.

## Next implementation stages
1. Stage 2: Real scanner logic (filters/rate-limits/cache/retry/backoff).
2. Stage 3: Import pipeline (URL/text/file) with validation + dedup + health checks.
3. Stage 4: Playlist editor (batch actions, reorder, copy-on-write).
4. Stage 5: Player runtime (Media3, VLC fallback, channel startup resilience).
5. Stage 6: Engine Stream, torrent-playback bridge, diagnostics enrichment.
6. Stage 7: Performance hardening + long-run stability + acceptance tests.
