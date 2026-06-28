# Stage 5: Player

## Scope

The player module provides channel playback, playlist navigation, fallback playback through VLC, and EPG-aware channel lists.

## Current behavior

- Internal playback uses Media3/ExoPlayer.
- VLC can be used as an external fallback for streams that fail internally.
- Playlists, groups, subgroups and channels are loaded from the repository.
- Channel selection starts internal playback immediately.
- The quick channel list and full channel catalog support TV remote navigation.
- The quick channel list uses the full playlist; it is not capped at 200 channels.
- Channel rows show logos when available.
- EPG data is shown in channel lists when the selected playlist has an EPG source.
- The `Program` action expands the current/next program list for a channel; `Hide program` collapses it.
- EPG time is formatted for the player timezone: `Asia/Oral` / Uralsk / UTC+5.
- Player local time is shown in the top-right corner of the inline player and in the fullscreen top bar.
- Technical panels are collapsible to keep the player screen clean.
- Duplicate fullscreen controls were removed from the top-right overlay.

## Playback resilience

- Internal playback errors are reported to the ViewModel.
- The player performs a limited soft retry instead of entering an endless retry loop.
- Internal playback failures stay inside the app and suggest manual VLC playback instead of automatically switching the user to an external app.
- Stream probe and player diagnostics are written to app logs.
- VLC direct launch falls back to a compatible `ACTION_VIEW` launch when needed.

## Settings integration

- Default player selection.
- Buffer profile selection.
- Manual buffer values for advanced troubleshooting.
- Per-channel player override where supported by settings.

## Navigation

- `player`
- `player/{playlistId}`

## Verification

Use the CI-equivalent command from the root `README.md`, or run the focused player checks:

```bash
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon --stacktrace :core:player:testDebugUnitTest :feature:player:testDebugUnitTest :feature:player:assembleDebug
```
