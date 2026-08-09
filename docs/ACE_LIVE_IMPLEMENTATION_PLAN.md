# Ace Live implementation plan

## Current boundary

The app already has an embedded libtorrent path for ordinary BitTorrent and keeps Ace Live on an explicit compatibility boundary. A 40-character Ace `content_id` is a transport identity, not a BitTorrent infohash. `.acelive` descriptors must not enter the ordinary libtorrent pipeline.

## Completed compatibility increment

1. Keep `HybridEngineRepositoryImpl` as the player-facing routing boundary.
2. Make the external compatibility backend match the current Ace Engine playback control contract: `GET /ace/getstream?format=json...` followed by `response.playback_url`.
3. Preserve `content_id` as 40-character hexadecimal and use the descriptor key `content_id`; use `magnet` for magnet descriptors and `url` for transport-file URLs.
4. Discover Ace Engine through AIDL and the dynamic HTTP API port. Include `org.acestream.live` and `org.acestream.node` in Android package visibility/known-package discovery.
5. Reject malformed control responses instead of handing the JSON control endpoint to Media3/LibVLC.
6. Cover the control request, descriptor normalization and failure behavior with unit tests.

## Completed metadata-provider increment

1. Introduce `AceContentMetadataProvider` as a backend-neutral content-id metadata boundary.
2. Use `ChainedAceContentMetadataProvider` so the first successful provider wins and failures can fall through to compatibility backends.
3. Keep `ExternalEngineAceContentMetadataProvider` as the current installed-engine provider.
4. Keep transport classification independent from the metadata backend so a future autonomous resolver does not change player-facing routing.
5. Do not embed reverse-engineered signing secrets or copy AGPL/proprietary resolver code into this application.

## Completed runtime-diagnostics increment

1. Track Ace resolution stages separately from ordinary engine health: endpoint discovery, metadata request, playback request, compatibility fallback, ready and error.
2. Expose only safe diagnostic attributes: descriptor kind, provider, route, engine package, loopback endpoint, transport type/live flag and a sanitized playback target.
3. Never store or display content-id values, magnets, access tokens, transport descriptor query strings, URL query/fragment data or identifier-like playback path segments.
4. Preserve a successful `loopback_compatibility` fallback reason separately from real request failure codes.
5. Mirror the runtime summary into the existing `EngineStatus.message` so the current Diagnostics screen can show hardware-acceptance state without a parallel UI/domain contract.
6. Cover redaction, live metadata, playback resolution and loopback fallback with unit tests.

## Completed verified-live-protocol increment

1. Extend `AceLiveDescriptor` with optional verified transport metadata without decrypting `.acelive` bodies in the parser.
2. Model confirmed live geometry fields: `piece_length`, `chunk_length` and the raw positive `bitrate` value. Do not assign unverified bitrate units or derive timing from it.
3. Derive only protocol-safe geometry such as chunks per piece.
4. Add the verified 8-byte big-endian IEEE-754 `f64` Unix timestamp codec used by Ace Live piece messages.
5. Keep public-key and tracker values out of diagnostic summaries; expose only presence/count and non-sensitive geometry.
6. Do not embed the reverse-engineered fixed transport-file AES key/IV. Descriptor decryption remains outside the app until an independently implementable public contract exists.

## Completed live-window scheduler increment

1. Add a pure `AceLiveWindowScheduler` that owns request assignment state but no sockets, discovery, authentication or wire I/O.
2. Treat the playback/reassembly `nextNeeded` cursor as authoritative; requested frontiers never substitute for successfully emitted progress.
3. Assign only to unchoked peers whose advertised `[minPiece..maxPiece]` window covers the requested piece.
4. Bound in-flight work per peer, prevent duplicate piece ownership and bound every scheduling tick even when a peer advertises an implausibly large head.
5. Requeue unfinished pieces when a peer drops, a request times out or a refreshed live window no longer covers an outstanding piece.
6. Do not silently skip an uncovered `nextNeeded` piece. Expose the lowest currently available piece so a separate recovery policy can explicitly decide whether to jump past an evicted gap.
7. Prefer peers with fewer outstanding assignments, then a fresher advertised head, without making network/address policy part of the scheduler.
8. Keep this layer independent of `bitrate` timing assumptions and proprietary `.acelive` decryption.

## Completed recovery-coordinator increment

