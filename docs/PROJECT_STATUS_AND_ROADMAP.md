# Project status and roadmap

_Last updated: 2026-08-20_

This is the canonical current-state and next-action document. Dated field reports remain immutable
evidence; when an older plan describes a different current increment, this page wins.

## Baseline and branch decisions

- This increment starts from `main` commit `eeb33f5` (merged PR #155).
- The terminal TCP-pool guard from PR #158 commit `bd64e24` is incorporated here. It closes a
  deterministic late-peer-start race, but the 2026-08-20 field run proves that teardown correctness
  alone does not solve startup/playback.
- PR #156 is closed and must not be merged as written. Its warm-routing state lived inside one
  `AceDhtIterativeDiscovery`, while production creates new discovery wrappers for initial, probe,
  expansion, refill and channel changes. Its same-instance test therefore did not prove production
  reuse, and its field interpretation is not reliable enough to ship.
- The post-#155 docs branch is evidence input only. Its old PR #156 conclusions are superseded by
  this page rather than merged literally.
- Historical diagnostic and already-pruned branches are not production merge candidates.

## Latest field evidence — 2026-08-20

Source: `myscanerIPTV-logs-1787212775216.txt`, captured from the TV build containing the terminal
pool guard. The full evidence record is in
[`testing/playback-log-analysis-2026-08-20.md`](testing/playback-log-analysis-2026-08-20.md).

### Startup handoff race is confirmed

For `channelId=13`, the speculative direct runtime made useful network progress just before the
fixed metadata handoff boundary:

- startup DHT returned three peers at about 7.527 s;
- TCP connected at about 7.810 s;
- handshake was accepted at about 8.007 s;
- the fixed 8 s direct/metadata coordinator then cancelled that runtime;
- a second metadata-derived runtime started from an independent cold route, obtained tracker=1 and
  DHT=0, and never produced media.

Two `phase=initial` records within one player request are two different runtimes. The failure is not
fixed by a larger unconditional timeout: the coordinator must preserve only current, qualified
direct progress for a short, non-renewable grace while retaining one absolute request deadline.

### DHT reuse was missing from the production lifetime

Production created a new DHT wrapper/walker for every discovery cycle, so instance-local warm nodes
could not survive even initial-to-probe, much less a channel switch. Bootstrap hostnames were also
resolved before the query loop, which delayed a known responsive node behind DNS.

### Player boundary is a separate release blocker

For `channelId=16`, P2P reached first media at about 4.199 s, buffer-ready at about 4.335 s and the
loopback reader at about 4.39 s. The local source delivered 20,967,640 bytes over about 32.4 s without
a Media3 load error, but READY appeared only after EOF during the next switch and was ignored as
stale. This is evidence of a TS/demux/player-qualification blocker, not peer starvation.

Do not enable the existing discontinuity gate blindly at initial startup: its PSI assumptions are
not yet broad enough for all live streams. Add PAT/PMT/PID/continuity/random-access and Media3 track
telemetry first, then make a separate fixture-backed behavior change.

### The channel drawer had a stale transient-state bug

Only the selected channel was updated in the process-local availability cache. When A was cancelled
by selection of B, A could remain `SEARCHING` forever even though only one generation/runtime was
active. This explains the simultaneous “поиск пиров…” labels in the screenshots; it did not prove
that several P2P engines were still running.

## Current bounded implementation increment

1. **Terminal peer-pool ownership.** A closed pool rejects a late `startPeer` before and under its
   ownership mutex, and the regression verifies that no transport is opened after close.
2. **Engine-owned DHT routing memory.** Live and metadata DHT wrappers share verified routing
   contacts across newly created discovery instances and channel runtimes. The memory stores nodes,
   not swarm peers; it is TTL-bounded to five minutes, LRU-bounded to 32 contacts, applies node-ID
   and IPv4-network diversity, and evicts failed or node-ID-mismatched remembered contacts.
3. **Warm query without serial DNS.** Verified contacts can be queried immediately while bootstrap
   DNS is resolved through a globally bounded worker pool and per-lookup pipeline. The scheduler
   reserves a bootstrap lane and query token until one bootstrap request launches, distributes the
   first wave across hostnames and keeps the original DHT packet, peer, time and cancellation bounds.
   Diagnostics distinguish cache hits from actual warm-contact queries, total queries and failures.
4. **Progress-aware direct handoff.** At the 8 s soft boundary, only the current direct runtime can
   request a fixed two-second qualification grace. Historical timeline milestones do not control
   behavior. A stale/disconnected runtime gets no grace, the deadline never renews, and direct,
   metadata startup and fallback remain under one 60 s content-preparation bound. Expiry closes
   the active runtime only while that preparation still owns the current generation.
5. **Honest availability state.** Selecting a new channel changes only superseded `SEARCHING`
   entries back to `UNCHECKED`; completed READY/PLAYING/ERROR evidence is retained.

This increment intentionally does not claim to solve the separate Media3/TS problem.

## Validation gates

Automated gates required on the exact integration head:

1. `:core:p2p:testDebugUnitTest`
2. `:core:data:testDebugUnitTest`
3. `:feature:player:testDebugUnitTest`
4. `:core:player:testDebugUnitTest`
5. `:core:player-vlc:testDebugUnitTest`
6. `lintDebug`, `:app:assembleDebug` and `:app:assembleDebugAndroidTest`
7. Python tooling tests and the real `TorrentTvPlaybackSmokeTest`

Real-device gate on the same TV Box:

- compare the fixed channel matrix against baseline `eeb33f5`;
- repeat A↔B switches and 20 rapid switches without explicit stop between every resolution;
- record discovery candidates, cache hits, warm queries, connect, handshake, current useful/producing
  state, first media, buffer-ready, HTTP open/read, Media3 READY/first frame and teardown ownership;
- verify old loopback servers and pools close and no stale URL/READY can win;
- judge median/tail startup and success rate, not a single successful channel.

## Decision order

1. Finish exact-head unit/build gates for the bounded DHT/handoff/teardown/UI increment.
2. Run the same-device field matrix. Merge performance claims only if repeated switches improve
   without resource leaks or a lower success rate.
3. Add deterministic A→B→C integration ownership coverage across DHT/refill/handoff.
4. Instrument the confirmed TS/demux/player boundary and add a continuous live MPEG-TS fixture that
   must reach READY before EOF.
5. Change startup TS qualification only after PAT/PMT/random-access evidence identifies the first
   invalid transition.
6. Finish weak-network, peer-loss, 2 h and 8 h ARM TV Box acceptance after both blockers close.

## Binding invariants

- Discovered endpoints are not connected, handshaked, requestable or producing peers.
- Warm routing memory stores only responsive/self-consistent DHT node contacts; it never treats old
  swarm peers as current availability. Strict BEP-42 validation remains a telemetry-first follow-up.
- Diagnostics are observational and must not be used as stale control-plane state.
- A 40-character Ace `content_id` is a transport identity, not automatically a BitTorrent infohash.
- Do not increase the 60 s content-preparation bound, 30 s no-connected-peer guard, DHT budgets,
  peer caps, request depth or output buffers to conceal a failure.
- Preserve generation/session ownership and complete non-cancellable cleanup on supersession.
- Do not change generic IPTV or normal BitTorrent behavior to fix Ace Live.
- Do not add an external Ace Stream Engine runtime dependency or fallback.

## Documentation map

- `PROJECT_STATUS_AND_ROADMAP.md` — canonical current status and next gate.
- `PLAYBACK_STATUS.md` — concise user-visible playback state and acceptance criteria.
- `testing/playback-log-analysis-2026-08-20.md` — latest field evidence.
- `ROADMAP.md` and `ACE_LIVE_IMPLEMENTATION_PLAN.md` — long-form history and architecture.
- `ACE_LIVE_FIELD_VALIDATION_*.md` — immutable dated evidence.
- `P2P_RUNTIME_NOTES.md`, `P2P_CONTENT_TRANSPORT.md` and `ACE_LIVE_STARTUP_TIMELINE.md` — runtime,
  identity and diagnostics contracts.
