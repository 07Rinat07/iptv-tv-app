# Project status and roadmap

_Last updated: 2026-08-17_

This document is the canonical **current-state and next-action view** for the project. Historical field reports and dated V4d notes remain evidence records; when an older document conflicts with this page about the current priority, this page wins until it is updated again.

## Current production baseline

- `main`: `0baa1939dbe1782fbacbd28a91876db5027b8dfb` (squash merge of PR #134).
- PR #134 (`fix(p2p): keep bounded DHT diversity behind tracker startup`) is merged. Tracker discovery stays the immediate fast path, while tracker-only startup now schedules the existing bounded DHT startup diversity sequence in the background until media-ready.
- Existing safety bounds were preserved: no increase to the 60-second preparation deadline, no-connected-peer guard, handshake/request timeouts, DHT budgets, peer target/max, scheduler/recovery bounds, output buffer, HTTP/Media3 policy, generic IPTV or normal BitTorrent behavior.
- Embedded Torrent TV/Ace Live remains self-contained; an installed external Ace Stream Engine is not a production runtime dependency or fallback.

## Current blocker: Content-ID transport metadata parity

After PR #134, the next field investigation exposed a separate blocker before further live-peer tuning: many Torrent TV sources enter through a 40-hex Ace `content_id`, and the embedded runtime must resolve transport metadata reliably enough to reach the same live-swarm path as explicit known-infohash sources.

The current production candidate is PR #136 (`fix(p2p): complete BEP-9 content metadata negotiation`). Its production intent is intentionally narrow:

- use the standard BitTorrent outer handshake for the metadata swarm and require BEP-10 extension support;
- support the normal `metadata_size` path;
- support peers that omit `metadata_size` by requesting metadata piece 0 and learning bounded `total_size` from the BEP-9 data response;
- preserve the existing 1 MiB metadata cap and existing connect/read/write/retry limits;
- leave the Ace Live peer codec, live peer pool, DHT budgets, startup deadlines, Media3/HTTP, scheduler, recovery, TS gating and generic IPTV unchanged.

PR #136 is **not mergeable by unit/CI status alone**. Its field acceptance gate also requires a fresh exact-head multi-channel Torrent TV sweep that proves at least one genuine content-metadata/catalog resolution success, while the known-infohash control path remains playable.

## Latest exact field evidence — diagnostic sweep #13

The disposable diagnostic PR #135 ran the eight-sample Torrent TV sweep against production base `0baa1939dbe1782fbacbd28a91876db5027b8dfb`. The instrumentation sweep completed successfully as a test harness and produced evidence, but the playback result was **2/8 ready**:

- `Animal Planet HD known infohash`: ready, 17,474 ms;
- `Provider Discovery HD content_id`: ready, 14,475 ms;
- the other six samples failed before a playable local stream was resolved.

The important interpretation is stricter than “one Content-ID sample played.” On the successful `Provider Discovery HD content_id` attempt, catalog resolution still reported `success=false`, the metadata branch showed peer-connect failures, and the final playback success came from the competing direct path. Therefore this sweep does **not** prove the required `content_metadata_result success=true` / equivalent catalog success for PR #136.

Consequently:

- PR #135 remains diagnostic-only and must never be merged;
- PR #136 remains pending even if exact-head Android CI is green;
- the next code change must be selected from the exact metadata-resolution stage that still fails, not from generic playback latency.

## Decision order from here

1. **Close the Content-ID metadata-resolution gap first.** Reproduce the exact-head metadata path and classify failure by stage: discovery → TCP connect → BitTorrent outer handshake → BEP-10 extended handshake → `ut_metadata` request/data → bdecode/transport metadata validation.
2. **Make one bounded, stage-specific fix.** Do not broaden peer counts, global startup timeouts, handshake timeouts, DHT query budgets, cache sizes or Media3/HTTP policy to mask a metadata-resolution failure.
3. **Repeat the same multi-channel sweep.** Required proof for the production metadata PR: a real Content-ID metadata/catalog success plus the known-infohash control remaining playable, with no external Ace Engine.
4. **Only then merge the production metadata PR** after the same exact head also passes Android CI, core P2P/unit tests, lint, build/instrumentation checks, signed ARM TV APK/source packaging and applicable real playback smoke.
5. **Return to V4d live-peer acquisition/production evidence.** If Content-ID resolution parity is restored but startup still fails, use fresh lifecycle evidence to choose exactly one next subsystem:
   - no candidates / weak diversity → discovery/acquisition;
   - candidates but repeated `CONNECT_FAILED` → dial/acquisition;
   - connected but `HANDSHAKE_TIMEOUT` / rejection → qualification/diversity;
   - handshake accepted but no useful window → availability/usefulness;
   - useful peer but no producer → producer acquisition/selection;
   - producer exists but media/buffer readiness is slow → scheduler/forward reserve;
   - proven localhost Range/reopen mismatch only → HTTP logical-offset semantics.
6. **Broad acceptance remains last.** Fixed same-device A/B, rapid zapping, weak-network/peer-loss tests and ARM TV Box soak follow only after the current functional blockers are closed.

## Invariants that remain binding

- A discovered endpoint is not equivalent to connected, handshaked, useful or producing evidence.
- A 40-character Ace `content_id` is a transport identity, not automatically a BitTorrent infohash.
- Do not infer a stale content ID from a generic preparation timeout or UI text alone.
- Do not increase the 60-second startup failure bound or 30-second no-connected-peer guard as a workaround.
- Do not weaken recovery `maxPieceAdvance`, TS discontinuity/auth/resync gating or stale-session ownership.
- Do not implement HTTP Range/resume without actual Range/reopen evidence.
- Do not change generic IPTV or normal BitTorrent behavior to fix Ace Live.
- Do not add an external Ace Stream Engine runtime dependency/fallback.
- Keep observability changes separate from behavior changes whenever possible.

## Documentation map

Use these documents by role:

- `PROJECT_STATUS_AND_ROADMAP.md` — canonical current status, blocker and next decision gate.
- `ROADMAP.md` — long-form project roadmap/history.
- `ACE_LIVE_IMPLEMENTATION_PLAN.md` — detailed clean-room protocol/runtime implementation history and architectural constraints.
- `ROADMAP_V4D_FIELD_2026-08-16.md` — historical R2/R3 field execution record through PR #134; its “current increment” section is superseded by this document.
- `ACE_LIVE_FIELD_VALIDATION_*.md` and `ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md` — immutable/dated field evidence and test procedure records.
- `ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md` and `ACE_LIVE_STARTUP_TIMELINE.md` — diagnostics contracts used to classify later failures.
- `P2P_RUNTIME_NOTES.md` and `P2P_CONTENT_TRANSPORT.md` — runtime and transport invariants.

Historical evidence should remain available rather than being rewritten to look current. New decisions should update this canonical page and, when architecture itself changes, the corresponding long-form design document.