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
5. Do not embed reverse-engineered signing secrets or copy AGPL/proprietary resolver code into this application. Public protocol behavior may be independently implemented only when its inputs and licensing are appropriate for this project.

## Current runtime-diagnostics increment

1. Track Ace resolution stages separately from ordinary engine health: endpoint discovery, metadata request, playback request, compatibility fallback, ready and error.
2. Expose only safe diagnostic attributes: descriptor kind, provider, route, engine package, loopback endpoint, transport type/live flag and a sanitized playback target.
3. Never store or display content-id values, magnets, access tokens, transport descriptor query strings, URL query/fragment data or identifier-like playback path segments.
4. Preserve a successful `loopback_compatibility` fallback reason separately from real request failure codes.
5. Mirror the runtime summary into the existing `EngineStatus.message` so the current Diagnostics screen can show hardware-acceptance state without a parallel UI/domain contract.
6. Cover redaction, live metadata, playback resolution and loopback fallback with unit tests.

## Next autonomous increments

1. Run hardware acceptance with the current AceStream Core Live package and several real `acestream://content_id`/`.acelive` sources; record service discovery, HTTP port, runtime route, control response and startup failures.
2. Add an autonomous content-id metadata provider ahead of the external provider when a verified public, independently implementable resolution contract is available. Keep transport-file data, content id and BitTorrent infohash as separate fields.
3. Extend the explicit `.acelive` model with verified transport-descriptor metadata and diagnostics.
4. Implement live-window, piece/chunk scheduling, peer discovery, recovery and live seek only from public specifications or license-compatible open implementations; do not copy closed APK/native code.
5. Add live startup/seek/recovery metrics and long-run hardware tests before making the autonomous Ace Live backend primary.
6. Remove the external Ace compatibility dependency only after real Ace Live channels pass the same acceptance baseline on x86 emulator and ARM TV hardware.

## Non-goals

- No `content_id -> decimal` conversion.
- No `content_id -> BTIH` guessing.
- No hard-coded `127.0.0.1:6878` as the normal installed-engine endpoint.
- No direct reuse of proprietary/AOT/native implementation code extracted from APKs.
- No embedded reverse-engineered catalog signing secret.
