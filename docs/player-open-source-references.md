# Player implementation references

The player hardening work follows public Android TV and Media3 patterns without importing incompatible code.

## Reused under permissive terms

- AndroidX Media3 / ExoPlayer — Apache License 2.0. The project already depends on the official Media3 modules for core playback, HLS, DASH, RTSP, SmoothStreaming and UI.
- FireVisionIPTV — MIT License. Used as an architectural reference for category browsing, favorites and EPG-oriented TV navigation; no source file was copied verbatim.

## Reference only

- NuvioTV — GPL-3.0. Reviewed only for TV-first interaction ideas. GPL source was not copied into this proprietary repository.
- Other IPTV applications without a compatible permissive license were used only to compare user-facing behavior.

## Design decisions

- The video surface owns mouse, touchpad and remote-center fullscreen toggling.
- A single fullscreen affordance is shown in the lower-right corner; the fullscreen overlay does not expose a duplicate Tracks button.
- Channel rows use stable channel IDs, auto-scroll to the active channel, and show current EPG data only when a matching XMLTV entry exists.
- EPG time is rendered from epoch timestamps in the device time zone. Missing guide data is reported explicitly instead of being fabricated.
- Buffering uses Media3 LoadControl supplied by the existing adaptive planner, decoder fallback, device-memory video caps and bounded live-stream recovery.
