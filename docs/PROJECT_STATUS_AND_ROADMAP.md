# Project status and roadmap

_Last updated: 2026-08-24_

This is the canonical current-state and next-action document. Dated field reports remain immutable evidence; when an older plan describes a different current increment, this page wins.

## Current integration head

- Latest functional production baseline: `82418095e034c00d1ec0b3737c3a3cc25b6c2cc8` — PR #209 `fix(p2p): avoid repeated dead peer on direct retry`, squash-merged after exact-head Android CI #941, real Torrent TV playback smoke without external Ace Engine, full `core:p2p` regression coverage and Player Refactor Guard #109.
- PR #200, #201, #202, #204, #206, #207 and #209 are merged after the earlier PR #174–#198 sequence. PR #206 added the troubleshooting baseline; PR #207 fixed the field-evidenced false `qualified_peer_no_media` handoff after valid `media_appended`; PR #209 preserves the existing five-second pre-handshake TCP connect-failure backoff across short-lived direct/direct-retry runtimes and keeps recently failed endpoints from immediately satisfying discovery fast paths. Neither P2P increment widened the binding P2P time/query/peer/request/buffer budgets.
- The old Player shell draft PR #208 is closed unmerged because its base predates the current P2P integration. Its mechanical extraction scope remains valid and must be recreated from fresh `main` before any visual Player redesign.
- Issue #210 now owns the confirmed Home/Live Player TV-dashboard direction and same-session dashboard ↔ fullscreen contract. Issue #211 owns ordinary-IPTV playback compatibility / Media3→LibVLC multicodec hardening and real-device codec acceptance.
- Catalog/Favorites, EPG, Player architecture, dashboard and codec work do not constitute new Ace Live field evidence. Further P2P behavior changes remain bound to Issue #159 real-device evidence.
- Historical merged feature branches are not production merge candidates and should be deleted after their changes are verified in `main` when repository tooling supports genuine branch deletion.

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
6. **PR #192 — structured EPG match diagnostics.** Expose a read-only per-playlist snapshot for total/matched/unmatched channels, conservative match-kind counts, channels with programmes, selected EPG source and last successful in-memory load timestamp; review hardening keeps declared zero-programme XMLTV channel IDs matchable, follows existing programme-bearing source selection and timestamps successful cache insertion.
7. **PR #193 — bounded stale EPG fallback.** Keep the 15-minute fresh TTL unchanged, retain the last valid guide for at most two hours and only after typed transient transport/HTTP 5xx refresh failures; evaluate fresh sources first, preserve the single-entry cache bound, and keep HTTP non-5xx, malformed/oversized XMLTV and low-memory fail-closed.
8. **PR #194 — EPG cache/refresh observability.** Extend the existing read-only diagnostics snapshot with explicit fresh/stale origin, non-negative cache age and active transient retry deadline while preserving source precedence, cache capacity and refresh policy. Review hardening keeps diagnostic backoff observation fully non-mutating.

### Next production increments

1. **Archive Player launch/UI integration.** Wire only persisted explicit catch-up capability and the fail-closed resolver into an intentional EPG programme action/Player launch path; do not infer archive support from live URLs and keep provider-specific unsupported modes disabled.
2. **Player/dashboard EPG polish.** Issue #210 may surface Now/Next and intentional programme actions, but archive playback remains gated by the persisted explicit capability above.
3. **Later aggregate filters.** Add EPG/Now-Next/archive/P2P virtual catalog views only after their capability contracts are stable.

## Issue #46 — Player UX, buffering and architecture

Player work is split into small fresh-main increments. Refactoring must preserve playback behavior first; performance-policy changes are separate commits/PRs so regressions remain attributable.

### Production routing and architecture debt

`MainActivity` routes the application to `StablePlayerScreen`, implemented in `feature/player/StablePlayerScreenReplacement.kt`. That production route, not the older `PlayerScreen.kt`, is the refactor and performance target.

