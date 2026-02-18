# Stage 4: Playlist Editor and Copy-on-Write

## Delivered
- Added `PlaylistEditorRepository` binding and implementation wiring in DI.
- Implemented safe copy-on-write editing for non-custom playlists:
  - first editor action on source playlist creates a custom working copy;
  - further actions continue on the working copy.
- Implemented editor actions in `core:data`:
  - hide/unhide selected channels;
  - delete selected channels;
  - delete unavailable channels (`health=UNAVAILABLE`);
  - move selected channels to top/bottom;
  - update channel fields (`name`, `groupName`, `logo`, `streamUrl`);
  - create custom playlist from selected channels;
  - export selected/visible channels to M3U.
- Upgraded UI layer:
  - `feature:playlists` now loads real playlists from repository;
  - playlist refresh and selection added;
  - navigation to editor with `playlistId` argument;
  - `feature:editor` now provides batch actions, selection, edit form, custom playlist creation and M3U preview.

## Robustness updates
- Added defensive error handling in `PlaylistEditorRepositoryImpl` returning `AppResult.Error` instead of throwing.
- Added unit tests for editor repository behavior:
  - COW copy creation for non-custom playlist;
  - delete on custom playlist without extra copy;
  - M3U export excluding hidden channels by default.

## Verified
- `:core:data:test`
- `:feature:playlists:assembleDebug`
- `:feature:editor:assembleDebug`
- `:app:assembleDebug`

## Notes
- Editor route supports both `editor` and `editor/{playlistId}`.
- Stage 4 focuses on playlist editing domain and UI; player runtime behavior remains part of Stage 5.
