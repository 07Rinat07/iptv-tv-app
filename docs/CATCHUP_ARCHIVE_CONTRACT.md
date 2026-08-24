# Catch-up / archive playback contract

This document defines the shared archive URL boundary used by EPG and Player work.

## Ownership

`core:model/CatchUpPlaybackResolver.kt` is the canonical fail-closed policy for resolving a finished EPG programme into an archive playback URL. Feature modules may depend on this contract directly without depending on `core:data` implementation details.

`core:data/repository/CatchUpPlaybackResolver.kt` remains only as a compatibility adapter for existing data-layer callers and regression tests. It delegates to the model contract and must not develop an independent URL policy.

## Required evidence

Archive playback is enabled only from explicit persisted catch-up metadata attached to the channel. A normal live URL, URL query flag, EPG presence or provider name is never sufficient evidence by itself.

The resolver requires:

- explicit `ChannelCatchUpMetadata.mode`;
- a valid finished programme interval;
- an HTTP/HTTPS live URL for the currently supported modes;
- a valid declared `catchup-days` window when present;
- an explicit source template for `append` and `default` modes.

Unsupported provider-specific modes remain disabled until they have dedicated tested contracts.

## Supported bounded modes

- `append`: renders the declared source template and appends it to the live URL before any fragment;
- `default`: renders an explicit absolute HTTP/HTTPS archive URL template;
- `shift` / `timeshift`: emits the bounded `utc` / `lutc` query form.

Supported placeholders are intentionally limited to start/end timestamps and duration. Unknown placeholders fail closed.

Transport suffixes after `|` are preserved after successful archive URL resolution.

## Safety invariants

The resolver must never:

- infer archive support from a live URL alone;
- enable an unfinished programme;
- accept malformed programme windows;
- silently ignore an invalid declared catch-up range;
- resolve an unknown catch-up mode;
- broaden P2P/Ace transport policy.

This contract is independent from Issue #159. Archive/EPG work must not change Ace Live peer selection, DHT, request depth, timeout or buffer policy.

## Next integration step

The EPG detail action should call this single resolver for the selected channel/programme, expose the unsupported reason when resolution fails, and pass a successful resolved URL into the existing Player session/navigation path without creating a second playback runtime. Player launch wiring is intentionally a separate increment so routing/session ownership changes remain reviewable.
