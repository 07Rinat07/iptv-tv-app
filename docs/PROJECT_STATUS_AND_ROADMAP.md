# Project status and roadmap

_Last updated: 2026-08-25 after the post-fix manual device retest and exported diagnostics._

This is the canonical current-state document. Historical dated reports remain evidence, but they do not override this page.

## Current baseline

The latest `main` recovery line was manually retested on a real Android device with a large public IPTV catalog and the production TV/player UI.

Result: **ordinary IPTV and the corrected TV UI are usable; embedded Torrent TV peer acquisition remains the primary release blocker**.

The retest confirms that the application can browse the large catalog, enter the player, render video, switch ordinary IPTV channels and remain within the device heap during the captured session. The remaining field weakness is concentrated in the embedded Ace Live/Torrent TV discovery and peer-qualification path rather than the Media3 player boundary.

The original recovery baseline remains documented in [`FIELD_VALIDATION_2026-08-25.md`](FIELD_VALIDATION_2026-08-25.md).

## Field retest status

### #229 — P0 memory/import OOM

The original process-death defect was fixed by replacing hot full-table Favorites materialization with bounded identity paging/full-row lookup only for matched favorites.

Post-fix field evidence is positive: the device session browsed an approximately 1800-channel provider list and the exported diagnostics ended at about 36 MB used from a 256 MB process heap after roughly 17 minutes of process uptime.

Keep the deterministic 2500-channel regression gate. Do not treat this one device run as a substitute for the existing large-fixture gate, but the original immediate field OOM was not reproduced.

### #230 — P0 Home root integration

The post-fix screenshot retest shows the intended TV-first Home/root layout without the previously blocking opaque legacy shell surface. Treat the visible Home regression as field-recovered unless a new reproduction is supplied.

### #231 — P0 production new Player UX

The post-fix player retest shows the TV player surface, channel rail and overlay controls rendered within the screen bounds. Ordinary IPTV video reaches first frame across multiple codecs/resolutions in the exported diagnostics.

Treat the previously reported clipping/legacy-looking production Player regression as field-recovered unless a new reproduction is supplied.

### #232 — P0 embedded Torrent TV peer/handshake gap

**Still open and now the first engineering priority.** Same-device behavior remains materially weaker than the Ace Stream benchmark because the embedded runtime often has too little peer diversity before TCP/Ace qualification.

Latest exported diagnostics show a representative startup in which:
- the initial tracker fast path returned only one peer;
- that peer failed to connect;
- the startup DHT probe consumed its full 7-second bounded window, issued 34 queries with 15 failed queries, but still returned only one peer;
- that candidate also failed initially;
- a connection was finally observed at about 15.96 seconds from startup.

The code already describes two independent bounded DHT startup probe rounds, but the configured maximum was one. The first follow-up change is therefore to execute two bounded probe rounds while preserving the existing 7-second per-round budget and four-peer target. This increases routing-path diversity without delaying the initial tracker candidate or widening the global preparation timeout.

After that change, revalidate the same fixed A/B channel matrix and compare discovered -> connected -> handshaked/qualified -> producing, plus time to first qualified peer and first media.

If two bounded probe rounds still return only one unusable candidate, continue #232 in this order:
1. DHT routing/bootstrap diversity and failed-query causes;
2. candidate retention/deduplication/backoff interaction;
3. TCP connect lifecycle;
4. Ace Live handshake qualification;
5. metadata/live-window transition;
6. request scheduling and authenticated piece production;
7. TS output only after upstream production is proven.

Do not compensate for missing peers by increasing player buffering or absolute startup failure bounds.

### #233 — P1 rapid playback request churn

The diagnostics still contain bursts of closely spaced `player_play_request` events. The manual retest did not establish that this is an autonomous focus-trigger bug rather than intentional rapid channel selection, so keep it as a reproducibility item instead of changing playback behavior speculatively.

## Confirmed working baseline

Ordinary IPTV must not be rewritten wholesale.

The retest contains repeated Media3 first-video-frame events for H.264/AAC, H.264/MP2 and MPEG-2/MP2, including HD and SD streams. Most captured playback-boundary sessions reached READY with zero rebuffers; one captured session had six rebuffers totaling about 8.9 seconds, so buffering/stream quality remains observable but is not the primary blocker while the P2P discovery gap is unresolved.

EPG matching is also incomplete for several channel names in the export. Keep EPG normalization/matching as post-#232 product-quality work unless it blocks a required archive/guide flow.

## Current execution order

1. #232 execute and validate two bounded independent startup DHT probe rounds.
2. #232 use the resulting field diagnostics to fix the first missing stage: discovery diversity -> TCP -> handshake -> producer.
3. #233 reproduce focus/playback request churn before changing behavior.
4. Re-run the large-library memory fixture and a real-device large-catalog soak as a release gate.
5. Resume EPG/archive/polish after the Torrent TV blocker is materially improved.

## Recovery development loop

For each focused change: create a temporary branch -> add/adjust a deterministic regression -> run the full relevant CI/build gate -> fix and rerun failures -> merge only the green exact head to `main` -> continue with the next item in this document.

A transient CI/job failure should be rerun. A deterministic failure should be fixed on the same branch and retested. Do not merge a red or untested head, and do not start an unrelated subsystem while the current blocker still has a concrete next step.

## Acceptance policy

CI is a regression gate, not field proof.

For every P0:
- merge only green exact-head code;
- build fresh `main`;
- manually reproduce the previously failing scenario;
- capture screenshot/video where relevant;
- export diagnostics;
- for Torrent TV, use fixed same-device A/B channels.

No telemetry-only, architecture-only or cosmetic increments should run ahead of this sequence.
