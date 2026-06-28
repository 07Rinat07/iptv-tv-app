# Stage 2: Scanner Module

## Scope
Implemented production-oriented scanner baseline for public repository search:
- providers: GitHub, GitLab, Bitbucket;
- web search fallback: DuckDuckGo, Bing, Google and Yandex;
- filter model and request normalization;
- resilient networking with retry/backoff;
- rate-limit awareness;
- query cache;
- feature UI integration.

## Core decisions
- Keep scanner logic in `core:network` data source, expose through `ScannerRepository` in `core:data`.
- Use local post-filtering to keep behavior consistent across providers with different API capabilities.
- Keep cache in-memory with TTL for fast repeated scans and API quota protection.
- In `Auto` mode, do not fail fast after an early provider timeout. Continue to fallback providers and web search unless the user explicitly selected a direct single-provider API scan.
- Keep explicit `.m3u` and `.m3u8` candidates discovered by search engines even when the quick probe is blocked or times out; playlist import and validation perform the final check.
- Regional search expansion covers Kazakhstan, Russian, Turkish and world-channel queries, plus known `iptv-org` country seeds for Kazakhstan and Turkey.

## Implemented files
- `core/model/src/main/kotlin/com/iptv/tv/core/model/Models.kt`
- `core/domain/src/main/kotlin/com/iptv/tv/core/domain/repository/ScannerRepository.kt`
- `core/network/src/main/java/com/iptv/tv/core/network/api/ScannerApis.kt`
- `core/network/src/main/java/com/iptv/tv/core/network/datasource/PublicRepositoryScannerDataSource.kt`
- `core/data/src/main/java/com/iptv/tv/core/data/repository/Repositories.kt`
- `feature/scanner/src/main/java/com/iptv/tv/feature/scanner/ScannerViewModel.kt`
- `feature/scanner/src/main/java/com/iptv/tv/feature/scanner/ScannerScreen.kt`

## Verification
- Unit/integration tests:
  - `core/network/src/test/java/com/iptv/tv/core/network/datasource/PublicRepositoryScannerDataSourceTest.kt`
  - `core/data/src/test/java/com/iptv/tv/core/data/ScannerRepositoryImplTest.kt`
- Successful build checks:
  - `:core:network:test`
  - `:core:data:test`
  - `:app:assembleDebug`

## Operational notes

- Search engines can change markup or rate-limit TV-box networks. The scanner therefore uses several engines and known seed sources instead of relying on one provider.
- Wider web search can return dead or duplicate links. This is expected; import validation and manual cleanup should remove unusable streams.

## Known limitations
- Bitbucket public API coverage for global code search is weaker than GitHub/GitLab.
- Provider-specific advanced filters (true server-side date/size) should be deepened in Stage 3.
- Scanner result persistence/history is not yet added.
