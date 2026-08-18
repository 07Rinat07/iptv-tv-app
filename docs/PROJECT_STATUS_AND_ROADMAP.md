# Project status and roadmap

_Last updated: 2026-08-18_

This document is the canonical **current-state and next-action view** for the project. Historical field reports and dated V4d notes remain evidence records; when an older document conflicts with this page about the current priority, this page wins until it is updated again.

## Current production baseline

- `main`: `a23b164e1c8bda9c1418108658af1e7b061366bb` (merge of PR #145).
- PR #136 (`fix(p2p): complete BEP-9 content metadata negotiation`) passed its exact-head Android CI and real field gate: the known-infohash control remained playable and a genuine Content-ID metadata/catalog resolution succeeded. It is merged into `main`.
- PR #144 (`fix(p2p): restore tracker during startup full expansion`) passed exact-head Android CI and is merged into `main`. Short startup probe rounds remain DHT-only, while the final bounded startup full-expansion can reacquire candidates from tracker + DHT.
- PR #145 synchronized this canonical roadmap after the Content-ID and discovery gates closed.
- Existing safety bounds remain unchanged: no increase to the 60-second preparation deadline, no-connected-peer guard, handshake/request timeouts, DHT budgets, peer target/max, scheduler/recovery bounds, output buffer, HTTP/Media3 policy, generic IPTV or normal BitTorrent behavior.
- Embedded Torrent TV/Ace Live remains self-contained; an installed external Ace Stream Engine is not a production runtime dependency or fallback.

## Closed blocker: Content-ID transport metadata parity

The Content-ID metadata-resolution gate that blocked V4d live-peer work is closed by PR #136. A real multi-channel field sweep proved both required properties on the production candidate: known-infohash playback remained functional and at least one real Content-ID metadata/catalog resolution succeeded.

Diagnostic PR #143 and the related old diagnostic branches are evidence-only and must not be merged into production.

## Latest V4d field evidence — 2026-08-18 post-#144 device run

A fresh real-device run from current production lineage showed that rapid channel selections are accepted and new P2P preparations are started, but most later Torrent TV selections fail before a playable localhost stream reaches the player.

The strongest representative failure is no longer discovery, TCP connect or handshake:

- first live candidate at about 185 ms;
- TCP connected at about 1,534 ms;
- Ace handshake accepted at about 1,728 ms;
- useful live window at about 1,796 ms;
- the peer subsequently reached `windowUseful=1`, `unchoked=1`, but remained `producing=0` with `aggregate_bps=0`.

This proves that at least one important V4d failure crosses discovery, connect, handshake and requestability successfully, then stalls before authenticated media production.

The same run also contains weaker acquisition/qualification failures on other channel selections: `CONNECT_FAILED` and `REMOTE_CLOSED` occur before handshake. Those remain real secondary failure modes, but they do not justify another broad discovery or timeout change because the representative useful-peer/no-producer path is now independently observed.

PR #144's intended discovery behavior is visible in this run: the final bounded startup expansion can return both tracker and DHT results, so the next increment must not simply repeat the same tracker/DHT change.

## Current V4d blocker: useful/unchoked peer without media production

The next narrow increment is **observability at the producer boundary**, not behavior tuning. Existing peer-quality snapshots already distinguish connected, handshaked, useful, unchoked and producing peers, but the field log needs an explicit bounded event for the state where a useful unchoked peer exists and no producer has emerged.

The current increment therefore adds `embedded_ace_live_producer_gap` diagnostics with bounded entry/periodic/resolution semantics. It does not change discovery, connection policy, scheduler ownership, request depth, refill/replacement, recovery, timeouts, media authentication, output buffering or player behavior.

After this diagnostic increment is exact-head green, repeat a short real-device run focused on one channel that reaches the producer gap. The next behavior PR must be chosen only from the evidence that follows this boundary. If the explicit producer gap persists but the current diagnostics still cannot distinguish request scheduling from request delivery/ingress, the next observational increment should expose bounded scheduler dispatch and chunk-ingress summaries before changing behavior.

## Decision order from here

1. **Land bounded producer-gap observability.** Exact-head CI must pass; no runtime policy changes are allowed in this increment.
2. **Run a short focused field validation.** Prefer a channel that reaches `handshaked=1`, `windowUseful=1`, `unchoked=1`, `producing=0`, plus one known-good control.
3. **Classify the first persistent blocker by stage:**
   - no candidates / weak diversity after bounded tracker+DHT expansion → discovery/acquisition;
   - candidates but repeated `CONNECT_FAILED` → dial/acquisition;
   - TCP connected but `HANDSHAKE_TIMEOUT` / rejection / `REMOTE_CLOSED` before qualification → qualification/diversity;
   - handshake accepted but no useful window → availability/usefulness;
   - useful + unchoked peer but no producer → producer boundary; inspect scheduler dispatch / request routing / chunk ingress before behavior changes;
   - chunks arrive but are rejected → wire/reassembly defect;
   - pieces complete but no accepted media reaches output → auth/resync/output boundary;
   - producer exists but media/buffer readiness is slow → scheduler/forward reserve;
   - proven localhost Range/reopen mismatch only → HTTP logical-offset semantics.
4. **Make exactly one bounded, stage-specific behavior increment.** Do not widen peer counts, global startup timeout, handshake timeout, DHT budgets, cache sizes, request depth, concurrency or Media3/HTTP policy merely to hide a startup failure.
5. **Require exact-head validation before merge.** Android CI, applicable core P2P/unit tests, lint/build/instrumentation checks, signed ARM TV APK/source packaging and real playback smoke must pass for behavior changes.
6. **Repeat field evidence after each behavior increment.** A green synthetic/CI run does not by itself prove startup/zap parity.
7. **Broad acceptance remains last.** Fixed same-device A/B, 20 rapid switches, weak-network/peer-loss tests and 2h/8h ARM TV Box soak follow only after V4d functional blockers are closed.

## V4d performance target

For healthy same-device swarms where Televizo + Ace Stream Engine starts/switches in roughly 2–4 seconds, the autonomous embedded runtime should move toward comparable startup/zap success and latency without using the external engine as a runtime fallback. Optimization must follow measured stage evidence rather than relaxing safety bounds.

## Invariants that remain binding

- A discovered endpoint is not equivalent to connected, handshaked, useful or producing evidence.
- A useful/unchoked peer is not equivalent to a producing peer; production requires accepted media at the live-output boundary.
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
