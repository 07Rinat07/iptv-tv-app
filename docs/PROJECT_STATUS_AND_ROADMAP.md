# Project status and roadmap

_Last updated: 2026-08-18_

This document is the canonical **current-state and next-action view** for the project. Historical field reports and dated V4d notes remain evidence records; when an older document conflicts with this page about the current priority, this page wins until it is updated again.

## Current production baseline

- `main`: `266cd7dc6bf4cbe0ac08cb78b7e9352c11a505df` (merge of PR #144).
- PR #136 (`fix(p2p): complete BEP-9 content metadata negotiation`) passed its exact-head Android CI and real field gate: the known-infohash control remained playable and a genuine Content-ID metadata/catalog resolution succeeded. It is merged into `main`.
- PR #144 (`fix(p2p): restore tracker during startup full expansion`) passed exact-head Android CI and is merged into `main`. Short startup probe rounds remain DHT-only, while the final bounded startup full-expansion can reacquire candidates from tracker + DHT.
- Existing safety bounds remain unchanged: no increase to the 60-second preparation deadline, no-connected-peer guard, handshake/request timeouts, DHT budgets, peer target/max, scheduler/recovery bounds, output buffer, HTTP/Media3 policy, generic IPTV or normal BitTorrent behavior.
- Embedded Torrent TV/Ace Live remains self-contained; an installed external Ace Stream Engine is not a production runtime dependency or fallback.

## Closed blocker: Content-ID transport metadata parity

The Content-ID metadata-resolution gate that blocked V4d live-peer work is closed by PR #136. A real multi-channel field sweep proved both required properties on the production candidate: known-infohash playback remained functional and at least one real Content-ID metadata/catalog resolution succeeded.

Diagnostic PR #143 and the related diagnostic branches are evidence-only and must not be merged into production.

## Current V4d blocker: live peer acquisition/qualification after metadata resolution

Fresh field evidence after metadata parity showed that the remaining failures are no longer correctly classified as a generic Content-ID metadata problem. Metadata/catalog resolution can succeed, but live startup can still fail before a useful producer is established.

The first bounded discovery/acquisition increment is now merged as PR #144. It restores tracker participation only during the final startup full-expansion while keeping short probe rounds DHT-only. This improves candidate reacquisition diversity without widening timeouts, peer limits, concurrency, request depth or memory budgets.

The next behavior change is **evidence-gated**. Do not immediately add another discovery heuristic. Run a fresh multi-channel field validation from current `main` and classify the first persistent failing stage using the existing canonical startup timeline and peer lifecycle diagnostics.

## Decision order from here

1. **Run fresh V4d field validation from current `main`.** Include the known-infohash control, multiple real Content-ID channels and rapid channel switches. Preserve exact startup/lifecycle evidence.
2. **Classify the first persistent blocker by stage:**
   - no candidates / weak diversity after bounded tracker+DHT expansion → discovery/acquisition;
   - candidates but repeated `CONNECT_FAILED` → dial/acquisition;
   - TCP connected but `HANDSHAKE_TIMEOUT` / rejection → qualification/diversity;
   - handshake accepted but no useful window → availability/usefulness;
   - useful peer but no producer → producer acquisition/selection;
   - producer exists but media/buffer readiness is slow → scheduler/forward reserve;
   - proven localhost Range/reopen mismatch only → HTTP logical-offset semantics.
3. **Make exactly one bounded, stage-specific increment.** Do not widen peer counts, global startup timeout, handshake timeout, DHT budgets, cache sizes, request depth, concurrency or Media3/HTTP policy merely to hide a startup failure.
4. **Require exact-head validation before merge.** Android CI, applicable core P2P/unit tests, lint/build/instrumentation checks, signed ARM TV APK/source packaging and real playback smoke must pass for behavior changes.
5. **Repeat field evidence after each behavior increment.** A green synthetic/CI run does not by itself prove startup/zap parity.
6. **Broad acceptance remains last.** Fixed same-device A/B, 20 rapid switches, weak-network/peer-loss tests and 2h/8h ARM TV Box soak follow only after V4d functional blockers are closed.

## V4d performance target

For healthy same-device swarms where Televizo + Ace Stream Engine starts/switches in roughly 2–4 seconds, the autonomous embedded runtime should move toward comparable startup/zap success and latency without using the external engine as a runtime fallback. Optimization must follow measured stage evidence rather than relaxing safety bounds.

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
- `ROADMAP_V4D_FIELD_2026-08-16.md` — historical R2/R3 field execution record through the earlier V4d stages; its “current increment” text is superseded by this document.
- `ACE_LIVE_FIELD_VALIDATION_*.md` and `ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md` — immutable/dated field evidence and test procedure records.
- `ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md` and `ACE_LIVE_STARTUP_TIMELINE.md` — diagnostics contracts used to classify later failures.
- `P2P_RUNTIME_NOTES.md` and `P2P_CONTENT_TRANSPORT.md` — runtime and transport invariants.

Historical evidence should remain available rather than being rewritten to look current. New decisions should update this canonical page and, when architecture itself changes, the corresponding long-form design document.