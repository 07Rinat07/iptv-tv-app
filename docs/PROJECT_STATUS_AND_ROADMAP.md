# Project status and roadmap

_Last updated: 2026-08-25 after manual TV Box field validation._

This is the canonical current-state document. Historical dated reports remain evidence, but they do not override this page.

## Current baseline

The consolidated build based on `main` commit `bb49b2aa1c460923358943fee7afc650e7b58d68` was manually tested on a real Android device/TV path.

Result: **not accepted as stable**.

The test exposed four release-blocking defects and one likely performance defect. The recovery baseline is documented in [`FIELD_VALIDATION_2026-08-25.md`](FIELD_VALIDATION_2026-08-25.md).

## Confirmed blockers

### #229 — P0 memory/import OOM

Real process death occurred near the 256 MB heap limit while large catalogs were being saved/loaded. A second OOM occurred inside Room `FavoriteChannelLookupDao_Impl`.

Confirmed code risk: Favorites continuously observed `SELECT * FROM channels` as `Flow<List<ChannelEntity>>`.

Current implementation work replaces that hot full-table materialization with bounded identity paging and full-row lookup only for matched favorite channels.

### #230 — P0 Home root integration

The new Home is routed but still nested in legacy `AppRoot` chrome. Manual startup did not produce a stable new Home experience.

Next after #229: make HOME a first-class full-screen root route and remove legacy shell wrapping for HOME.

### #231 — P0 production new Player UX

Player routes use `StablePlayerScreen`, but the manual build still presents a legacy-looking panel/card experience.

Next after Home: make the visible production Player the actual TV-first design while preserving one playback session across dashboard/fullscreen.

### #232 — P0 embedded Torrent TV peer/handshake gap

Same-device A/B against Televizo + Ace Stream Engine shows materially worse peer/start success in the embedded runtime.

Observed failures include:
- tracker returns one candidate but no connection;
- DHT finds peers and TCP connects but handshake never completes;
- peer found but no media arrives;
- P2P media stalls after initial playback.

Some P2P sessions do reach READY/first frame, proving the player boundary can work when media production succeeds.

Protocol work must therefore follow the first-missing-stage chain rather than adding generic telemetry or timeout increases.

### #233 — P1 rapid playback request churn

Diagnostics show many consecutive `player_play_request` events separated by roughly 150–200 ms. Verify whether list focus movement is starting playback unintentionally.

## Confirmed working baseline

Ordinary IPTV must not be rewritten wholesale.

Manual diagnostics include successful Media3 first frames for H.264/AAC and H.264/MP2 and multiple sessions with READY, first audio and first video frame. Screenshots also show successful video and LibVLC fallback cases.

## Current execution order

1. #229 memory/import stability.
2. #230 Home root integration.
3. #231 Player visible production UX.
4. #232 Torrent TV peer/handshake/live-window parity.
5. #233 focus/playback request separation if reproduced.
6. Resume EPG/archive/polish only after recovery blockers.

## Acceptance policy

CI is a regression gate, not field proof.

For every P0:
- merge only green exact-head code;
- build fresh `main`;
- manually reproduce the previously failing scenario;
- capture screenshot/video where relevant;
- export diagnostics;
- for Torrent TV, use fixed same-device A/B channels.

No telemetry-only, architecture-only or cosmetic increments should run ahead of this sequence.