- `feature/player/StablePlayerScreenReplacement.kt` — production root composition, channel filtering, layout selection and remote-action policy remain together after bounded presentation extractions; shell/navigation is the next mechanical split.
- `feature/player/StablePlayerPresentation.kt` — focused shared presentation helpers for channel banner, volume/scroll controls and EPG display formatting.
- `feature/player/StablePlayerPanels.kt` — panel/dialog composition extracted mechanically in PR #196; callback/state ownership remains in the production root.
- `feature/player/StablePlayerChannelBrowser.kt` — channel browser/list/drawer and nearby-channel presentation extracted mechanically in PR #198 while preserving `StableChannelNavigation` and existing callbacks.
- `feature/player/StablePlayerInput.kt` — production Android View/touch/key lifecycle; it must remain aligned with the `stableRemoteActionForKey` policy used by the real video surfaces.
- `feature/player/PlayerViewModel.kt` — roughly 113 KB: broad `PlayerUiState`, playback orchestration, catalog/EPG state and frequent state updates in one ViewModel.
- `feature/player/PlayerScreen.kt` — roughly 108 KB legacy composable. Do not spend refactor budget on it unless routing is explicitly migrated back to it or the legacy path is being retired.
- Other large candidates include `ScannerViewModel.kt`, `EditorViewModel.kt`, `PublicRepositoryScannerDataSource.kt`, `SettingsScreen.kt` and `ImporterViewModel.kt`.

The target is not class-heavy OOP. For Kotlin/Compose, apply SOLID/bounded responsibilities with small interfaces, coordinators/policies where stateful behavior belongs, pure helpers for deterministic logic and focused composables for presentation.

### Refactor and UX sequence

1. **Production Player input boundary — complete, PR #183.** `StablePlayerInputHandler` remains the Android lifecycle/gesture adapter, deterministic input contracts/policy are extracted, `stableRemoteActionForKey` remains the production key mapping source of truth, and Player refactors are protected by an exact-head Scanner boundary/regression gate.
2. **Production StablePlayer composition split — next mechanical increment.** PR #186 extracted the first stable presentation responsibilities, PR #196 extracted panel/dialog presentation, and PR #198 extracted channel browser/list/drawer plus nearby-channel presentation. Stale draft PR #208 attempted the remaining shell extraction but is closed unmerged because its base predates current `main`; recreate the same behavior-preserving `StablePlayerShell.kt` extraction from fresh `main`. Playback semantics, search/filter semantics, input policy and state ownership remain unchanged.
3. **Issue #210 — Home/Live Player dashboard shell.** After the mechanical split is merged, introduce the TV-first dark dashboard layout as small fresh-main PRs: compact left navigation, large central video pane, right channel/group selector and bottom quick-channel rail. Do not create a second playback runtime.
4. **Issue #210 — focus/navigation and channel rails.** Preserve visible focus, D-pad/Center/Back semantics, mouse/touchpad behavior and focus restore across left rail, video, channel list and bottom rail.
5. **Issue #210 — same-session dashboard ↔ fullscreen.** Fullscreen button/double-click may expand the active video; Back/fullscreen toggle returns to dashboard while preserving the same playback session/runtime, selected channel, volume/mute, scale/aspect, EPG context and focus return target. Back closes overlays before leaving fullscreen, and leaves Player only from dashboard level.
6. **Issue #210/#47 — Now/Next, Favorites and UI polish.** Surface reliable EPG and Favorite state without inventing archive capability. Archive actions use only Issue #47's explicit persisted catch-up contract.
7. **Issue #211 — playback compatibility / multicodec hardening.** Keep Media3 primary and LibVLC as bounded fallback; classify network/source/container/demux/decoder/backend failures, preserve Player context across fallback when possible, and build a measured codec/container matrix rather than promising literally every codec on every device.
8. **Real-device codec acceptance.** Validate H.264/H.265/MPEG-2 plus AAC/HE-AAC/AC-3/E-AC-3 and additional available samples on ARM TV Box, recording backend/decoder/startup/first-frame/audio/fallback evidence.
9. **Player state/recomposition and performance follow-up.** After the confirmed UX/playback sequence, separate hot playback/engine telemetry from large catalog/EPG/browser state and address remaining main-thread/state churn only from deterministic evidence or profiling.
10. **RAM-bounded Auto buffer follow-up.** Keep persisted `BufferProfile.STANDARD` for compatibility but present it as `Авто`; for ordinary IPTV only, tune time-based recovery/min-buffer thresholds within existing device/multiview byte caps when supported by measured evidence. Ace/P2P buffer policy remains excluded without Issue #159 device evidence.
11. **Large-file follow-up.** Apply the same responsibility-first decomposition pattern to Scanner, Editor, Importer and Settings in separate bounded-context PRs; do not combine unrelated modules in one rewrite.

### Refactor and UX invariants

