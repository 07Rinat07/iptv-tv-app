# Stage 6: Engine Stream + Torrent Bridge + Diagnostics

## Delivered
- Implemented Engine Stream runtime client in `core:engine`:
  - dynamic endpoint connection (`connect`);
  - status refresh (`refreshStatus`);
  - torrent descriptor resolve (`resolveStream`) with response parsing and fallback stream URL.
- Engine repository contract extended:
  - added `refreshStatus()` for diagnostics refresh loops.
- Player integrated with Engine Stream:
  - detects torrent descriptors (`magnet`, `acestream`, `ace`, `.torrent`);
  - auto-connects to configured engine endpoint;
  - resolves descriptor to playable URL before internal/VLC playback;
  - keeps VLC fallback behavior and startup retry logic.
- Settings extended for Stage 6:
  - persisted engine endpoint;
  - optional Tor mode switch (`on/off`);
  - existing player/buffer settings preserved.
- Diagnostics module upgraded:
  - Hilt-enabled ViewModel + real UI;
  - network summary from `ConnectivityManager`;
  - live engine status (`connected`, `peers`, `speed`, `message`);
  - actions: connect engine, refresh status, resolve descriptor, refresh network;
  - DB-backed logs stream (`sync_logs`) via `DiagnosticsRepository`.
- Data layer extended:
  - `DiagnosticsRepository` added;
  - `SyncLogEntity -> SyncLog` mapper added;
  - engine connect/resolve success/errors are written to logs.

## Key Files
- `core/engine/src/main/java/com/iptv/tv/core/engine/data/EngineStreamClient.kt`
- `core/engine/src/main/java/com/iptv/tv/core/engine/api/EngineStreamApi.kt`
- `core/domain/src/main/kotlin/com/iptv/tv/core/domain/repository/EngineRepository.kt`
- `core/domain/src/main/kotlin/com/iptv/tv/core/domain/repository/DiagnosticsRepository.kt`
- `core/domain/src/main/kotlin/com/iptv/tv/core/domain/repository/SettingsRepository.kt`
- `core/data/src/main/java/com/iptv/tv/core/data/repository/Repositories.kt`
- `core/data/src/main/java/com/iptv/tv/core/data/di/RepositoryModule.kt`
- `feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt`
- `feature/diagnostics/src/main/java/com/iptv/tv/feature/diagnostics/DiagnosticsViewModel.kt`
- `feature/diagnostics/src/main/java/com/iptv/tv/feature/diagnostics/DiagnosticsScreen.kt`
- `feature/settings/src/main/java/com/iptv/tv/feature/settings/SettingsViewModel.kt`
- `feature/settings/src/main/java/com/iptv/tv/feature/settings/SettingsScreen.kt`
- `app/src/main/AndroidManifest.xml`

## Tests and Verification
- Unit tests:
  - `core/engine/src/test/java/com/iptv/tv/core/engine/data/EngineStreamClientTest.kt`
- Verified commands:
  - `:core:engine:test`
  - `:core:data:test`
  - `:feature:settings:assembleDebug`
  - `:feature:player:assembleDebug`
  - `:feature:diagnostics:assembleDebug`
  - `:app:assembleDebug`

## Notes
- Tor mode is currently a persisted routing mode switch in settings/UI; deep proxy routing is not implemented in this stage.
- Engine API payloads differ between providers; current parser is tolerant and attempts key-based extraction with fallback URL construction.
