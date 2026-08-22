# Project status and roadmap

_Last updated: 2026-08-22_

This is the canonical current-state and next-action document. Dated field reports remain immutable evidence; when an older plan describes a different current increment, this page wins.

## Current integration head

- Current `main`: `03f8e8e` — Recent/History virtual aggregate, deterministic MRU ordering and user/docs sync.
- PR #174–#179 are merged: versioned portable backup, safe exports, RIPTV picker import, source reconciliation/preference, TV source picker and its help sync. All Channels is also complete in `main`.
- Catalog/Favorites work does not constitute new Ace Live field evidence. P2P transport decisions remain bound to the real-device evidence track described below.
- Historical diagnostic and already-pruned branches are not production merge candidates.

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

### Current production increment

**Catalog focus performance hardening — in progress.** The current fresh-main increment removes O(N) snapshot reconstruction from the D-pad focus hot path while preserving entries, breadcrumbs, checkpoint/rebuild focus restore and exact Player routing.

### Next production increments

1. **Catalog focus hot path.** Reuse prepared entries/breadcrumbs for focus-only updates and protect it with a deterministic 10k-channel regression harness.
2. **Non-blocking rebuild.** Prepare the canonical tree off Main with latest-wins cancellation and preserve checkpoint application on Main.
3. **Aggregate collector/summary hardening.** Remove redundant cold-flow subscriptions, coalesce unchanged summary work and bound top-50 preparation.
4. **Later aggregate filters.** Add EPG/Now-Next/archive/P2P views only after their capability contracts are stable.

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

Already-completed hardening includes terminal peer-pool ownership, production-lifetime DHT routing memory, warm-query scheduling, progress-aware direct handoff/fallback, deterministic A→B→C ownership, player-session terminal summaries and bounded MPEG-TS/continuous-fixture diagnostics.

The remaining field gate is real-device evidence for producer-stage / rapid-switch behavior and the separate player/TS boundary. Do **not** infer new peer/request/buffer policy from catalog/Favorites CI.

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
2. relevant unit modules, including `:core:data:testDebugUnitTest` for Favorites/data work
3. `:feature:playlists:testDebugUnitTest` for canonical catalog changes
4. `:app:assembleDebug`
5. `:app:assembleDebugAndroidTest`
6. signed ARM TV APK build/artifacts in the full Android workflow

Database/Favorites persistence work additionally uses the dedicated Database Unit CI (`:core:database:testDebugUnitTest` + `:core:data:testDebugUnitTest`).

P2P/runtime changes additionally require their P2P/unit/tooling gates and real `TorrentTvPlaybackSmokeTest`/hardware evidence when the touched behavior requires it. Building an instrumentation APK is not counted as running the real-device smoke.

## Decision order

1. Finish the catalog focus hot-path increment from fresh `main` and merge it only after exact-head feature tests, lint and Android assembly gates are green.
2. Continue off-Main latest-wins rebuild and aggregate collector/summary hardening as separate measured increments.
3. For P2P Issue #159, wait for new same-device producer-stage/rapid-switch evidence before changing peer selection, request timeout, DHT, request depth or buffer policy.
4. Continue EPG/Now-Next/archive (#47) and Player UX (#46) on top of the stable catalog/Favorites identity contracts.
5. Complete hardware/soak/release acceptance before closing master roadmap #44.

## Cross-track invariants

- Catalog/Favorites work must not rewrite Scanner discovery/query semantics.
- Catalog/Favorites work must not modify Ace Live peer/DHT/request/buffer policies without P2P field evidence.
- Logical favorite ownership must not depend on Room auto-generated channel/playlist row lifetime.
- Source provenance and source variants must remain discoverable after logical deduplication.
- A virtual aggregate must not masquerade as a destructively editable physical playlist.
- Export/import must preserve user-owned data without silently exporting credentials or provider secrets.
- Every production increment starts from fresh `main`, carries focused tests, and merges only after exact-head gates are green.

## Documentation map

- `PROJECT_STATUS_AND_ROADMAP.md` — canonical current status and next gate.
- `USER_GUIDE.md` — user-facing controls, canonical catalog and autonomous Favorites behavior.
- `architecture.md` — module boundaries and canonical catalog/Favorites/P2P contracts.
- `PLAYBACK_STATUS.md` — concise user-visible playback state and P2P acceptance criteria.
- `testing/playback-log-analysis-2026-08-20.md` — latest canonical P2P field evidence until a newer controlled device run supersedes it.
- `ROADMAP.md` and `ACE_LIVE_IMPLEMENTATION_PLAN.md` — long-form history and architecture.
- `ACE_LIVE_FIELD_VALIDATION_*.md` — immutable dated evidence.
- `P2P_RUNTIME_NOTES.md`, `P2P_CONTENT_TRANSPORT.md` and `ACE_LIVE_STARTUP_TIMELINE.md` — runtime, identity and diagnostics contracts.
