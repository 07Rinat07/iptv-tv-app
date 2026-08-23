# Project status and roadmap

_Last updated: 2026-08-23_

This is the canonical current-state and next-action document. Dated field reports remain immutable evidence; when an older plan describes a different current increment, this page wins.

## Current integration head

- Current production `main`: `77df26cf` — PR #191 catch-up capability persistence, merged after exact-head Android CI #856, Database Unit CI #101, Player Refactor Guard #56 and resolution of the Favorites propagation review finding.
- PR #174–#187 and PR #189–#191 are merged. PR #186 continued the production StablePlayer presentation/composition split, PR #187 made XMLTV partial matching fail closed on ambiguity, PR #189 preserved explicit per-channel catch-up metadata, PR #190 added a bounded fail-closed archive URL resolver, and PR #191 persisted catch-up capability through Room/import/Ready/Favorites without Player launch wiring. PR #188 was a closed, unmerged staging attempt and is not production history.
- Catalog/Favorites, EPG and Player architecture work do not constitute new Ace Live field evidence. P2P transport decisions remain bound to the real-device evidence track described below.
- Historical merged feature branches are not production merge candidates and should be deleted after their changes are verified in `main`.

## Issue #45 — canonical catalog + autonomous unified Favorites

The catalog/data track is developed as small fresh-main increments. Scanner discovery/query semantics and P2P transport policy are protected from these changes.

### Complete

1. **PR #167 — canonical navigation contract.** Stable hierarchy path/checkpoint, breadcrumb context, predictable one-level Back and focus reconciliation after tree rebuild.
2. **PR #168 — real Playlists UI integration.** `observeChannels → LegacyPlaylistCatalogAdapter → CanonicalCatalogNavigator → UI`, exact channel route to Player, shared Android Back dispatcher and off-screen focus restore by stable `CatalogNodeId`. Exact-head Android CI #693 passed before merge.
3. **Permanent catalog regression gate.** Android CI explicitly runs `:feature:playlists:testDebugUnitTest`.
4. **PR #170 — autonomous Favorites persistence.** Database v10 stores durable logical favorite snapshots and source variants independent of source playlist/channel lifetime. Legacy favorites are safely migrated through seed snapshots and consolidated by the shared Kotlin `ChannelStableIdentity` algorithm.
5. **PR #171 — favorite playback resolver.** A single `FavoritePlaybackContext` selects a live matching variant when possible and a persisted variant/snapshot when the original source rows no longer exist. No second Player runtime was introduced.
6. **PR #172 — virtual Favorites aggregate.** A stable system-owned virtual playlist exposes durable Favorites to the existing canonical catalog and Player without creating a physical Room playlist. The representative favorite ID remains stable while playback source fields come from the best live or persisted variant. Physical refresh/delete/editor actions are disabled for this virtual list.
7. **Durability acceptance now covered.** Deleting the original playlist/channel does not delete the logical favorite; re-import can reconnect a matching logical channel; multiple source variants remain discoverable.
8. **PR #174 — versioned portable backup backend.** The application-owned RIPTV format preserves logical identity, snapshots, provenance and source variants without exporting local Room IDs as portable identity.
9. **PR #175 — safe share/export contract.** TXT/M3U8 and RIPTV exports share credential-aware redaction rules and do not silently disclose provider secrets.
10. **PR #176 — portable RIPTV import.** The Android document picker validates the bounded versioned payload before an idempotent merge/relink.
11. **PR #177 — source reconciliation.** Selectable variants are reconciled against current live rows while durable provenance remains available.
12. **PR #178 — preferred source picker.** Favorites exposes deterministic source selection on TV without duplicating the logical favorite.
13. **PR #179 — source-picker help sync.** User Guide and built-in About Help describe the merged backup/import/export and preferred-source behavior.
14. **All Channels virtual aggregate — main `66a67a6`.** A system-owned, non-destructive view preserves concrete channel/playlist IDs and the existing Player route without creating a physical playlist row.
15. **Recent/History virtual aggregate — main `03f8e8e`.** A bounded MRU view resolves current live/durable Favorite channels, preserves playback context, removes logical duplicates and reacts to hidden/parental/history changes without a Room playlist row.
16. **Catalog focus performance — main `90a285c`.** Focus-only D-pad transitions retain the prepared entries/breadcrumbs instead of rebuilding O(N) UI lists; a 10k-channel harness protects checkpoint restore and exact ordering.
17. **Non-blocking catalog rebuild — main `2d22aea`.** Canonical session creation, restore and snapshot preparation run on the injected Default dispatcher; conflated emissions carry immutable publication tokens, cancelled/stale playlist candidates cannot publish, and focus changes during restore are reconciled until stable.
18. **PR #180 — aggregate collector/summary hardening — main `68ddecb`.** Favorites, All Channels and Recent share lazy aggregate flows across count/catalog/summary consumers, unchanged publications are coalesced, and preview preparation is bounded while preserving stable ordering. Exact-head Database Unit CI #34 and Android CI #735 passed before squash merge.