- Production Player routing is `StablePlayerScreen`; validate the actual routed implementation before every architectural change.
- No second playback runtime.
- No behavioral change in a mechanical file split.
- Dashboard ↔ fullscreen is a presentation/state transition over the same active playback session, not a channel restart.
- No P2P/Ace peer/DHT/request/timeout/buffer change from generic Player, dashboard or codec work.
- Keep TV/D-pad focus behavior, Back behavior, selected-channel identity and playback-session ownership stable.
- Keep production remote mapping (`stableRemoteActionForKey`) and `StablePlayerInputHandler` behavior aligned; do not maintain a parallel legacy remote policy as a substitute for production tests.
- Player architecture work must not modify Scanner discovery/query/search semantics.
- Prefer extracted pure functions/data classes and small composables over inheritance hierarchies.
- Introduce interfaces/classes only when they own a real contract, lifecycle or policy.
- Every refactor/UX/playback PR starts from fresh `main`, has focused regression coverage and merges only after exact-head gates are green.

### Issue #211 compatibility target

Ordinary IPTV compatibility is intentionally broad but measurable. Hardware decoder capability, Android API level, DRM and redistribution/licensing constraints mean the project must not claim literal universal codec support.

- Primary backend: Media3.
- Integrated fallback: LibVLC for classified decoder/container/demux/backend failures where the bundled backend can safely handle the stream.
- Target video matrix: H.264/AVC, H.265/HEVC, MPEG-2 Video, MPEG-4 Part 2 where encountered, VP9, and AV1 where device/backend support exists.
- Target audio matrix: AAC/HE-AAC, MP2/MP3, AC-3, E-AC-3 where supported, Opus where encountered, and multiple audio tracks.
- Delivery/container matrix: MPEG-TS over HTTP/HTTPS, HLS, progressive MP4/MKV/WebM for VOD/archive, redirects/common IPTV headers, and loopback MPEG-TS from the embedded P2P runtime.
- Preserve selected channel, volume/mute and dashboard/fullscreen context across backend fallback when technically possible; prevent Media3↔LibVLC fallback loops.
- Expose fallback classification in diagnostics, not as permanent user noise.

The detailed confirmed implementation contract is in [`PLAYER_DASHBOARD_AND_PLAYBACK_COMPATIBILITY_PLAN.md`](PLAYER_DASHBOARD_AND_PLAYBACK_COMPATIBILITY_PLAN.md).

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

The current P2P transport policy remains evidence-driven. The latest same-device TV Box evidence is tracked in Issue #159 and the 2026-08-20 analysis documents.

Already-completed hardening includes terminal peer-pool ownership, production-lifetime DHT routing memory, warm-query scheduling, progress-aware direct handoff/fallback, deterministic A→B→C ownership, player-session terminal summaries and bounded MPEG-TS/continuous-fixture diagnostics. PR #184 additionally provides deterministic producer-stage field-evidence classification across correlated runtime/gap diagnostics, including bounded observation-window deltas and zero-event scheduler stalls; this tooling is observational and does not authorize transport-policy changes.

The latest field-derived fixes are now production:

- **PR #207 — media handoff preservation.** When valid TS has already reached `media_appended`, the direct-retry runtime is no longer falsely terminated as `qualified_peer_no_media`; the fixed two-second qualification grace remains unchanged for a qualified peer with no media, and the runtime with media continues only through the existing bounded startup/player handoff.
- **PR #209 — dead-connect retry diversity.** The existing five-second first pre-handshake TCP-connect failure backoff survives direct/direct-retry runtime replacement, is scoped by exact swarm+endpoint, is cleared by a successful TCP connect, filters stale tracker/DHT candidates and raises only the DHT early-return peer-count threshold by the number of currently remembered failed endpoints. Absolute DHT time/query/branching/peer caps remain unchanged.

Issue #159 remains open. The next Ace/P2P behavior change requires new TV Box evidence from current `main`; do not infer new peer/request/buffer policy from catalog/Favorites/EPG/Player/dashboard/codec CI.

### Binding P2P constraints

- Discovered endpoints are not connected, handshaked, requestable or producing peers.
- Producer `sent` proves a completed local socket write, not receipt or acceptance by the peer.
- A 40-character Ace `content_id` is a transport identity, not automatically a BitTorrent infohash.
- Preserve the 60 s content-preparation bound, 30 s no-connected-peer guard, existing 8 s soft handoff boundary and fixed 2 s non-renewable qualification grace.
- Do not increase DHT discovery budgets/branching/query caps, active-peer target/max, TCP connect/handshake/write timeouts, request depth/concurrency, recovery cursor/max-advance limits or output buffer/cache to conceal a failure.
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

