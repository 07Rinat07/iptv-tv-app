# Remote and mouse controls

_Last reviewed: 2026-08-24_

This guide documents the current production input contract for Rinat IPTV on Android TV / TV Box. It is intentionally conservative: controls described here correspond to the behavior protected by the production Player and TV-navigation regression tests. When a screen has specialized behavior, that screen's behavior takes precedence over generic navigation.

## General TV navigation

Use the D-pad to move focus between actionable controls and lists:

- **Up / Down / Left / Right** — move focus according to the current screen layout.
- **OK / DPAD_CENTER / Enter** — activate the focused control.
- **Back** — close the current overlay/dialog first when one is open, otherwise return to the previous app level.
- **Page Up / Page Down** and **Channel Up / Channel Down** may act as page navigation on long TV lists that use the shared paged-list component.

The focused control should remain visually identifiable. Long lists are expected to scroll while keeping the active selection reachable. A screen that traps focus or makes the selected control disappear is a navigation regression and should be reported with the screen name and the exact key sequence.

## Mouse and touchpad

The application supports mouse/touchpad interaction alongside D-pad navigation:

- **Click** — activate buttons, cards and other clickable controls.
- **Wheel / touchpad scrolling** — scroll long lists and pages where the current component supports scrolling.
- Dialogs, channel lists and settings panels are designed so mouse interaction does not require a remote-only action.

Do not assume the wheel always changes volume or channels. List/page surfaces use the wheel for scrolling. The Player has its own specialized input contract described below.

## Player controls

The production Player route is `StablePlayerScreen`. Its input mapping is protected by Player regression tests and should not be duplicated in a second navigation policy.

### When the video surface owns focus

The following mappings are installed on the Android video surface. In fullscreen mode an active video surface/session is expected to own focus. Outside fullscreen they apply only while that video surface has focus; when focus is on a Compose rail, channel browser, dialog or panel, the focused Compose control or the system may receive these keys instead.

| Input | Action |
| --- | --- |
| DPAD_CENTER / Enter / Numpad Enter | Show or hide Player controls |
| Menu / Guide | Show or hide Player controls |
| Channel Up / Media Next | Next channel |
| Channel Down / Media Previous | Previous channel |
| Volume Up | Increase Player volume |
| Volume Down | Decrease Player volume |
| Mute / Volume Mute | Toggle mute |
| Media Play/Pause / headset media key | Toggle playback |
| Keyboard `F` | Toggle fullscreen |

### Fullscreen D-pad behavior

While the production Player is fullscreen **and an active video surface/session exists**:

- **Left** — previous channel.
- **Right** — next channel.
- **Up** — volume up.
- **Down** — volume down.
- **Center / Enter** — show or hide controls.
- **Back** — leave the fullscreen overlay before leaving the Player route.

If fullscreen is open without an active playback session/video surface, those video-surface arrow mappings are not installed; Compose/system focus handling applies instead. Outside fullscreen, the arrow keys are not converted into Player channel/volume actions by the Player key policy; normal Compose focus navigation remains available.

### Player mouse/touchpad gestures

When the Android Player video surface is active, `StablePlayerInputHandler` provides these pointer gestures:

- **single tap / click on the video surface** — show or hide Player controls;
- **double tap** — toggle fullscreen;
- **horizontal swipe or horizontal wheel/touchpad scroll** — change channel; positive/rightward input maps to the previous channel and negative/leftward input maps to the next channel according to the production adapter;
- **vertical drag or vertical wheel/touchpad scroll** — change Player volume; upward/positive input raises volume and downward/negative input lowers volume.

These mappings belong to the video surface, not to lists. Channel browser, channel drawer and panel lists remain normal clickable/scrollable Compose surfaces and must not inherit accidental channel or volume changes from list scrolling.

Because Player input is specialized, changes to shared TV scrolling/focus components must be checked against the real `StablePlayerScreen` route rather than the legacy `PlayerScreen.kt` implementation.

## Channel browser and channel drawer

The production channel browser/list/drawer is implemented in `StablePlayerChannelBrowser.kt`.

- D-pad moves focus to a channel row; **OK / Enter** activates the focused row and selects/plays that channel. Mouse click activates the clicked channel directly.
- The selected/focus candidate is reconciled after filtering so a hidden item does not remain the only logical focus target.
- Long channel lists support shared paged-list navigation.
- Favorite actions remain separate from selecting/playing a channel.
- Search/filter changes must not rewrite Scanner discovery/query semantics; the Player browser has its own filtering state.

## Player panels

Player playlists, groups/filters and settings dialogs are implemented in `StablePlayerPanels.kt`.

- Dialogs can be dismissed with Back or their explicit close/done action.
- Long option lists use scrollable TV lists with paging controls.
- Opening or closing a panel must not start a second playback runtime or change P2P transport policy.

## Scanner safety boundary

Scanner search/import behavior is protected from shared navigation refactors. TV/mouse improvements must not change:

- query text or keyword semantics;
- provider selection;
- search mode;
- start/stop search behavior;
- result ordering/selection semantics;
- preview or import behavior.

If a shared focus/scroll component is changed, verify Scanner query input, search start, result scrolling, candidate selection, import and Back navigation.

## P2P / Ace Live safety boundary

Remote or mouse work does **not** authorize transport-policy changes. In particular, navigation/UI changes must not alter Ace/P2P peer selection, DHT budgets, request depth, timeouts, buffers or fallback policy. Those decisions remain gated by Issue #159 real-device evidence.

## Troubleshooting navigation problems

When reporting an input problem, include:

1. device or emulator model;
2. app build/commit if known;
3. screen or dialog name;
4. input device (remote, keyboard, mouse, touchpad);
5. exact key/click/scroll sequence;
6. which control had focus before the problem;
7. whether Back could recover the UI;
8. whether playback, Scanner or another running operation continued normally.

For Player problems, also state whether the Player was fullscreen and whether controls were visible. For scrolling problems, note whether the issue is focus movement, viewport scrolling, mouse-wheel handling, or all three.

## Regression expectations

A navigation-related PR is complete only when the relevant unit/regression checks and the repository's normal full Android CI gate are green. Player architecture/input changes additionally require the exact-head `Player Refactor Guard`, which explicitly checks out and verifies the PR head SHA. P2P/runtime changes have separate gates and, where required, real-device evidence; building an instrumentation APK is not a substitute for hardware validation.
