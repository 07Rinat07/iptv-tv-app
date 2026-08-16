# Ace Live peer lifecycle telemetry — 2026-08-16

## Context

PR #131 closed the R2 behavior gap where an endpoint that had never completed an Ace handshake could consume the normal established-peer reconnect budget. Exact-head Android CI #557 passed all applicable gates, including real Torrent TV playback without an external Ace Engine, core P2P/unit tests, lint, debug/instrumentation build, signed ARM TV APKs and source packaging. PR #131 was then squash-merged into `main` as `41a883d9ba6c26321d673c9e636a052580201076`.

The R2 field log still could not distinguish why TCP-reachable candidates remained `handshaked=0`. The next increment therefore remains observational only.

## Diagnostic contract

Status: `embedded_ace_live_peer_lifecycle`.

Every sparse TCP-pool lifecycle event is exported through the existing persistent diagnostics observer with the same startup correlation:

- `event=connected`: `peer`, `reconnect_attempt`, `startup_id`, `elapsed_ms`;
- `event=connect_failed`: `peer`, `retrying`, `startup_id`, `elapsed_ms`;
- `event=handshake_accepted`: `peer`, `startup_id`, `elapsed_ms`;
- `event=handshake_rejected`: `peer`, `reject_reason`, `startup_id`, `elapsed_ms`;
- `event=disconnected`: `peer`, `reason`, `retrying`, `requeued_pieces`, `startup_id`, `elapsed_ms`.

Disconnect reasons remain the transport enum values, including `HANDSHAKE_TIMEOUT`, `HANDSHAKE_REJECTED`, `REMOTE_CLOSED`, `IO_ERROR` and `PROTOCOL_REJECTED`. The diagnostic layer does not reinterpret or coalesce them.

`startup_id` is the canonical Ace Live preparation start timestamp already owned by `AceLiveStartupTimelineDiagnostics`; `elapsed_ms` is measured from that same clock. This makes lifecycle rows directly comparable with `embedded_ace_live_startup_timeline` without introducing a second runtime clock.

## Deliberately unchanged

This increment does not change:

- pre-handshake or established-peer reconnect budgets;
- connect, handshake, no-peer, stall or absolute startup timeouts;
- peer target/max, DHT discovery budgets or refill cadence;
- scheduler, request depth, replacement or recovery policy;
- TS authentication/resync/discontinuity behavior;
- output buffer or cache size;
- localhost HTTP Range/reopen semantics;
- Media3 or generic IPTV playback policy.

## Field validation after merge

Repeat the same-device matrix with one known-working Torrent TV channel, `Премиальное HD` or another previous `connected -> handshaked=0` case, and rapid P2P channel switching.

The exported log should answer directly:

1. which peer ids reached TCP connect;
2. whether any endpoint was internally reconnected before qualification;
3. whether failure was connect failure, handshake timeout, explicit handshake rejection, remote close or another transport reason;
4. whether the failure was final or retrying;
5. how many pieces were requeued when an established peer disconnected;
6. elapsed time from preparation start to each lifecycle transition;
7. which lifecycle belongs to the same canonical startup timeline via `startup_id`.

Only after that evidence should V4d decide whether competitive useful-peer acquisition needs another behavior change.
