# Project status and roadmap

_Last updated: 2026-08-19_

This document is the canonical **current-state and next-action view** for the project. Historical field reports and dated V4d notes remain evidence records; when an older document conflicts with this page about the current priority, this page wins until it is updated again.

## Current production baseline

- `main`: `d010ba1d7e636327f9bf55e41c401256e02ca763` (merge of PR #146).
- PR #136 (`fix(p2p): complete BEP-9 content metadata negotiation`) passed its exact-head Android CI and real field gate: the known-infohash control remained playable and a genuine Content-ID metadata/catalog resolution succeeded. It is merged into `main`.
- PR #144 (`fix(p2p): restore tracker during startup full expansion`) passed exact-head Android CI and is merged into `main`. Short startup probe rounds remain DHT-only, while the final bounded startup full-expansion can reacquire candidates from tracker + DHT.
- PR #145 synchronized this canonical roadmap after the Content-ID and discovery gates closed.
- PR #146 (`feat(p2p): persist V4d producer-gap diagnostics`) passed exact-head Android CI on head `7899d86619958142553ab57ef8910ff98490b24b` and is merged into `main`. It adds bounded `embedded_ace_live_producer_gap` entry/periodic/resolution diagnostics only; it does not change runtime scheduling or transport behavior.
- Existing safety bounds remain unchanged: no increase to the 60-second preparation deadline, no-connected-peer guard, handshake/request timeouts, DHT budgets, peer target/max, scheduler/recovery bounds, output buffer, HTTP/Media3 policy, generic IPTV or normal BitTorrent behavior.
- Embedded Torrent TV/Ace Live remains self-contained; an installed external Ace Stream Engine is not a production runtime dependency or fallback.

## Closed blocker: Content-ID transport metadata parity

The Content-ID metadata-resolution gate that blocked V4d live-peer work is closed by PR #136. A real multi-channel field sweep proved both required properties on the production candidate: known-infohash playback remained functional and at least one real Content-ID metadata/catalog resolution succeeded.

Diagnostic PR #143 and the related old diagnostic branches are evidence-only and must not be merged into production.

## Latest V4d field evidence — 2026-08-19 post-#146 device run

A fresh real-device run from the merged PR #146 production baseline exercised the new producer-gap diagnostics and now distinguishes two materially different producer-boundary outcomes.

A healthy/control path crossed the boundary quickly:

- handshake accepted at about 1,548 ms;
- useful live window at about 1,584 ms;
- `embedded_ace_live_producer_gap state=active` appeared with `handshaked=1`, `windowUseful=1`, `unchoked=1`, `producing=0`, `aggregate_bps=0`;
- `first_media` appeared at about 2,301 ms;
- the producer gap then emitted `state=resolved` with `producing=1` about 617 ms after gap entry.

A failing path independently crossed discovery, TCP connect, handshake and useful-window qualification, then stalled at the producer boundary:

- first candidate at about 180 ms;
- TCP connected at about 3,041 ms;
- handshake accepted at about 3,242 ms;
- useful live window at about 3,390 ms;
- `embedded_ace_live_producer_gap state=active` appeared with `handshaked=1`, `windowUseful=1`, `unchoked=1`, `producing=0`, `aggregate_bps=0`;
- no corresponding producer-gap resolution or `first_media` was observed for that failing startup before the session fell back into peer loss/reacquisition and ultimately resolved with no available peer.

The same device run also includes secondary acquisition failures where tracker discovery returns one candidate but TCP connection fails, and later DHT probes return zero peers. Those remain real, but they do not erase the independently observed producer-boundary stall and therefore do not justify another broad discovery, timeout or peer-count change.

## Current V4d blocker: localize the producer boundary

The post-#146 field gate is now closed. The remaining ambiguity is inside the producer boundary itself: existing diagnostics prove that a peer can be connected, handshaked, useful and unchoked while no authenticated media producer emerges, but they do not yet show which transition first disappears.

The next narrow increment is therefore **bounded observational instrumentation only** across this chain:

`scheduler scheduled -> request selected -> request sent -> chunk ingress -> chunk accepted/rejected -> piece completed -> authenticated media output`.

The instrumentation must be rate-limited/bounded, tied to the existing startup/session identity, and observational only. It must not change scheduler policy, request depth/concurrency, discovery, tracker/DHT, TCP/reconnect, timeouts, peer limits, cache/buffer bounds, HTTP/Media3 behavior, authentication rules or external Ace Stream behavior.

Only after field evidence identifies the first missing transition should a stage-specific behavior fix be selected.

## Decision order from here

1. **Producer-gap observability landed and field-gated.** PR #146 passed exact-head Android CI and the post-#146 TV Box run captured both a quickly resolved producer gap and a persistent useful/unchoked-no-producer failure.
2. **Add one bounded producer-boundary observational increment.** Instrument scheduler dispatch/request selection/request send/chunk ingress/chunk acceptance or rejection/piece completion/authenticated output without changing runtime policy.
3. **Run focused real-device validation and classify the first missing transition.** Preserve one known-good/control path so successful `active -> resolved` behavior remains visible beside the failing path.
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
