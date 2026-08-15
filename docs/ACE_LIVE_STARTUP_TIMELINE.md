# Ace Live startup timeline — V4d

V4d closes the startup/zap latency blocker with evidence before changing transport or buffering behaviour. The timeline is monotonic from one player preparation request and uses first-write-wins milestones so retries and HTTP reopens cannot rewrite the original startup path.

## Canonical milestones

`transport_selection → direct_attempt / metadata_attempt → first_candidate → connected → handshake → useful_window → first_media → buffer_ready → http_reader_open → http_first_read → media3_ready → first_frame`

Each exported milestone carries `elapsed_ms` relative to the same startup origin. Missing milestones remain missing rather than receiving synthetic timestamps.

## Runtime diagnostics bridge

`AceLiveStartupTimelineDiagnostics` is the observational bridge used by runtime hook points. A first occurrence emits the stable status `embedded_ace_live_startup_timeline` with `phase=<milestone>, elapsed_ms=<value>`. Reconnects, HTTP reader reopens and Media3 retries cannot re-emit the canonical milestone because the underlying timeline is first-write-wins.

The bridge deliberately catches diagnostics-sink failures after recording the milestone. Diagnostics therefore cannot change startup ownership, scheduler decisions, retry behaviour, buffer state or failure bounds.

This wiring layer is intentionally separated from the individual transport/peer/loopback/Media3 hook points so those call sites can be connected incrementally and regression-tested without changing playback policy.

## Invariants

- Timeline collection is observational only. It must not change startup/no-peer/stall bounds, request depth, refill/replacement budgets, recovery jumps or TS discontinuity handling.
- Generic IPTV player policy remains unchanged; P2P-specific Media3 policy stays isolated at the existing player boundary.
- Repeated peer callbacks, retries and reader reopens may add separate diagnostics, but the canonical startup milestone keeps its first timestamp.
- Runtime hook points must feed these milestones from the existing Ace Live transport/peer path, loopback HTTP lifecycle and Media3 boundary, then field logs identify the actual latency segment before any optimization.
- The direct 8-second soft window must not be shortened or bypassed from telemetry alone; any startup-race change requires evidence that metadata is actionable earlier while absolute failure bounds remain intact.
