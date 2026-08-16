# Ace Live startup timeline — V4d

V4d closes the startup/zap latency blocker with evidence before changing transport or buffering behaviour. The timeline is monotonic from one player preparation request and uses first-write-wins milestones so retries and HTTP reopens cannot rewrite the original startup path.

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

The next V4d wiring increment should create one diagnostics bridge at the playback preparation origin and feed these adapters from `AceLiveEmbeddedEngine`/TCP pool/output callbacks. Media3 `READY` and first-frame remain a separate player-layer hook so the P2P core and player instrumentation are not mixed in one behavioral change.

## Invariants

- Timeline collection is observational only. It must not change startup/no-peer/stall bounds, request depth, refill/replacement budgets, recovery jumps or TS discontinuity handling.
- Generic IPTV player policy remains unchanged; P2P-specific Media3 policy stays isolated at the existing player boundary.
- Repeated peer callbacks, retries and reader reopens may add separate diagnostics, but the canonical startup milestone keeps its first timestamp.
- A discovered endpoint is not treated as connected, handshaked, useful or producing evidence.
- A metadata/window update is not considered useful until current peer-quality state confirms `windowUseful=true` against the authoritative cursor.
- Runtime hook points must feed these milestones from the existing Ace Live transport/peer path, loopback HTTP lifecycle and Media3 boundary, then field logs identify the actual latency segment before any optimization.
- The direct 8-second soft window must not be shortened or bypassed from telemetry alone; any startup-race change requires evidence that metadata is actionable earlier while absolute failure bounds remain intact.
