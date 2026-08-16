# Ace Live startup timeline — V4d

V4d closes the startup/zap latency blocker with field evidence before changing transport or buffering behaviour. The timeline is monotonic from one player preparation request and uses first-write-wins milestones so retries and HTTP reopens cannot rewrite the original startup path.

## Canonical milestones

`transport_selection → direct_attempt / metadata_attempt → first_candidate → connected → handshake → useful_window → first_media → buffer_ready → http_reader_open → http_first_read → media3_ready → first_frame`

Each exported milestone carries `elapsed_ms` relative to the same startup origin. Missing milestones remain missing rather than receiving synthetic timestamps.

## Runtime diagnostics bridge

`AceLiveStartupTimelineDiagnostics` is the observational bridge used by runtime hook points. A first occurrence emits the stable status `embedded_ace_live_startup_timeline` with `phase=<milestone>, elapsed_ms=<value>`. Reconnects, HTTP reader reopens and Media3 retries cannot re-emit the canonical milestone because the underlying timeline is first-write-wins.

The bridge deliberately catches diagnostics-sink failures after recording the milestone. Diagnostics therefore cannot change startup ownership, scheduler decisions, retry behaviour, buffer state or failure bounds.

The runtime-event adapter centralizes the evidence mapping before the large call sites are wired:

- transport selection, speculative direct start and metadata start map only to their explicit phases;
- discovery marks `first_candidate` only after at least one candidate actually exists;
- TCP connect and accepted handshake remain separate milestones;
- `useful_window` is accepted only from the peer-quality snapshot after requestability has been evaluated against the authoritative cursor;
- authenticated/resynchronized accepted output marks `first_media`, while startup policy readiness separately marks `buffer_ready`;
- only a real GET reader open maps `http_reader_open`; HEAD and delivery callbacks do not synthesize that milestone;
- the first positive loopback read maps `http_first_read`.

## Field update — 16 августа 2026

The second TV Box export confirms that V4d is not only a cold-start problem. During roughly 75 seconds of retained steady-state diagnostics, four endpoints were discovered but only one peer remained handshaked. The authoritative loopback reader stayed at exactly `458656` playable bytes and about one second of headroom while `windowUseful/producing` repeatedly flipped. User-visible behaviour matched that starvation pattern: long starts, choppy audio, frozen/missing video and 60-second preparation failures.

The same export also exposed an observability problem: volatile `windowUseful/producing` transitions generated 107 of the retained 120 rows, so useful startup evidence was pushed out of the bounded history. `AceLivePeerDiagnosticsReporter` therefore treats only `discovered/connected/handshaked` changes as immediate lifecycle events; volatile live-edge quality remains available in periodic full snapshots.

For weak tracker fast-path startup, a single endpoint is still not evidence of a useful producer. Startup DHT probes now return after the first valid DHT endpoint so TCP validation of an alternative can begin immediately. The existing 7-second probe budget, two bounded rounds, full background expansion and all absolute failure bounds remain unchanged.

Detailed evidence is recorded in [`ACE_LIVE_FIELD_VALIDATION_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16.md).

## Remaining V4d sequence

1. Merge the field-evidence peer-diversity/diagnostic-retention increment only after exact-head CI and real Torrent TV smoke are green.
2. Create one canonical startup diagnostics bridge at the playback preparation origin and wire the existing Ace transport/discovery/TCP/output/loopback callbacks into it.
3. Wire Media3 `READY`, first-frame and load/retry evidence at the player layer without changing generic IPTV behaviour.
4. Re-run the fixed TV Box channel matrix and identify the measured dominant segment before touching the 8-second direct soft window.
5. Correct pre-READY pressure authority so parser/read bursts cannot masquerade as playback bitrate; preserve post-READY authoritative reader semantics.
6. If the producer set still remains at one handshaked peer with about one-second headroom, add bounded fresh-candidate diversity/recovery based on handshaked/useful evidence rather than discovered-count alone.
7. Only then proceed to the broader acceptance matrix: 20 rapid switches, weak network, peer loss, and 2h/8h ARM soak.

## Invariants

- Timeline collection is observational only. It must not change startup/no-peer/stall bounds, request depth, refill/replacement budgets, recovery jumps or TS discontinuity handling.
- Generic IPTV player policy remains unchanged; P2P-specific Media3 policy stays isolated at the existing player boundary.
- Repeated peer callbacks, retries and reader reopens may add separate diagnostics, but the canonical startup milestone keeps its first timestamp.
- A discovered endpoint is not treated as connected, handshaked, useful or producing evidence.
- A metadata/window update is not considered useful until current peer-quality state confirms `windowUseful=true` against the authoritative cursor.
- Startup peer-diversity work must reduce time-to-alternative-candidate without increasing the absolute discovery/startup/no-peer bounds.
- The direct 8-second soft window must not be shortened or bypassed before the canonical field timeline proves that metadata is actionable earlier.