### Current development state

Issue #45's aggregate collector/summary work is complete in `main`. Ready-catalog reconciliation and Favorites integration were additionally hardened in PR #182. Catalog work stays stable unless a measured regression or a planned capability requires reopening it.

## Issue #47 — EPG / Now-Next / archive

### Complete

1. **PR #181 — calendar-safe EPG day windows.** TODAY/TOMORROW follow local civil-day boundaries instead of fixed 24-hour arithmetic, including DST/skipped-midnight regressions.
2. **PR #187 — ambiguity-safe EPG matching.** Preserve `tvg-id`, exact display-name and exact channel-id priority; partial fallback accepts only one distinct XMLTV candidate and fails closed when multiple channels are plausible, including normalized-key collisions.
3. **PR #189 — explicit M3U catch-up metadata boundary.** Preserve standalone `catchup`, `catchup-days` and `catchup-source` attributes per parsed channel, reject URL/title false positives, and keep live-only channels archive-disabled.
4. **PR #190 — safe catch-up playback capability contract.** Resolve only explicit supported provider metadata into deterministic archive URLs and fail closed on unknown modes/placeholders, malformed declared ranges, URL-fragment hazards or invalid/out-of-window programme intervals; no Player launch wiring yet.
5. **PR #191 — catch-up capability persistence.** Persist explicit per-channel catch-up metadata through the normal Channel/Room contract, generic import, Ready refresh and Favorites representation, preserving invalid-declared range state and clearing retired publisher capability without enabling Player launch.

### Next production increments

1. **PR #192 — structured EPG match diagnostics — active.** Expose a read-only per-playlist snapshot for total/matched/unmatched channels, conservative match-kind counts, channels with programmes, selected EPG source and last successful in-memory load timestamp; do not change matching, source precedence or refresh/cache policy.
2. **EPG cache/refresh hardening.** Retain the last valid guide across bounded temporary fetch failures and make refresh/cache state observable in a separate fresh-main increment without broadening match heuristics.
3. **Later aggregate filters.** Add EPG/Now-Next/archive/P2P virtual catalog views only after their capability contracts are stable.

## Issue #46 — Player UX, buffering and architecture

Player work is split into small fresh-main increments. Refactoring must preserve playback behavior first; performance-policy changes are separate commits/PRs so regressions remain attributable.

### Production routing and architecture debt

`MainActivity` routes the application to `StablePlayerScreen`, implemented in `feature/player/StablePlayerScreenReplacement.kt`. That production route, not the older `PlayerScreen.kt`, is the refactor and performance target.

- `feature/player/StablePlayerScreenReplacement.kt` — production root composition, channel filtering, panels, layout selection and remote-action policy still share one file, though PR #186 extracted the first presentation responsibilities.
- `feature/player/StablePlayerInput.kt` — production Android View/touch/key lifecycle; it must remain aligned with the `stableRemoteActionForKey` policy used by the real video surfaces.
- `feature/player/PlayerViewModel.kt` — roughly 113 KB: broad `PlayerUiState`, playback orchestration, catalog/EPG state and frequent state updates in one ViewModel.
- `feature/player/PlayerScreen.kt` — roughly 108 KB legacy composable. Do not spend refactor budget on it unless routing is explicitly migrated back to it or the legacy path is being retired.
- Other large candidates include `ScannerViewModel.kt`, `EditorViewModel.kt`, `PublicRepositoryScannerDataSource.kt`, `SettingsScreen.kt` and `ImporterViewModel.kt`.

