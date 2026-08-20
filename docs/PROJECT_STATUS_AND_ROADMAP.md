# Project status and roadmap

_Last updated: 2026-08-20_

This document is the canonical **current-state and next-action view** for the project. Historical field reports and dated V4d notes remain evidence records; when an older document conflicts with this page about the current priority, this page wins until it is updated again.

## Current production baseline

- `main`: `eeb33f5138b0586cd2615e47d26d57bdee0080d7` (merge of PR #155).
- PR #136 (`fix(p2p): complete BEP-9 content metadata negotiation`) passed its exact-head Android CI and real field gate: the known-infohash control remained playable and a genuine Content-ID metadata/catalog resolution succeeded. It is merged into `main`.
- PR #144 (`fix(p2p): restore tracker during startup full expansion`) passed exact-head Android CI and is merged into `main`. Short startup probe rounds remain DHT-only, while the final bounded startup full-expansion can reacquire candidates from tracker + DHT.
- PR #145 synchronized this canonical roadmap after the Content-ID and discovery gates closed.
- PR #146 (`feat(p2p): persist V4d producer-gap diagnostics`) passed exact-head Android CI on head `7899d86619958142553ab57ef8910ff98490b24b` and is merged into `main`. It adds bounded `embedded_ace_live_producer_gap` entry/periodic/resolution diagnostics only; it does not change runtime scheduling or transport behavior.
- PR #147 synchronized the roadmap after the post-#146 TV Box evidence reproduced a persistent useful/unchoked/no-producer stall.
- PR #149 (`feat(p2p): expose V4d producer-boundary stages`) passed exact-head Android CI on head `d9910420b7a71e42132cefaea457f7b44737076f` and is merged into `main`. It adds bounded `embedded_ace_live_producer_boundary` observational stage counters for scheduler output, chunk ingress, chunk disposition and contiguous piece completion; runtime scheduling/transport policy is unchanged.
- PR #150 (`fix(p2p): isolate StreamHave by stream index`) passed exact-head Android CI #638 on head `efb20272484b7321eace470edce9de1f33137196` and is merged into `main`. It fixes a deterministic peer-wire correctness bug so a `StreamHave` for a foreign stream index cannot advance the primary stream-0 live window; the adversarial regression test remains in production coverage.
- PR #151 synchronized this canonical roadmap after PRs #149 and #150.
- PR #152 (`fix(p2p): wire selected producer-boundary telemetry`) passed exact-head Android CI #643 on head `ef6db9dbdd08e5f6336e21df0e7ab6a95a25d21e` and is merged into `main`. It wires the already-existing `selected` producer-boundary observation only after the normal readiness/window/ownership filters accept an outbound request; request selection behavior itself is unchanged.
- PR #153 synchronized this canonical roadmap after PR #152; runtime behavior was unchanged.
- PR #155 (`fix(p2p): use bounded startup DHT candidate batch`) passed its exact-head CI and TV Box field gate and is merged into `main` as `eeb33f5138b0586cd2615e47d26d57bdee0080d7`. It changes only the bounded startup DHT early-return target from 1 to 4 candidates; the 7-second probe budget, DHT request/query limits, active-peer limits and 30s/60s startup guards remain unchanged. Field evidence improved startup diversity to `dht=SUCCEEDED/3` and `/4`, while remaining failures moved to candidate qualification/connection and producer progress.
- PR #156 (`fix(p2p): reuse warm DHT routing seeds across swarms`) is **open/draft and not merge-approved**. Its first implementation reused verified responsive DHT nodes across swarms with a 5-minute TTL, 32-node LRU bound and at most 4 warm seeds, but fresh TV Box evidence on 2026-08-20 showed worse later-switch diversity (`dht=SUCCEEDED/0` repeatedly) and no producing startup in the sampled switches. A corrective regression and code increment now reserve one concurrent startup branch for normal bootstrap when `searchBranching > 1`; exact-head CI and repeat TV Box evidence are required before any merge.
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

## Current V4d blocker: validate corrected warm routing without losing bootstrap diversity

PR #155 closed the specific one-candidate startup-DHT bottleneck: same-device field validation observed bounded `dht=SUCCEEDED/3` and `/4` batches and the first two tested Torrent TV channels played normally. The next experiment, PR #156, attempted to reuse only DHT nodes that had answered a valid KRPC query so later swarms would not rebuild routing state from cold bootstrap every time.

Fresh TV Box evidence from the first #156 build (`cc6691cc59c759cdd5797535e78421d91d39e855`) failed that field gate. Representative later switches repeatedly showed tracker `SUCCEEDED/1` with DHT `SUCCEEDED/0`, followed by `connect_failed`; one earlier startup reached `discovered=3`, `connected=2`, `handshaked=2`, but remained `producing=0`. This is materially worse than the post-#155 candidate-diversity baseline and therefore blocks merge regardless of deterministic CI status.

The bounded root-cause hypothesis is scheduler ordering, not a request-timeout or budget shortage: production `searchBranching=4`, the first #156 implementation allowed up to 4 known-ID warm seeds, and known-ID candidates outrank unresolved bootstrap candidates in the XOR-distance queue. Four warm entries could therefore occupy the entire first concurrent wave and delay normal bootstrap until a later wave.

Corrective commits in PR #156 now add regression coverage for that condition and reserve a bootstrap lane without widening any safety bound. With `searchBranching=4`, at most 3 warm seeds enter the startup frontier; with serial `searchBranching=1`, the original one-warm-seed behavior is preserved. The code correction is `2838c0ec2c7143d6415d70e1caeb73b577fb8e72`; this documentation sync will produce a later final PR head.

The current gate is therefore:

`corrective regression -> final exact-head Android CI -> APK from that exact head -> same TV Box switch sweep -> compare DHT candidates/TCP/handshake/first_media against #155 -> merge or reject #156`.

No timeout, DHT budget, query cap, peer target/max, TCP limit, scheduler depth, buffer/cache, HTTP/Media3 policy or external Ace fallback may be widened to make this gate pass.

## Decision order from here

1. **PR #155 remains the production comparison baseline.** It is merged and field-proved the bounded 1 -> 4 startup DHT candidate change can improve diversity without widening the 7-second probe or other safety limits.
2. **Finish the corrective #156 regression.** Preserve both properties: a later swarm can reuse a verified responsive warm DHT node, and production-sized concurrent lookup cannot let warm routing consume every startup slot ahead of normal bootstrap.
3. **Require final exact-head CI after this roadmap sync.** Core P2P tests, the real Torrent TV smoke when infrastructure is healthy, lint/build/instrumentation and signed ARM TV APK/source packaging must all correspond to the same final PR head.
4. **Repeat the same TV Box field sweep before merge.** Compare cold vs later-switch DHT latency, `dht=SUCCEEDED/N`, total discovered candidates, TCP connects, handshakes/useful peers, `first_media`, producer-gap state, stalls and errors against the post-#155 run. Green CI alone is insufficient.
5. **Reject or revise #156 if later-switch diversity remains below #155.** Persistent routing is an optimization, not a reason to accept fewer viable candidates.
6. **Only after #156 passes field validation, continue the bounded hardening sequence:** cross-channel candidate prewarm after healthy `first_media`, then peer qualification/failure memory, then rapid-switch resource ownership. Each item requires separate regression/field evidence and must retain strict CPU/network/heap bounds.
7. **Keep producer-boundary evidence active.** Better discovery does not prove playback: useful/unchoked peers can still remain `producing=0`, so `embedded_ace_live_producer_gap` and producer-boundary telemetry remain acceptance evidence.
8. **Broad acceptance remains last.** Fixed same-device A/B, 20 rapid switches, weak-network/peer-loss tests and 2h/8h ARM TV Box soak follow only after the functional acquisition/producer blockers are closed.

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
