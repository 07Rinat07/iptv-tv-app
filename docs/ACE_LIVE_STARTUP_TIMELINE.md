# Ace Live startup timeline — V4d

V4d closes the startup/zap latency blocker with field evidence before changing transport or buffering behaviour. The timeline is monotonic from one playback preparation request and uses first-write-wins milestones so speculative direct/metadata paths, retries, peer reconnects and HTTP reopens cannot rewrite the original startup evidence.

## Canonical milestones

`transport_selection → direct_attempt / metadata_attempt → discovery_completed → first_candidate → connected → handshake → useful_window → first_media → buffer_ready → http_reader_open → http_first_read → media3_ready → first_frame`

Each exported core milestone carries `elapsed_ms` relative to the same Ace Live preparation origin. Missing milestones remain missing rather than receiving synthetic timestamps. `discovery_completed` is intentionally separate from `first_candidate`: an empty or failed discovery round is timing evidence, but it is not peer availability evidence.

## Runtime diagnostics bridge

`AceLiveStartupTimelineDiagnostics` is the observational bridge used by core runtime hook points. A first occurrence emits the stable status `embedded_ace_live_startup_timeline` with `phase=<milestone>, elapsed_ms=<value>`. Reconnects, repeated discovery/refill rounds and reader reopens cannot re-emit the canonical milestone because the underlying timeline is first-write-wins.

The bridge deliberately catches diagnostics-sink failures after recording the milestone. Diagnostics therefore cannot change startup ownership, scheduler decisions, retry behaviour, buffer state or failure bounds.

PR #128 wired the bridge to authoritative existing events and passed exact-head Android CI #549 before merge to `main` as `dea23c65a2b6c0865f91870806a4db53e5b0d0f3`:

- one timeline is created at each Ace Live playback preparation origin; the same timeline is shared by speculative direct and metadata branches of a `content_id` race;
- transport selection, speculative direct start and metadata-resolution start map only to their explicit phases;
- completion of the first real peer-discovery orchestration marks `discovery_completed`; `first_candidate` is emitted only when that result actually contains at least one endpoint;
- TCP `TransportConnected` and accepted peer handshake remain separate milestones;
- `useful_window` is emitted from existing peer-production/requestability evidence;
- first authenticated, MPEG-TS-resynchronized bytes accepted by `AceLiveMediaBuffer` mark `first_media`;
- the existing startup-buffer policy decision separately marks `buffer_ready` immediately before startup completion;
- a real live-loopback consumer open marks `http_reader_open`, while the first positive loopback delivery marks `http_first_read`.

## Player boundary contract

The next V4d boundary is intentionally split into a contract PR and a wiring PR so Media3 instrumentation cannot silently change playback behaviour.

`P2pPlayerBoundaryTelemetryTracker` already owns P2P-only `BUFFERING`, `READY`, first-frame and rebuffer accounting. The contract is extended with:

- `load_started` — first Media3 P2P load start only;
- `load_completed` — first successful Media3 P2P load completion only;
- `load_error` — sparse failure evidence with an explicit load-duration value and cumulative count;
- `load_retry` — explicit recovery/retry evidence with a cumulative count.

Repeated successful live-chunk load starts/completions do not each emit a record, preventing the same bounded-diagnostics flood previously seen with volatile peer-quality events. Internal counters still advance so a later error/retry record can show how much load activity preceded it. This contract owns no retry, seek, timeout, LoadControl or P2P policy.

The immediately following wiring PR must connect this contract to the actual Media3 P2P session and extend localhost evidence with request method, `Range`/requested offset, reader close/reopen and close reason. It must also correlate `READY`/first-frame evidence with the active P2P request/session. Until that wiring lands, player `elapsed_ms` remains relative to the existing player-start timestamp and must not be misrepresented as the core preparation-origin clock.

## Field update — 16 августа 2026

The second TV Box export confirms that V4d is not only a cold-start problem. During roughly 75 seconds of retained steady-state diagnostics, four endpoints were discovered but only one peer remained handshaked. The authoritative loopback reader stayed at exactly `458656` playable bytes and about one second of headroom while `windowUseful/producing` repeatedly flipped. User-visible behaviour matched that starvation pattern: long starts, choppy audio, frozen/missing video and 60-second preparation failures.

The same export also exposed an observability problem: volatile `windowUseful/producing` transitions generated 107 of the retained 120 rows, so useful startup evidence was pushed out of the bounded history. `AceLivePeerDiagnosticsReporter` therefore treats only `discovered/connected/handshaked` changes as immediate lifecycle events; volatile live-edge quality remains available in periodic full snapshots.

For weak tracker fast-path startup, a single endpoint is still not evidence of a useful producer. Startup DHT probes now return after the first valid DHT endpoint so TCP validation of an alternative can begin immediately. The existing 7-second probe budget, two bounded rounds, full background expansion and all absolute failure bounds remain unchanged.

Detailed evidence is recorded in [`ACE_LIVE_FIELD_VALIDATION_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16.md).

## Remaining V4d sequence

1. ✅ Canonical core runtime timeline: PR #128, exact-head Android CI #549, merged to `main`.
2. 🚧 P2P player load telemetry contract: typed bounded load start/completion/error/retry evidence with focused unit coverage.
3. Wire the player contract to actual Media3 Analytics/load callbacks and add localhost request method/`Range`, reader close/reopen reason, track readiness, `READY` and first-frame correlation without changing generic IPTV behaviour.
4. Use measured Media3 requests to decide whether bounded logical-offset HTTP reopen/resume is required; do not implement resume speculatively.
5. Add a bounded forward playback reserve around the authoritative consumer cursor if the field timeline still shows the player living near the live edge.
6. Correct pre-READY pressure authority so parser/read bursts cannot masquerade as playback bitrate; preserve post-READY authoritative reader semantics.
7. If the producer set still remains at one handshaked peer with insufficient headroom, add bounded competitive/fresh-candidate diversity based on connected/handshaked/useful evidence rather than discovered-count alone.
8. Add decoder-safe startup warmup using the existing TS sync/PAT/PMT/random-access evidence only after the preceding boundaries are measured.
9. Only then proceed to the fixed same-device A/B matrix, 20 rapid switches, weak network, peer loss, and 2h/8h ARM soak.

## Invariants

- Timeline collection is observational only. It must not change startup/no-peer/stall bounds, request depth, refill/replacement budgets, recovery jumps or TS discontinuity handling.
- The runtime's existing startup clock used by buffer policy and guards is not replaced by the canonical preparation timeline; the new clock is diagnostics-only.
- Generic IPTV player policy remains unchanged; P2P-specific Media3 policy stays isolated at the existing player boundary.
- Repeated peer callbacks, discovery/refill rounds and reader reopens may add their existing diagnostics, but the canonical startup milestone keeps its first timestamp.
- A discovered endpoint is not treated as connected, handshaked, useful or producing evidence.
- A metadata/window update is not considered useful until current peer-quality state confirms requestability against the authoritative cursor.
- Startup peer-diversity work must reduce time-to-alternative-candidate without increasing the absolute discovery/startup/no-peer bounds.
- The direct 8-second soft window must not be shortened or bypassed before the canonical field timeline proves that metadata is actionable earlier.
