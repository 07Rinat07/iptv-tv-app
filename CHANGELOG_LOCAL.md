# Local update — 2026-08-04

## Added

- Adaptive wide-screen application sidebar with compact mode.
- About screen with author and device information.
- Player channel browser for playlists, groups, subgroups and in-playlist search.
- Remote-control command mapping and cyclic channel switching.
- Device-aware Media3 buffer planning and bounded recovery policy.
- Unit tests for adaptive buffering and player interaction.

## Changed

- Player viewport uses a 16:9 layout and supports double-click/double-tap fullscreen toggling.
- Fullscreen player includes channel navigation and stop/collapse controls.
- Channel list rows are more compact and expose logo, group, EPG and favorite state.
- Long channel lists use an adaptive maximum height.

## Protected

Scanner and playlist-search source files were intentionally left unchanged.
