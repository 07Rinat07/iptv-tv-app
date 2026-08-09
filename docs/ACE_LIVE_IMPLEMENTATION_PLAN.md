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
4. Suggest a forward cursor jump only when the current piece has remained stalled for at least the request timeout and no active unchoked peer advertises it anymore.
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

## Completed piece-reassembler increment

1. Add a pure `AceLivePieceReassembler` that consumes only chunks accepted at the active-peer/session boundary.
2. Assemble payload bytes by verified geometry and support the shorter final chunk when `pieceLength` is not divisible by `chunkLength`.
3. Emit completed pieces only from the authoritative contiguous `nextNeeded` cursor; a completed future piece remains buffered until earlier pieces are emitted.
4. Make recovery discontinuity explicit through `skipTo`; normal append never skips a missing piece.
5. Reject stale, invalid and too-far-ahead chunks before allocating a new piece buffer.
6. Repeat geometry/header validation at the reassembly boundary, require an identical 8-byte live header for every chunk of a piece, and make duplicates idempotent.
7. Cap both the ahead horizon and total allocated piece-payload memory. The default byte budget is a local safety policy, not a protocol timing/bitrate assumption.
8. Reject geometry whose chunks-per-piece exceeds the u16 wire index space.
9. Treat the maximum u32 piece as terminal; do not wrap the contiguous cursor back to zero implicitly.
10. Cover out-of-order chunks/pieces, contiguous drain, short tail, duplicate/header mismatch, explicit skip, memory bounds and u32 boundary behavior with unit tests.

## Completed peer-wire/session increment

1. Add a bounded incremental peer-frame codec for `<u32 BE length><u8 id><payload>` with `length=0` keep-alive and a local 2 MiB frame cap.
2. Decode standard empty-payload choke/unchoke and the verified Ace live id=7 piece layout: `[stream u32][piece u32][8B header][chunk u16][data]`.
3. Encode only chunk requests already produced by `AceLiveActivePeerCoordinator` as full id=6 peer frames; do not invent request coordinates in the network layer.
4. Preserve unknown/vendor messages instead of disconnecting solely because a standard id carries a non-standard Ace payload.
5. Do not bind live-window `myinfo` updates to an unverified numeric message id.
6. Add a single-threaded `AceLivePeerSessionCoordinator` that serializes decoded peer events, active-peer ownership, recovery and reassembly without owning sockets.
7. Run reassembly preflight before mutating active chunk state so header/geometry/memory rejection cannot strand ownership.
8. Discard partial reassembly for every piece requeued by peer loss/window movement or request timeout before reassignment to another peer.
9. Reflect contiguous reassembler emission back into recovery immediately; apply explicit recovery discontinuity to ownership and reassembly as one serialized operation.
10. Derive an effective scheduling horizon from both the configured ahead limit and the number of whole piece buffers allowed by `maxBufferedBytes`.
11. Cover frame vectors, partial/oversized frames, tolerant unknown ids, choke/unchoke, future-piece buffering, wrong-peer rejection, header preflight, peer-drop/timeout cleanup, recovery advance and memory-capped scheduling with unit tests.

## Completed peer-metadata/connection-state increment

1. Add `AceLivePeerMetadataRecognizer`, a bounded bencode recognizer that classifies live metadata by payload content instead of a guessed vendor numeric message id.
2. Accept either a direct bencoded dictionary or a single-byte extended/submessage envelope followed by a dictionary; recognize `mi.max_piece` plus optional `min_piece`, `position` and `distance_from_source`.
3. Bound payload bytes, nesting, container entries, string bytes and total parsed nodes before decoded metadata can affect scheduler state.
4. When `min_piece` is absent, conservatively expose only `[max_piece..max_piece]` instead of assuming historical piece availability.
5. Add the standard interested peer-frame encoder without adding proprietary handshake identity/signing fields.
6. Add `AceLivePeerConnectionStateMachine` for one transport-neutral peer lifecycle: transport connected, externally validated handshake, interested emission, incremental frame consumption, choke state and metadata-window refresh.
7. Keep partial peer-frame receive buffering capped by the verified frame limit and surface oversized framing as a disconnect recommendation instead of allocating unbounded memory.
8. Route recognized windows into `AceLivePeerSessionCoordinator` with the current choke state; malformed metadata is reported but does not by itself tear down a valid framed connection.
9. Keep global piece assignment in `AceLivePeerSessionCoordinator`; each connection state machine only selects already-scheduled request frames owned by its peer.
10. On transport disconnect, release peer ownership and partial reassembly through the existing peer-drop boundary before resetting local connection state.
11. Cover content-based metadata recognition, conservative missing-min behavior, malformed/oversized input, interested/unchoke gating, partial-frame buffering, request routing and disconnect requeue with unit tests.