The target is not class-heavy OOP. For Kotlin/Compose, apply SOLID/bounded responsibilities with small interfaces, coordinators/policies where stateful behavior belongs, pure helpers for deterministic logic and focused composables for presentation.

### Refactor sequence

1. **Production Player input boundary — complete, PR #183.** `StablePlayerInputHandler` remains the Android lifecycle/gesture adapter, deterministic input contracts/policy are extracted, `stableRemoteActionForKey` remains the production key mapping source of truth, and Player refactors are protected by an exact-head Scanner boundary/regression gate.
2. **Production StablePlayer composition split — in progress.** PR #186 extracted the first stable presentation responsibilities. Continue bounded mechanical splits (channel browser/list, panels/settings, shell/navigation) without changing playback semantics, search/filter semantics or state ownership. PR #188 was a closed staging attempt and did not land production code.
3. **Player state/recomposition split.** Separate hot playback/engine telemetry from large catalog/EPG/browser state, reduce root `PlayerUiState` invalidation, move expensive derived channel models/indexes out of root composition, and publish only distinct state changes.
4. **RAM-bounded Auto buffer.** Keep persisted `BufferProfile.STANDARD` for compatibility but present it as `Авто`; for ordinary IPTV `ChannelHealth.UNSTABLE`, increase only time-based Media3 recovery/min-buffer thresholds while preserving existing device/multiview byte caps. Manual remains explicit. Ace/P2P buffer policy is excluded without Issue #159 device evidence.
5. **Measured Player performance pass.** Address remaining main-thread/state churn only from deterministic evidence or profiling, keeping each optimization independently testable.
6. **Large-file follow-up.** Apply the same responsibility-first decomposition pattern to Scanner, Editor, Importer and Settings in separate bounded-context PRs; do not combine unrelated modules in one rewrite.

### Refactor invariants

- Production Player routing is `StablePlayerScreen`; validate the actual routed implementation before every architectural change.
- No second playback runtime.
- No behavioral change in a mechanical file split.
- No P2P/Ace peer/DHT/request/buffer change from a generic Player refactor.
- Keep TV/D-pad focus behavior, Back behavior, selected-channel identity and playback-session ownership stable.
- Keep production remote mapping (`stableRemoteActionForKey`) and `StablePlayerInputHandler` behavior aligned; do not maintain a parallel legacy remote policy as a substitute for production tests.
- Player architecture work must not modify Scanner discovery/query/search semantics.
- Prefer extracted pure functions/data classes and small composables over inheritance hierarchies.
- Introduce interfaces/classes only when they own a real contract, lifecycle or policy.
- Every refactor PR starts from fresh `main`, has focused regression coverage and merges only after exact-head gates are green.

## Ready catalog — PR #182 complete

The built-in Ready catalog contains exactly three live presets. READY_CATALOG refreshes are network-backed and atomic, refresh playlist/EPG metadata from downloaded M3U data, preserve exact stream identity and valid source variants, reconcile Favorite source variants safely, and route scheduled refresh through the same live downloader. Manual/Scanner imports remain independent from READY_CATALOG.

## Portable Favorites contract

`Избранные каналы` is a user-owned library, not a view over current source rows.

The full backup direction is:

```text
Favorite backup
  ├── formatVersion
  ├── logical favorites[]
  │    ├── logicalKey
  │    ├── display snapshot
  │    ├── preferred source marker
  │    └── sourceVariants[]
  │         ├── stream/source identity
  │         ├── original playlist/group provenance
  │         └── non-secret metadata
  └── backup metadata
```

M3U/M3U8 remains the universal IPTV interoperability path for representative channels. It does not preserve the complete logical/provenance/variant model and therefore is not the full Rinat IPTV backup format.

A future shareable HTTP/LAN URL is a separate feature after portable backup/import. An Internet-reachable share URL requires an explicit hosting/sync backend and is not implied by local export.

## P2P / Ace Live evidence gate — Issue #159

The current P2P transport policy remains evidence-driven. The latest canonical field evidence is the 2026-08-20 TV Box track summarized in [`testing/playback-log-analysis-2026-08-20.md`](testing/playback-log-analysis-2026-08-20.md).

