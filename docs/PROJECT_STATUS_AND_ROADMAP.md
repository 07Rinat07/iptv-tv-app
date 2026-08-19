# Project status and roadmap

_Last updated: 2026-08-19_

This document is the canonical **current-state and next-action view** for the project. Historical field reports and dated V4d notes remain evidence records; when an older document conflicts with this page about the current priority, this page wins until it is updated again.

## Current production baseline

- `main`: `0d0af63245e8ff9b2e42d90baa50f7eae3d35cb1` (merge of PR #152).
- PR #136 (`fix(p2p): complete BEP-9 content metadata negotiation`) passed its exact-head Android CI and real field gate: the known-infohash control remained playable and a genuine Content-ID metadata/catalog resolution succeeded. It is merged into `main`.
- PR #144 (`fix(p2p): restore tracker during startup full expansion`) passed exact-head Android CI and is merged into `main`. Short startup probe rounds remain DHT-only, while the final bounded startup full-expansion can reacquire candidates from tracker + DHT.
- PR #145 synchronized this canonical roadmap after the Content-ID and discovery gates closed.
- PR #146 (`feat(p2p): persist V4d producer-gap diagnostics`) passed exact-head Android CI on head `7899d86619958142553ab57ef8910ff98490b24b` and is merged into `main`. It adds bounded `embedded_ace_live_producer_gap` entry/periodic/resolution diagnostics only; it does not change runtime scheduling or transport behavior.
- PR #147 synchronized the roadmap after the post-#146 TV Box evidence reproduced a persistent useful/unchoked/no-producer stall.
- PR #149 (`feat(p2p): expose V4d producer-boundary stages`) passed exact-head Android CI on head `d9910420b7a71e42132cefaea457f7b44737076f` and is merged into `main`. It adds bounded `embedded_ace_live_producer_boundary` observational stage counters for scheduler output, chunk ingress, chunk disposition and contiguous piece completion; runtime scheduling/transport policy is unchanged.
- PR #150 (`fix(p2p): isolate StreamHave by stream index`) passed exact-head Android CI #638 on head `efb20272484b7321eace470edce9de1f33137196` and is merged into `main`. It fixes a deterministic peer-wire correctness bug so a `StreamHave` for a foreign stream index cannot advance the primary stream-0 live window; the adversarial regression test remains in production coverage.
- PR #151 synchronized this canonical roadmap after PRs #149 and #150.
- PR #152 (`fix(p2p): wire selected producer-boundary telemetry`) passed exact-head Android CI #643 on head `ef6db9dbdd08e5f6336e21df0e7ab6a95a25d21e` and is merged into `main`. It wires the already-existing `selected` producer-boundary observation only after the normal readiness/window/ownership filters accept an outbound request; request selection behavior itself is unchanged.
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

## Current V4d blocker: field-localize the first missing producer transition

The bounded producer-boundary observational telemetry from PR #149 and the selected-stage wiring from PR #152 are now both in production. The current blocker is **fresh real-device evidence from current `main`** that identifies where a failing startup first diverges from a known-good/control startup.

The classification chain is:

`scheduler scheduled -> request selected -> request sent -> chunk ingress -> chunk accepted/rejected -> piece completed -> authenticated media output`.

Current production telemetry directly covers `scheduled`, `selected`, `chunk ingress`, `accepted/rejected`, and contiguous `piece completed`, and it must be correlated with `embedded_ace_live_producer_gap` on a focused TV Box run. If a failing run has `scheduled>0` and `selected>0` but still has `chunk_ingress=0`, the remaining ambiguity is at the actual send/write boundary; only then may the next increment add the smallest bounded `sent` observation needed to distinguish selection from successful socket write. No scheduler/request/transport behavior change is permitted until the first missing transition is supported by field evidence.

PR #150 remains an independent peer-wire correctness fix and does not close or bypass this V4d evidence gate.

## Decision order from here

1. **Producer-gap observability landed and field-gated.** PR #146 passed exact-head Android CI and the post-#146 TV Box run captured both a quickly resolved producer gap and a persistent useful/unchoked-no-producer failure.
2. **Producer-boundary observational instrumentation landed.** PR #149 passed exact-head Android CI and is merged into production with bounded scheduler-output, ingress, disposition and piece-completion telemetry; no runtime policy was changed.
3. **Selected-stage observational wiring landed.** PR #152 passed exact-head Android CI #643 and is merged into production; actual outbound selections can now be distinguished from scheduler output without changing which requests are selected.
4. **Run focused real-device validation on current `main`.** Preserve one known-good/control path and one failing path, capture `embedded_ace_live_producer_gap` plus `embedded_ace_live_producer_boundary`, and classify the first missing transition.
5. **If the current telemetry terminates after `selected` but before `chunk ingress`, add only one bounded `sent`/socket-write observation.** Do not change scheduler/request/transport behavior merely because the write boundary is still ambiguous.
6. **Only after field evidence identifies the first missing transition, make exactly one bounded, stage-specific behavior increment.** Do not widen peer counts, global startup timeout, handshake timeout, DHT budgets, cache sizes, request depth, concurrency or Media3/HTTP policy merely to hide a startup failure.
7. **Require exact-head validation before merge.** Android CI, applicable core P2P/unit tests, lint/build/instrumentation checks, signed ARM TV APK/source packaging and real playback smoke must pass for behavior changes.
8. **Repeat field evidence after each behavior increment.** A green synthetic/CI run does not by itself prove startup/zap parity.
9. **Broad acceptance remains last.** Fixed same-device A/B, 20 rapid switches, weak-network/peer-loss tests and 2h/8h ARM TV Box soak follow only after V4d functional blockers are closed.

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