Player architecture and Live Player/dashboard PRs that touch `feature/player/**` additionally use `Player Refactor Guard`: checkout `github.event.pull_request.head.sha`, verify `git rev-parse HEAD` equals that exact SHA, reject `feature/scanner`/`core/scanner` production changes, and run both Player and Scanner unit suites.

The current guard workflow does not yet trigger for a PR whose production changes are limited to `feature/home/**`. Before the first Home-only dashboard implementation PR, land a separate fresh-main CI-guard increment that adds the Home dashboard paths to both the workflow `pull_request.paths` trigger and its applicability detection; after that precondition, Home-only dashboard PRs must use the same exact-head Player/Scanner regression guard plus full Android CI.

Database/Favorites persistence work additionally uses the dedicated Database Unit CI (`:core:database:testDebugUnitTest` + `:core:data:testDebugUnitTest`).

P2P/runtime changes additionally require their P2P/unit/tooling gates and real `TorrentTvPlaybackSmokeTest`/hardware evidence when the touched behavior requires it. Building an instrumentation APK is not counted as running the real-device smoke.

Codec/backend compatibility claims that depend on hardware decoding additionally require the Issue #211 real-device matrix; emulator/unit green alone must not be described as proof of a device codec capability.

## Decision order

1. **Complete the fresh-main mechanical Player shell split.** Recreate the valid scope of closed stale PR #208 from current `main`; no behavior, input, state ownership, Scanner or P2P changes.
2. **Issue #210 — Home/Live Player layout.** Introduce the confirmed TV dashboard structure in small fresh-main PRs. Before any PR limited to `feature/home/**`, first merge the guard-path precondition described above.
3. **Issue #210 — focus/navigation and channel rails.** Make left rail, video, right list and bottom rail fully usable by D-pad and mouse/touchpad with visible/restored focus.
4. **Issue #210 — dashboard ↔ fullscreen.** Use the same playback session/runtime and preserve selected channel, volume/mute, aspect/scale, EPG context and focus return; Back closes overlays, then fullscreen, then Player.
5. **Issue #210/#47 — EPG Now/Next, Favorites and polish.** Use only explicit archive/catch-up capability for programme playback and keep unsupported modes fail-closed.
6. **Issue #211 — Media3→LibVLC compatibility hardening.** Add deterministic fallback classification and broaden measured codec/container coverage without external ordinary-IPTV players.
7. **Run the TV Box codec matrix.** Validate H.264/H.265/MPEG-2/AC-3/E-AC-3 and available additional samples on real ARM hardware, recording backend/decoder/first-frame/audio/fallback evidence.
8. **P2P Issue #159 remains evidence-gated.** Do not make another Ace peer/DHT/request/timeout/buffer behavior change until new current-main TV Box evidence identifies the first missing producer transition.
9. Refactor remaining Player state/recomposition and other oversized Scanner/Editor/Importer/Settings files only as separate measured follow-ups after the confirmed UX/playback sequence.
10. Complete hardware/soak/release acceptance before closing master roadmap #44.

## Cross-track invariants

- Catalog/Favorites/EPG/Player architecture/dashboard/codec work must not rewrite Scanner discovery/query semantics.
- Catalog/Favorites/EPG/Player architecture/dashboard/codec work must not modify Ace Live peer/DHT/request/timeout/buffer policies without P2P field evidence.
- Logical favorite ownership must not depend on Room auto-generated channel/playlist row lifetime.
- Source provenance and source variants must remain discoverable after logical deduplication.
- A virtual aggregate must not masquerade as a destructively editable physical playlist.
- Export/import must preserve user-owned data without silently exporting credentials or provider secrets.
- EPG fallback matching must prefer certainty over silently assigning an ambiguous guide channel.
- Every production increment starts from fresh `main`, carries focused tests, and merges only after exact-head gates are green.

## Documentation map

- `PROJECT_STATUS_AND_ROADMAP.md` — canonical current status and next gate.
- `PLAYER_DASHBOARD_AND_PLAYBACK_COMPATIBILITY_PLAN.md` — confirmed #210/#211 implementation contract and TV Box compatibility acceptance.
- `USER_GUIDE.md` — user-facing controls, canonical catalog and autonomous Favorites behavior.