## Completed TCP connection-pool increment

1. Add a bounded TCP adapter that owns connect/read/write/close and finite reconnect attempts for already-discovered Ace Live peer endpoints.
2. Perform the independently verified outer Ace transport handshake before peer-frame ingress; keep proprietary signed identity generation outside the pool.
3. Feed post-handshake bytes into `AceLivePeerConnectionStateMachine` and serialize access to the shared `AceLivePeerSessionCoordinator`.
4. Route only already-scheduled request frames to the connection that owns their peer id.
5. Bound connect, read, handshake and write timing; close a stalled socket so one peer cannot hold a write slot indefinitely.
6. Requeue ownership and partial reassembly on disconnect before retrying the endpoint.
7. Keep tracker/DHT discovery, descriptor decryption, peer scoring and playback output outside the transport pool.
8. Cover timeout, cancellation, reconnect, handshake, routing and peer-lifecycle failure paths with unit tests.

## Completed UDP tracker-discovery increment

1. Keep a live swarm identity explicitly separate from both `content_id` and ordinary BitTorrent routing: external metadata exposes `liveSwarmInfoHash`, while `core:p2p` converts only validated 40-hex input to an immutable 20-byte `AceLiveSwarmKey`.
2. Add a pure BEP-15 codec for UDP tracker connect/announce framing, transaction-id validation and compact IPv4 peer responses.
3. Require an explicit caller-owned peer announce port; never substitute the Ace HTTP API port or invent a self-announce endpoint when no peer listener owns one.
4. Consume only descriptor-provided `udp://` tracker endpoints. Bound tracker count, URL length, request timeout, response bytes, peers per tracker and total returned peers.
5. Treat tracker metadata as untrusted network input: reject loopback/private/link-local/multicast/reserved tracker destinations by default and require an explicit policy opt-in for controlled local deployments/tests.
6. Treat compact peer results as untrusted too: reject non-global peer endpoints by default before they can reach the TCP connection pool.
7. Deduplicate returned `AceLiveTcpPeerEndpoint` values across tracker responses and keep tracker failures best-effort and isolated.
8. Keep Mainline DHT, LSD, peer scoring/refill, inbound listener/NAT mapping and autonomous runtime wiring as separate later increments.
9. Cover wire offsets, malformed/oversized responses, swarm-key ownership, tracker URL policy, unsafe destination rejection and a complete local fake-tracker connect/announce exchange with unit tests.

## Next autonomous increments

1. Run hardware acceptance with the current AceStream Core Live package and several real `acestream://content_id`/`.acelive` sources; record service discovery, HTTP port, runtime route, control response and startup failures.
2. Add an autonomous content-id metadata provider ahead of the external provider when a verified public, independently implementable resolution contract is available.
3. Feed verified decoded live metadata into `AceLiveDescriptor` when a lawful/public decoder or provider is available.
4. Add an outer peer-handshake parser/validator boundary that can verify standard transport framing/capabilities without inventing Ace identity/signature fields; keep signed identity generation separate until its contract is independently implementable.
5. Connect the completed UDP tracker adapter to peer-pool orchestration only when a real local peer-listener/announce-port lifecycle is present; do not advertise the HTTP API endpoint.
6. Add Mainline DHT (BEP-5) and, if useful for LAN operation, LSD as independently verified discovery sources; keep discovery source aggregation/deduplication separate from peer scoring.
7. Add peer scoring/background refill after multiple discovery sources exist, preserving stale-pool recovery semantics without automatically banning reachable peers.
8. Add an explicit discontinuity event boundary for future TS keyframe regating/HLS handling when recovery jumps are actually applied to output.
9. Add live startup/seek/recovery metrics and long-run hardware tests before making the autonomous Ace Live backend primary.
10. Remove the external Ace compatibility dependency only after real Ace Live channels pass the same acceptance baseline on x86 emulator and ARM TV hardware.

## Non-goals

- No `content_id -> decimal` conversion.
- No `content_id -> BTIH` guessing.
- No hard-coded `127.0.0.1:6878` as the normal installed-engine endpoint.
- No direct reuse of proprietary/AOT/native implementation code extracted from APKs.
- No embedded reverse-engineered catalog signing secret or transport-file decrypt key/IV.
