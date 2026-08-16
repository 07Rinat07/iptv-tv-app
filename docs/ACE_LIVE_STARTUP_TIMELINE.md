# Ace Live startup timeline — V4d

V4d closes the startup/zap latency blocker with field evidence before changing transport or buffering behaviour. The timeline is monotonic from one playback preparation request and uses first-write-wins milestones so speculative direct/metadata paths, retries, peer reconnects and HTTP reopens cannot rewrite the original startup evidence.

## Canonical milestones

`transport_selection → direct_attempt / metadata_attempt → discovery_completed → first_candidate → connected → handshake → useful_window → first_media → buffer_ready → http_reader_open → http_first_read → media3_ready → first_frame`

Each exported milestone carries `elapsed_ms` relative to the same preparation origin. Missing milestones remain missing rather than receiving synthetic timestamps. `discovery_completed` is intentionally separate from `first_candidate`: an empty or failed discovery round is timing evidence, but it is not peer availability evidence.

## Runtime diagnostics bridge

`AceLiveStartupTimelineDiagnostics` is the observational bridge used by runtime hook points. A first occurrence emits the stable status `embedded_ace_live_startup_timeline` with `phase=<milestone>, elapsed_ms=<value>`. Reconnects, repeated discovery/refill rounds and reader reopens cannot re-emit the canonical milestone because the underlying timeline is first-write-wins.

The bridge deliberately catches diagnostics-sink failures after recording the milestone. Diagnostics therefore cannot change startup ownership, scheduler decisions, retry behaviour, buffer state or failure bounds.

The runtime bridge is now wired to authoritative existing events:

- one timeline is created at each Ace Live playback preparation origin; the same timeline is shared by the speculative direct and metadata branches of a `content_id` race;
- transport selection, speculative direct start and metadata-resolution start map only to their explicit phases;
- completion of the first real peer-discovery orchestration marks `discovery_completed`; `first_candidate` is emitted only when that result actually contains at least one endpoint;
- TCP `TransportConnected` and accepted peer handshake remain separate milestones;
- `useful_window` is emitted from the existing peer-production snapshot only after requestability has been evaluated against the authoritative live cursor;
- the first authenticated, MPEG-TS-resynchronized byte range accepted by `AceLiveMediaBuffer` marks `first_media`;
- the existing startup-buffer policy decision separately marks `buffer_ready` immediately before startup completion;
- a real live-loopback consumer open marks `http_reader_open`, while the first positive loopback delivery marks `http_first_read`.

`media3_ready` and `first_frame` deliberately remain unwired in this increment. They belong to the next player-layer PR together with Media3 load/retry and localhost request/reopen evidence, so generic IPTV/Media3 behaviour is not mixed with transport instrumentation.

## Field update — 16 августа 2026

The second TV Box export confirms that V4d is not only a cold-start problem. During roughly 75 seconds of retained steady-state diagnostics, four endpoints were discovered but only one peer remained handshaked. The authoritative loopback reader stayed at exactly `458656` playable bytes and about one second of headroom while `windowUseful/producing` repeatedly flipped. User-visible behaviour matched that starvation pattern: long starts, choppy audio, frozen/missing video and 60-second preparation failures.

The same export also exposed an observability problem: volatile `windowUseful/producing` transitions generated 107 of the retained 120 rows, so useful startup evidence was pushed out of the bounded history. `AceLivePeerDiagnosticsReporter` therefore treats only `discovered/connected/handshaked` changes as immediate lifecycle events; volatile live-edge quality remains available in periodic full snapshots.

For weak tracker fast-path startup, a single endpoint is still not evidence of a useful producer. Startup DHT probes now return after the first valid DHT endpoint so TCP validation of an alternative can begin immediately. The existing 7-second probe budget, two bounded rounds, full background expansion and all absolute failure bounds remain unchanged.

Detailed evidence is recorded in [`ACE_LIVE_FIELD_VALIDATION_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16.md).

## Remaining V4d sequence

1. Gate this canonical runtime timeline bridge on exact-head Android CI, including core P2P tests and the real Torrent TV smoke without external Ace Engine.
2. In a separate player-layer increment, record Media3 load/retry, localhost request method/`Range`, reader close/reopen reason, `READY` and first-frame evidence without changing generic IPTV behaviour.
3. Use measured Media3 requests to decide whether bounded logical-offset HTTP reopen/resume is required; do not implement resume speculatively.
4. Add a bounded forward playback reserve around the authoritative consumer cursor if the field timeline still shows the player living near the live edge.
5. Correct pre-READY pressure authority so parser/read bursts cannot masquerade as playback bitrate; preserve post-READY authoritative reader semantics.
6. If the producer set still remains at one handshaked peer with insufficient headroom, add bounded competitive/fresh-candidate diversity based on connected/handshaked/useful evidence rather than discovered-count alone.
7. Add decoder-safe startup warmup using the existing TS sync/PAT/PMT/random-access evidence only after the preceding boundaries are measured.
8. Only then proceed to the fixed same-device A/B matrix, 20 rapid switches, weak network, peer loss, and 2h/8h ARM soak.

## Invariants

- Timeline collection is observational only. It must not change startup/no-peer/stall bounds, request depth, refill/replacement budgets, recovery jumps or TS discontinuity handling.
- The runtime's existing startup clock used by buffer policy and guards is not replaced by the canonical preparation timeline; the new clock is diagnostics-only.
- Generic IPTV player policy remains unchanged; P2P-specific Media3 policy stays isolated at the existing player boundary.
- Repeated peer callbacks, discovery/refill rounds and reader reopens may add their existing diagnostics, but the canonical startup milestone keeps its first timestamp.
- A discovered endpoint is not treated as connected, handshaked, useful or producing evidence.
- A metadata/window update is not considered useful until current peer-quality state confirms requestability against the authoritative cursor.
- Startup peer-diversity work must reduce time-to-alternative-candidate without increasing the absolute discovery/startup/no-peer bounds.
- The direct 8-second soft window must not be shortened or bypassed before the canonical field timeline proves that metadata is actionable earlier.
