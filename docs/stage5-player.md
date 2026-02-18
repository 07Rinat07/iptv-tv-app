# Stage 5: Player Runtime (Media3 + VLC Fallback)

## Delivered
- Implemented real runtime player flow in `feature:player`:
  - load playlists and channels from repository;
  - choose playlist and channel for playback;
  - play with internal Media3/ExoPlayer or external VLC.
- Added internal playback host with ExoPlayer (`PlayerView` in Compose):
  - builds `LoadControl` from selected buffer profile;
  - supports manual buffer values for `MANUAL` profile;
  - reports ready/error states back to ViewModel.
- Added VLC runtime behavior:
  - installed check before launch;
  - explicit install actions (`market://` then web fallback);
  - automatic fallback to internal player if VLC missing or launch fails.
- Added playback resiliency:
  - auto-retry for internal playback errors with staged delays.
- Added per-channel player override:
  - persisted in DataStore via `SettingsRepository`;
  - effective player = channel override or default player.
- Added Settings runtime screen:
  - default player selection;
  - buffer profile selection;
  - manual buffer values edit/save.

## Data/Domain updates
- `SettingsRepository` extended with:
  - `observeManualBuffer()`
  - `observeChannelPlayerOverride(channelId)`
  - `setChannelPlayerOverride(channelId, playerType?)`
- DataStore keys extended with channel override map storage.
- `ManualBufferSettings` model introduced in `core:model`.

## Navigation updates
- Added player route with playlist argument:
  - `player`
  - `player/{playlistId}`
- `Playlists` screen now opens `Player` with selected playlist id.

## Tests and verification
- New unit tests:
  - `core/player/src/test/.../PlayerContractsTest.kt`
- Verified commands:
  - `:core:player:test`
  - `:feature:settings:assembleDebug`
  - `:feature:player:assembleDebug`
  - `:app:assembleDebug`
