# Stage 9: Device Acceptance + Signed Release Runbook

## Scope
- lock down final acceptance flow on real Android TV hardware;
- define repeatable 8h stability and weak-network validation;
- define signed release build and publish procedure.

## Acceptance Matrix
- OS/API: Android 7.0 (API 24), Android 9/10, Android 12+.
- Device class: low-end TV Box (2 GB RAM), mid-range TV Box (4 GB RAM), Google TV device.
- Input mode: D-pad only, mouse pointer only, mixed mode.
- Network mode: stable broadband, packet loss/jitter profile, low bandwidth profile.

## Mandatory E2E Scenarios
1. Scanner chain:
   - `Сканировать -> Найти -> Выбрать -> Проверить -> Сохранить -> Воспроизвести`.
   - Expected: channel list stored, playback starts, no crash.
2. Manual import:
   - URL import, text import, local file import.
   - Expected: parse report, dedup applied, playlist visible in `Плейлисты`.
3. Editor:
   - multi-select delete/hide/move, field edit, export, custom playlist creation.
   - Expected: copy-on-write preserved, source playlist not corrupted.
4. Favorites/History:
   - add favorites from multiple playlists, restart app, refresh playlists.
   - Expected: favorites/history persisted.
5. Player switching:
   - internal player <-> VLC default and per-channel override.
   - Expected: VLC fallback works when not installed.
6. Engine stream:
   - connect endpoint, resolve torrent descriptor, playback through resolved stream.
   - Expected: diagnostics reflects engine status and errors.

## 8h Soak Procedure
1. Build debug candidate and install on target device.
2. Start playback rotation script/operator flow:
   - switch channel every 3-5 minutes across 30+ channels.
   - mix HTTP/HLS and engine-resolved channels.
3. Keep diagnostics screen snapshots every 30 minutes:
   - memory/uptime, engine metrics, sync logs.
4. Pass criteria:
   - no fatal crash;
   - no ANR;
   - playback recoveries happen within configured retry policy.

## Weak-Network Procedure
1. Apply network shaping:
   - bandwidth: 2-5 Mbps;
   - latency: 120-250 ms;
   - packet loss: 1-3%.
2. Validate buffer profiles:
   - `MINIMAL`, `STANDARD`, `HIGH`, `MANUAL`.
3. For each profile validate:
   - startup latency;
   - rebuffer frequency;
   - auto-retry recovery behavior.
4. Pass criteria:
   - app remains responsive;
   - playback restores automatically or surfaces actionable error.

## Signed Release Runbook
### Prerequisites
- release keystore and alias are available in secure storage;
- CI secrets configured:
  - `IPTV_RELEASE_STORE_FILE`
  - `IPTV_RELEASE_STORE_PASSWORD`
  - `IPTV_RELEASE_KEY_ALIAS`
  - `IPTV_RELEASE_KEY_PASSWORD`

### Local signed build
```bash
export IPTV_RELEASE_STORE_FILE=/abs/path/iptv-release.jks
export IPTV_RELEASE_STORE_PASSWORD=***
export IPTV_RELEASE_KEY_ALIAS=iptv
export IPTV_RELEASE_KEY_PASSWORD=***
./gradlew --no-daemon :app:assembleRelease
```

### Signature verification
```bash
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk
```

### Artifact integrity
```bash
sha256sum app/build/outputs/apk/release/app-release.apk
```

## Release Gate Checklist
- [x] `lintDebug`, unit tests, release build green.
- [x] `:app:connectedDebugAndroidTest` smoke green.
- [ ] Real-device 8h soak green.
- [ ] Weak-network profile validation green.
- [ ] Signed APK signature verified.
- [ ] Acceptance report attached (use `docs/device-acceptance-report-template.md`).
- [ ] PR checklist completed (`.github/pull_request_template.md`).

## Rollback Rule
- keep previous signed stable APK and checksum;
- if crash rate or startup/playback regression detected post-release, roll back to previous stable build immediately.
