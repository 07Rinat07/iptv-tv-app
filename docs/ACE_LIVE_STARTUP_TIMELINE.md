# Ace Live startup timeline — V4d

V4d closes the startup/zap latency blocker with evidence before changing transport or buffering behaviour. The timeline is monotonic from one player preparation request and uses first-write-wins milestones so retries and HTTP reopens cannot rewrite the original startup path.

## Canonical milestones

`transport_selection → direct_attempt / metadata_attempt → first_candidate → connected → handshake → useful_window → first_media → buffer_ready → http_reader_open → http_first_read → media3_ready → first_frame`

Each exported milestone carries `elapsed_ms` relative to the same startup origin. Missing milestones remain missing rather than receiving synthetic timestamps.

## Invariants

- Timeline collection is observational only. It must not change startup/no-peer/stall bounds, request depth, refill/replacement budgets, recovery jumps or TS discontinuity handling.
- Generic IPTV player policy remains unchanged; P2P-specific Media3 policy stays isolated at the existing player boundary.
- Repeated peer callbacks, retries and reader reopens may add separate diagnostics, but the canonical startup milestone keeps its first timestamp.
- The next V4d wiring increment should feed these milestones from the existing Ace Live runtime, loopback HTTP lifecycle and Media3 boundary, then use field logs to identify the actual latency segment before optimizing it.
- The direct 8-second soft window must not be shortened or bypassed from telemetry alone; any startup-race change requires evidence that metadata is actionable earlier while absolute failure bounds remain intact.
