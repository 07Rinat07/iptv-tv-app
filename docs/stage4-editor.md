# Stage 4: Playlist Editor

## Scope

The editor lets the user clean, organize and export playlist data without damaging the original imported source.

## Current behavior

- Non-custom playlists are edited through a safe working copy.
- Custom playlists can be edited directly.
- Channels can be selected manually or through visible-list actions.
- Supported channel actions:
  - hide or show;
  - delete;
  - delete unavailable channels;
  - move selected channels to top or bottom;
  - edit name, group, logo and stream URL.
- The editor can create a custom playlist from selected channels.
- Export is available in `M3U`, `M3U8` and `TXT`.
- The main editor screen keeps advanced tools collapsed by default.
- EPG data is shown for channels when the playlist has a configured EPG source.
- The `Program` action expands the current/next program list for a channel; `Hide program` collapses it.
- EPG time is formatted for `Asia/Oral` / Uralsk / UTC+5.

## Robustness

- Repository errors are returned as `AppResult.Error`.
- Editing operations validate empty selection and missing playlist/channel states.
- Export uses generated playlist text instead of mutating the original imported source.

## Navigation

- `editor`
- `editor/{playlistId}`

## Verification

Use the CI-equivalent command from the root `README.md`, or run focused editor checks:

```bash
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon --stacktrace :core:data:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:editor:assembleDebug
```
