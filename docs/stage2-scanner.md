# Stage 2: Scanner Module

## Scope
Implemented production-oriented scanner baseline for public repository search:
- providers: GitHub, GitLab, Bitbucket;
- filter model and request normalization;
- resilient networking with retry/backoff;
- rate-limit awareness;
- query cache;
- feature UI integration.

## Core decisions
- Keep scanner logic in `core:network` data source, expose through `ScannerRepository` in `core:data`.
- Use local post-filtering to keep behavior consistent across providers with different API capabilities.
- Keep cache in-memory with TTL for fast repeated scans and API quota protection.

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

## Known limitations (next stage)
- Bitbucket public API coverage for global code search is weaker than GitHub/GitLab.
- Provider-specific advanced filters (true server-side date/size) should be deepened in Stage 3.
- Scanner result persistence/history is not yet added.
