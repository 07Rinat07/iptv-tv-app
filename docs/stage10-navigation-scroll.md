# Stage 10: Scroll And Device Navigation

## Delivered

- Added shared `TvScrollableLazyColumn` and `TvLazyColumnScrollbar` components in `core:designsystem`.
- Reused the shared draggable scrollbar across regular app sections:
  - home;
  - ready playlists;
  - playlists;
  - favorites;
  - history;
  - EPG;
  - importer;
  - editor;
  - downloads;
  - settings;
  - network test;
  - diagnostics;
  - sections dialog.
- Kept native Compose scrolling intact for touch, mouse wheel and TV remote focus movement.
- Left scanner search logic unchanged.
- Left player channel lists on their specialized controls for now because they are tied to playback state, groups, overlays and channel switching.

## Navigation Redesign Plan

### 1. Shared Navigation Model

- Move the section list from `MainActivity` into one shared route model.
- Store route id, title, short title, priority and device placement in that model.
- Reuse the same model for TV rail, phone bottom navigation and the sections dialog.

### 2. TV Box Layout

- Replace the modal-only `Разделы` flow with a persistent left rail on wide screens.
- Keep large focusable targets and visible focus outline.
- Preserve `Назад`, `Выход` and current section state.
- Support D-pad:
  - up/down moves through sections;
  - center opens the selected section;
  - back returns to previous section or closes overlays.

### 3. Phone Layout

- Use bottom navigation for the most common sections:
  - Главная;
  - Сканер;
  - Плейлисты;
  - Избранное;
  - Плеер.
- Put secondary sections behind `Еще`.
- Keep touch targets at least `48dp`.
- Avoid wide TV-only chrome on narrow screens.

### 4. Focus And State

- Remember the last opened section.
- Do not steal focus during normal recomposition.
- Restore focus to the previous navigation target after closing dialogs.
- Keep startup destination setting compatible with the new navigation shell.

### 5. Acceptance Checks

- Remote: navigate all main sections with D-pad only.
- Phone: navigate all main sections by touch without horizontal clipping.
- Mouse: wheel scroll works in every regular list section.
- TV Box: draggable scrollbar works on wide screens.
- Scanner: search quality and scan behavior remain unchanged.

## Notes

- Scanner changes are intentionally out of scope for this stage.
- Player navigation should be handled as a separate pass because channel switching, mini/fullscreen mode and playback focus need dedicated testing on TV Box.
