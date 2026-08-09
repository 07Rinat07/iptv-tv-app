# Ace Live output discontinuity increment

## Status

Implements the next autonomous roadmap boundary after tracker discovery: make an applied live recovery jump explicit to the future media-output layer.

## Contract

- `AceLivePeerSessionCoordinator.applyRecoveryAdvance` remains the only place where a surfaced recovery cursor jump is applied to ownership and reassembly together.
- A successful applied jump now returns `AceLiveOutputDiscontinuity` with the exact `fromPiece`, `toPiece`, skipped-piece count and reason `RECOVERY_EVICTED_GAP`.
- Normal contiguous piece completion does not synthesize a discontinuity event.
- The P2P layer does not inspect MPEG-TS, guess keyframes, flush decoders or emit HLS tags. Those actions remain the responsibility of a future playback/output adapter consuming this explicit event.
- The event is produced only after the existing recovery policy has already validated the jump against the current cursor and `maxPieceAdvance`.

## Tests

`AceLiveOutputDiscontinuityTest` covers:

- exact discontinuity coordinates and reason for an applied evicted-gap recovery jump;
- skipped-piece count;
- next-needed cursor after application;
- validation that a discontinuity must move strictly forward.

## Roadmap effect

This completes the explicit discontinuity-event boundary item in `ACE_LIVE_IMPLEMENTATION_PLAN.md`. Remaining autonomous work stays separate: DHT/LSD discovery expansion, playback mux/keyframe regating, startup/seek/recovery metrics, and long-run hardware acceptance. No `content_id -> BTIH` conversion, proprietary transport-file decryption secret, catalog signing secret, or copied proprietary/AGPL implementation is introduced.
