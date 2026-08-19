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

## Current V4d blocker: post-#146 producer-boundary field evidence

Bounded producer-gap observability is now landed in production via PR #146. The current gate is **real-device evidence from the post-#146 `main`**, not another behavior change.

Run one short focused real-device test on a channel that previously reached a useful/unchoked peer without media production. Capture both `embedded_ace_live_peer_quality` and the new `embedded_ace_live_producer_gap` events. The decisive persistent state is:

- `handshaked=1` or greater;
- `windowUseful=1` or greater;
- `unchoked=1` or greater;
- `producing=0`;
- `aggregate_bps=0`;
- `embedded_ace_live_producer_gap state=active` persists rather than quickly resolving.

Do **not** change scheduler policy, request depth/concurrency, discovery, tracker/DHT, TCP/reconnect, timeouts, peer limits, cache/buffer bounds, HTTP/Media3 behavior or add an external Ace Stream fallback before this field gate is classified.

If the explicit producer gap persists, the next narrow increment is observational instrumentation across the producer boundary so the first missing transition can be localized before a behavior fix:

`scheduler scheduled -> request selected -> request sent -> chunk ingress -> chunk accepted/rejected -> piece completed -> authenticated media output`.

Only after that chain identifies the first failing boundary should the next behavior PR be selected.

## Decision order from here

1. **Producer-gap observability landed.** PR #146 passed exact-head Android CI and is merged into `main`; no runtime policy changes were included.
2. **Run the post-#146 focused field validation.** Prefer a channel that reaches `handshaked=1`, `windowUseful=1`, `unchoked=1`, `producing=0`, plus one known-good control when practical.
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
4. **If producer-boundary evidence is still ambiguous, add one bounded observational increment.** Instrument scheduler dispatch/request routing/chunk ingress/piece completion/authenticated output without changing runtime policy.
5. **Make exactly one bounded, stage-specific behavior increment.** Do not widen peer counts, global startup timeout, handshake timeout, DHT budgets, cache sizes, request depth, concurrency or Media3/HTTP policy merely to hide a startup failure.
6. **Require exact-head validation before merge.** Android CI, applicable core P2P/unit tests, lint/build/instrumentation checks, signed ARM TV APK/source packaging and real playback smoke must pass for behavior changes.
7. **Repeat field evidence after each behavior increment.** A green synthetic/CI run does not by itself prove startup/zap parity.
8. **Broad acceptance remains last.** Fixed same-device A/B, 20 rapid switches, weak-network/peer-loss tests and 2h/8h ARM TV Box soak follow only after V4d functional blockers are closed.

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
