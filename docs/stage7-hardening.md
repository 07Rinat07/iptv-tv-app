# Stage 7: Performance Hardening, Stability, Acceptance Smoke

## Delivered
- Database performance hardening:
  - added indices for hot paths in `playlists`, `channels`, `history`, `sync_logs`, `downloads`;
  - upgraded Room schema version to `2`;
  - optimized playlist listing query to return playlist + visible channel count in one SQL query.
- Sync pipeline hardening:
  - `PlaylistRepository` extended with `refreshAllPlaylists()`;
  - `PlaylistSyncWorker` now refreshes either one playlist or all playlists when ID is absent;
  - worker writes start/success/error logs into diagnostics stream and returns refreshed count in output data;
  - `SyncScheduler` now applies:
    - normalized 6/12/24h interval policy,
    - `CONNECTED` network requirement,
    - `batteryNotLow` requirement,
    - exponential backoff.
- Runtime stability diagnostics:
  - diagnostics screen shows runtime memory/uptime summary and periodic updates.
- Acceptance/smoke reinforcement:
  - parser stress test for `8,100` channels (`M3U` large list scenario);
  - expanded app smoke navigation test across route chain (`scanner -> importer -> playlists -> player -> settings -> diagnostics`);
  - sync scheduler unit test for interval normalization.

## Key Files
- `core/database/src/main/java/com/iptv/tv/core/database/entity/Entities.kt`
- `core/database/src/main/java/com/iptv/tv/core/database/IptvDatabase.kt`
- `core/database/src/main/java/com/iptv/tv/core/database/dao/Daos.kt`
- `core/domain/src/main/kotlin/com/iptv/tv/core/domain/repository/PlaylistRepository.kt`
- `core/data/src/main/java/com/iptv/tv/core/data/repository/Repositories.kt`
- `sync/src/main/java/com/iptv/tv/sync/SyncScheduler.kt`
- `sync/src/main/java/com/iptv/tv/sync/worker/PlaylistSyncWorker.kt`
- `feature/diagnostics/src/main/java/com/iptv/tv/feature/diagnostics/DiagnosticsViewModel.kt`
- `feature/diagnostics/src/main/java/com/iptv/tv/feature/diagnostics/DiagnosticsScreen.kt`
- `core/parser/src/test/kotlin/com/iptv/tv/core/parser/M3uParserTest.kt`
- `sync/src/test/java/com/iptv/tv/sync/SyncSchedulerTest.kt`
- `app/src/androidTest/java/com/iptv/tv/AppSmokeTest.kt`

## Verification
- `:core:parser:test`
- `:sync:test`
- `:core:engine:test`
- `:core:data:test`
- `:feature:diagnostics:assembleDebug`
- `:feature:player:assembleDebug`
- `:feature:settings:assembleDebug`
- `:app:assembleDebug`

## Notes
- Instrumentation smoke test file is updated, but running `androidTest` requires an emulator/device in the environment.
- DB version bump uses existing destructive fallback policy from `DatabaseModule`; this avoids crashes on schema mismatch during dev iterations.