Already-completed hardening includes terminal peer-pool ownership, production-lifetime DHT routing memory, warm-query scheduling, progress-aware direct handoff/fallback, deterministic A→B→C ownership, player-session terminal summaries and bounded MPEG-TS/continuous-fixture diagnostics. PR #184 additionally provides deterministic producer-stage field-evidence classification across correlated runtime/gap diagnostics, including bounded observation-window deltas and zero-event scheduler stalls; this tooling is observational and does not authorize transport-policy changes.

The remaining field gate is real-device evidence for producer-stage / rapid-switch behavior and the separate player/TS boundary. Do **not** infer new peer/request/buffer policy from catalog/Favorites/EPG/Player-refactor CI.

### Binding P2P constraints

- Discovered endpoints are not connected, handshaked, requestable or producing peers.
- Producer `sent` proves a completed local socket write, not receipt or acceptance by the peer.
- A 40-character Ace `content_id` is a transport identity, not automatically a BitTorrent infohash.
- Do not increase the 60 s content-preparation bound, 30 s no-connected-peer guard, DHT budgets, peer caps, request depth or output buffers to conceal a failure.
- Preserve generation/session ownership and complete non-cancellable cleanup on supersession.
- Do not change generic IPTV or normal BitTorrent behavior to fix Ace Live.
- Do not add an external Ace Stream Engine runtime dependency or fallback.
- Do not implement alternate-peer/request-timeout assumptions without new device evidence showing the missing producer stage and justifying that policy.

## Validation gates

For normal Android integration work on the exact PR head:

1. `lintDebug`
2. relevant unit modules, including `:core:data:testDebugUnitTest` for Favorites/data work, `:feature:epg:testDebugUnitTest` for EPG work and `:feature:player:testDebugUnitTest` for Player work
3. `:feature:playlists:testDebugUnitTest` for canonical catalog changes
4. `:app:assembleDebug`
5. `:app:assembleDebugAndroidTest`
6. signed ARM TV APK build/artifacts in the full Android workflow

Player architecture PRs additionally use `Player Refactor Guard`: checkout `github.event.pull_request.head.sha`, verify `git rev-parse HEAD` equals that exact SHA, reject `feature/scanner`/`core/scanner` production changes, and run both Player and Scanner unit suites.

Database/Favorites persistence work additionally uses the dedicated Database Unit CI (`:core:database:testDebugUnitTest` + `:core:data:testDebugUnitTest`).

P2P/runtime changes additionally require their P2P/unit/tooling gates and real `TorrentTvPlaybackSmokeTest`/hardware evidence when the touched behavior requires it. Building an instrumentation APK is not counted as running the real-device smoke.

## Decision order

1. Keep PR #182's merged Ready/catalog/Favorites baseline stable; do not reopen it without a measured regression or planned capability.
2. Continue Issue #46 against the production StablePlayer route: the input boundary is complete in PR #183 and the first composition split landed in PR #186; continue bounded composition extraction before state/recomposition, RAM-bounded Auto buffer and measured performance work.
3. Continue Issue #47 from merged PR #191 with PR #192 structured match diagnostics, then harden EPG cache/refresh behavior in a separate fresh-main increment.
4. For P2P Issue #159, wait for new same-device producer-stage/rapid-switch evidence before changing peer selection, request timeout, DHT, request depth or buffer policy.
5. Refactor other oversized Scanner/Editor/Importer/Settings files only as separate bounded-context PRs after establishing the production Player decomposition pattern.
6. Complete hardware/soak/release acceptance before closing master roadmap #44.

## Cross-track invariants

- Catalog/Favorites/EPG/Player architecture work must not rewrite Scanner discovery/query semantics.
- Catalog/Favorites/EPG/Player architecture work must not modify Ace Live peer/DHT/request/buffer policies without P2P field evidence.
- Logical favorite ownership must not depend on Room auto-generated channel/playlist row lifetime.
- Source provenance and source variants must remain discoverable after logical deduplication.
- A virtual aggregate must not masquerade as a destructively editable physical playlist.
- Export/import must preserve user-owned data without silently exporting credentials or provider secrets.
- EPG fallback matching must prefer certainty over silently assigning an ambiguous guide channel.
- Every production increment starts from fresh `main`, carries focused tests, and merges only after exact-head gates are green.

## Documentation map

- `PROJECT_STATUS_AND_ROADMAP.md` — canonical current status and next gate.
- `USER_GUIDE.md` — user-facing controls, canonical catalog and autonomous Favorites behavior.
