# Troubleshooting

_Last reviewed: 2026-08-24_

This guide covers user-visible failure modes for the current production application. It does not authorize changes to playback, Scanner, EPG matching, or P2P transport policy; those remain governed by the canonical project roadmap and their dedicated regression/evidence gates.

## Before diagnosing

Record the app build or commit when available, the affected screen, the playlist/channel name, whether the problem reproduces after reopening the same screen, and whether it affects one source or every source. For Player problems also record whether playback was fullscreen, which backend was active when shown, and whether the failure happened before video/audio appeared or during established playback.

Avoid treating a single failed source as an application-wide failure. IPTV providers, XMLTV endpoints and P2P peers can fail independently.

## Playlist import

### A URL or file does not import

Check that the source is reachable from the TV device and that the selected input is actually an IPTV playlist or another format supported by the importer. If a remote URL works on another network but not on the TV Box, test the device network first rather than repeatedly re-importing the same source.

If an imported playlist contains no usable channels, keep the original source unchanged for diagnosis. Do not rewrite stream URLs merely to make the import appear successful.

### A re-import looks duplicated

Use the existing playlist/catalog and Favorites views to determine whether the entries represent the same logical channel from different sources or actual duplicate imports. Favorites intentionally preserve source provenance and may expose multiple source variants of one logical channel.

## Scanner

Scanner discovery/query behavior is a protected boundary. If Scanner behaves unexpectedly, record:

- query text and keywords;
- selected provider;
- search mode;
- whether search started and stopped normally;
- whether results could be focused/scrolled;
- whether preview worked;
- whether the final import action completed.

Do not treat a generic TV focus or scrolling problem as permission to change Scanner provider/query/import semantics.

## Player

### A channel does not start

First determine whether the failure affects one channel, one playlist, or all ordinary IPTV channels. Retry the same source only enough to distinguish a transient network failure from a reproducible application failure.

For ordinary IPTV playback, preserve the existing Media3 -> LibVLC fallback behavior. If the app reports an actionable playback/backend error, keep that message with the reproduction details.

### Video starts but controls seem unresponsive

The production route is `StablePlayerScreen`. Fullscreen video and Compose rails/browser/dialogs do not share identical key ownership. When focus is on a Compose control, D-pad/Enter acts on that focused control; Player video-surface shortcuts apply when the Android video surface owns focus, especially in fullscreen mode.

For channel lists, moving D-pad focus onto a row is not the same as activating it: press OK/Enter to select/play the focused channel.

### Back exits more than expected

Back should close the current Player overlay/dialog first, then leave fullscreen where applicable, before leaving the Player route. Report the exact visible overlay and focus target if Back skips a level.

## EPG / programme guide

### No programme data appears

A channel can exist without matched EPG data. Matching is deliberately conservative: explicit `tvg-id` and exact identities take priority, and ambiguous fallback matches fail closed instead of silently attaching the wrong schedule.

If the playlist declares an EPG source, record that source, whether other channels from the same playlist have programmes, and whether the problem is limited to one channel. Temporary refresh failures can use the bounded stale EPG fallback only under the policy documented in the canonical roadmap.

### Programme data looks old

The diagnostics model distinguishes the selected snapshot's fresh/stale origin, cache age and active transient retry deadline. Stale fallback is bounded and must not change source precedence or silently survive permanent/malformed/low-memory failures.

## Archive / catch-up

The current production build does **not yet expose archive/catch-up Player launch/UI**. The shared resolver and persisted explicit catch-up capability exist as backend contracts, but Issue #47 still lists intentional Player launch/UI integration as a future production increment.

When that UI integration lands, archive playback must remain enabled only from explicit persisted catch-up capability that passes the fail-closed resolver. A normal live URL is not evidence that archive playback exists. Unknown provider-specific modes, invalid ranges, malformed templates and unsupported placeholders must remain disabled rather than guessed.

Do not construct a synthetic archive URL manually and treat success on one provider as a generic contract.

## Favorites and source variants

Favorites are user-owned logical entries and can survive deletion/re-import of the original playlist. Removing a Favorite must not remove the original channel from its playlist.

If playback from Favorites resolves to an unexpected source, inspect the available source variants and preferred-source selection. Preserve provenance when reporting the issue: playlist/source, group and channel identity are relevant.

## Network diagnostics

When several unrelated IPTV sources fail at once, check the TV device network before changing application settings. Useful observations include:

- whether DNS/HTTP access works from the device;
- whether another known-good playlist endpoint responds;
- whether failures are immediate or timeout-like;
- whether reconnecting the device network changes the result.

Do not log or publish credentials, provider tokens, PIN values or other secrets while collecting evidence.

## P2P / Torrent TV / Ace Live

P2P failures require evidence-driven diagnosis. Current transport policy is gated by Issue #159 real-device evidence. Do not increase peer caps, DHT budgets, request depth, timeouts, output buffers, or add an external Ace Stream Engine dependency merely to make a failing channel start.

For a reproducible P2P failure, preserve the correlated runtime fields when available (`startup_id`, `runtime_id`, generation/path/channel) and the exact producer-stage field names used by diagnostics/classification:

`scheduled -> selected -> sent -> chunk_ingress -> chunk_accepted/chunk_rejected -> piece_completed -> authenticated/authentication_rejected -> ts_resync_output -> media_appended`

Also keep Player-side READY/first-frame/first-audio and resolve-error evidence where emitted. The first missing transition determines the next investigation; later-stage failure must not be hidden by broad acquisition-policy changes.

A 40-character Ace `content_id` is a transport identity and must not automatically be treated as a BitTorrent infohash.

## Focus, D-pad and mouse problems

Record the screen/dialog, input device, focused control before the failure, exact key/click/wheel sequence, whether the focus indicator remained visible, and whether Back could recover the UI.

Long lists should remain usable by D-pad and mouse/touchpad. A shared focus/scroll fix must still protect Scanner semantics and the specialized production Player input path.

See `docs/REMOTE_AND_MOUSE.md` for the current production remote, keyboard, mouse/touchpad and Player focus/input contract.

## What to attach to a bug report

For a useful reproducible report include:

1. app build/commit;
2. Android TV / TV Box / emulator model and Android version;
3. affected screen and source type;
4. exact reproduction steps;
5. expected and actual result;
6. relevant on-screen error text;
7. whether the problem reproduces after reopening the app;
8. sanitized diagnostics/logs when available;
9. for Player/P2P issues, whether picture/audio was ever reached;
10. for navigation issues, the focused control and exact input sequence.

Never include credentials, tokens, private playlist URLs containing secrets, PINs or personally sensitive data in public reports.

## Validation expectations for fixes

A troubleshooting finding is not complete merely because one manual retry succeeds. A production fix must follow the repository workflow: fresh `main`, a narrow temporary branch, focused regression coverage where appropriate, the repository's full Android CI, required specialized exact-head guards, review resolution, and merge only when the PR is fully green and mergeable.

P2P/runtime behavior changes additionally require the evidence and hardware gates specified by the canonical roadmap; building an instrumentation APK alone is not real-device validation.