1. Add `AceLiveRecoveryCoordinator` around the pure window scheduler without adding sockets or peer discovery.
2. Timestamp outstanding piece assignments and requeue requests that remain unresolved past the local request timeout.
3. Track only authoritative contiguous `nextNeeded` progress; receiving a later/future piece must not reset the stall timer.
4. Suggest a forward cursor jump only when the current piece has remained stalled for at least the request timeout and no active unchoked peer still advertises that piece.
5. Keep cursor discontinuities explicit: the caller must apply a surfaced `AceLiveCursorAdvance`; normal scheduling never skips automatically.
6. Cap discontinuities with `maxPieceAdvance`; a larger evicted gap is surfaced as a reconnect/refresh condition instead of performing an unbounded jump.
7. Signal a stale-but-reachable pool separately from peer failure. A stale pool does not automatically ban or delete its peers.
8. Keep timeout/check/stale thresholds as validated local policy values and do not derive them from the unverified raw descriptor bitrate.
9. Cover timeout requeue, gap recovery, stale-pool signaling, progress reset, peer removal, sweep throttling and stale cursor decisions with unit tests.

## Completed active-peer coordinator increment

1. Add a pure `AceLiveActivePeerCoordinator` that consumes already-decoded window/choke/drop/chunk events and delegates piece ownership/recovery to `AceLiveRecoveryCoordinator`.
2. Expand every piece assignment into the verified Ace Live message-id 6 chunk coordinates: `[stream u32=0][piece u32][chunk u16]` in big-endian wire order.
3. Derive chunk offsets only from verified transport geometry: `begin = chunkIndex * chunkLength`; keep the final chunk bounded by `pieceLength`.
4. Keep peer sockets, tracker/DHT discovery, handshake/authentication and media buffering outside this coordinator.
5. Reject wrong-stream, wrong-owner, unsolicited, stale and too-far-ahead chunks before reassembly.
6. Bound scheduling and inbound acceptance to a configurable reassembler-ahead window.
7. Require all chunks of one piece to carry a valid, identical verified 8-byte live header; duplicates do not advance completion.
8. Release scheduler ownership only after every expected chunk has arrived; completing a future piece does not advance the contiguous playback cursor.
9. Clear chunk tracking when peer/window loss or timeout requeues a piece, and when an explicit recovery cursor advance makes older work stale.
10. Keep source-signature verification and decrypted descriptor credentials out of this increment.

## Current piece-reassembler increment

1. Add a pure `AceLivePieceReassembler` that consumes only chunks already accepted by the active-peer coordinator.
2. Assemble payload bytes by verified geometry and support the shorter final chunk when `pieceLength` is not divisible by `chunkLength`.
3. Emit completed pieces only from the authoritative contiguous `nextNeeded` cursor; a completed future piece remains buffered until earlier pieces are emitted.
4. Make recovery discontinuity explicit through `skipTo`; normal append never skips a missing piece.
5. Reject stale, invalid and too-far-ahead chunks before allocating a new piece buffer.
6. Repeat geometry/header validation at the reassembly boundary, require an identical 8-byte live header for every chunk of a piece, and make duplicates idempotent.
7. Cap both the ahead horizon and total allocated piece-payload memory. The default byte budget is a local safety policy, not a protocol timing/bitrate assumption.
8. Reject geometry whose chunks-per-piece exceeds the u16 wire index space.
9. Treat the maximum u32 piece as terminal; do not wrap the contiguous cursor back to zero implicitly.
10. Cover out-of-order chunks/pieces, contiguous drain, short tail, duplicate/header mismatch, explicit skip, memory bounds and u32 boundary behavior with unit tests.

## Next autonomous increments

1. Run hardware acceptance with the current AceStream Core Live package and several real `acestream://content_id`/`.acelive` sources; record service discovery, HTTP port, runtime route, control response and startup failures.
2. Add an autonomous content-id metadata provider ahead of the external provider when a verified public, independently implementable resolution contract is available.
3. Feed verified decoded live metadata into `AceLiveDescriptor` when a lawful/public decoder or provider is available.
4. Add the peer-wire/session adapter that decodes verified live window/choke/drop/piece messages and sends only requests produced by `AceLiveActivePeerCoordinator`; keep discovery and transport connection policy separate.
5. Connect accepted peer chunks to `AceLivePieceReassembler` through a small session orchestration boundary, and apply recovery cursor advances to both ownership and reassembly state atomically.
6. Add an explicit discontinuity event boundary for future TS keyframe regating/HLS handling when the transport layer actually begins applying recovery jumps.
7. Add live startup/seek/recovery metrics and long-run hardware tests before making the autonomous Ace Live backend primary.
8. Remove the external Ace compatibility dependency only after real Ace Live channels pass the same acceptance baseline on x86 emulator and ARM TV hardware.

## Non-goals

- No `content_id -> decimal` conversion.
- No `content_id -> BTIH` guessing.
- No hard-coded `127.0.0.1:6878` as the normal installed-engine endpoint.
- No direct reuse of proprietary/AOT/native implementation code extracted from APKs.
- No embedded reverse-engineered catalog signing secret or transport-file decrypt key/IV.
