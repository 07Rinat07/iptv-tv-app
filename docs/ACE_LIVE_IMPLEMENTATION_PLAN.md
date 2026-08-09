# Ace Live implementation plan

## Current boundary

The app already has an embedded libtorrent path for ordinary BitTorrent and keeps Ace Live on an explicit compatibility boundary. A 40-character Ace `content_id` is a transport identity, not a BitTorrent infohash. `.acelive` descriptors must not enter the ordinary libtorrent pipeline.

## Current increment

1. Keep `HybridEngineRepositoryImpl` as the player-facing routing boundary.
2. Make the external compatibility backend match the current Ace Engine playback control contract: `GET /ace/getstream?format=json...` followed by `response.playback_url`.
3. Preserve `content_id` as 40-character hexadecimal and use the descriptor key `content_id`; use `magnet` for magnet descriptors and `url` for transport-file URLs.
4. Discover Ace Engine through AIDL and the dynamic HTTP API port. Include `org.acestream.live` and `org.acestream.node` in Android package visibility/known-package discovery.
5. Reject malformed control responses instead of handing the JSON control endpoint to Media3/LibVLC.
6. Cover the control request, descriptor normalization and failure behavior with unit tests.

## Next autonomous increments

1. Run hardware acceptance with the current AceStream Core Live package and several real `acestream://content_id`/`.acelive` sources; record service discovery, HTTP port, control response and startup failures.
2. Finish an independent content-id metadata provider using only a verified public protocol or open implementation. Keep transport-file data, content id and BitTorrent infohash as separate fields.
3. Extend the explicit `.acelive` model with verified transport-descriptor metadata and diagnostics.
4. Implement live-window, piece/chunk scheduling, peer discovery, recovery and live seek only from public specifications or license-compatible open implementations; do not copy closed APK/native code.
5. Add live startup/seek/recovery metrics and long-run hardware tests before making the autonomous Ace Live backend primary.
6. Remove the external Ace compatibility dependency only after real Ace Live channels pass the same acceptance baseline on x86 emulator and ARM TV hardware.

## Non-goals

- No `content_id -> decimal` conversion.
- No `content_id -> BTIH` guessing.
- No hard-coded `127.0.0.1:6878` as the normal installed-engine endpoint.
- No direct reuse of proprietary/AOT/native implementation code extracted from APKs.
