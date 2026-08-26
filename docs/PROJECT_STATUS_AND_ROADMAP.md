# Project status and roadmap

_Last updated: 2026-08-26 after PR #249 and #250 merged; PR #251 is the final EPG integration candidate before the next TV Box field run._

This is the canonical current-state document. For continuation details read [`CURRENT_DEVELOPMENT_HANDOFF.md`](CURRENT_DEVELOPMENT_HANDOFF.md). Historical dated reports remain evidence but do not override these two documents.

## Current baseline

The application can browse a large IPTV catalog, enter the TV-first Player, render ordinary IPTV across multiple codecs/resolutions and remain within the captured device heap. The primary release blocker remains embedded Torrent TV peer acquisition/qualification; EPG quality is the active product-quality recovery track being integrated in bounded increments.

Current merged baseline:

- PR #249 merged to `main` as `f2cb6e02c8725e49032038942521232438e18f38` — diversified bounded Mainline DHT bootstrap roots without widening startup/runtime safety budgets;
- PR #250 merged to `main` as `1d4c86569537a525661525dfb949c0aa6754b284` — conservative EPG matching recovery and invalid/placeholder programme filtering.

PR #251 (`feat/epg-time-refresh-r1`) may merge only after it is cleanly based on this `main` and its exact head passes Database Unit CI, Player Refactor Guard and Android CI. The next development session must verify the actual `main` SHA/PR status before assuming #251 is present in the field APK.

## #229 — memory/import OOM

The original immediate field OOM was not reproduced after replacing hot full-table Favorites materialization with bounded identity paging/full-row lookup only for matched favorites. Keep the deterministic large-catalog regression and real-device soak as release gates. Do not raise heap limits to hide regressions.

## #230 — Home root integration

Field-recovered. The TV-first Home/root layout is visible without the previously blocking legacy shell. Reopen only on a new reproduction.

## #231 — production Player UX

Field-recovered for the original clipping/legacy-looking regression. Ordinary IPTV reaches first frame. Continue programme/nearby-channel UX as separate bounded EPG/Player increments; preserve one playback/P2P session across dashboard/fullscreen.

## #232 — embedded Torrent TV peer/handshake gap

**Still open and the primary release blocker.** Previous field evidence showed weak peer diversity and many failed KRPC queries. PR #249 adds bootstrap diversity but is not field-proven until the next TV Box run.

Analyse each supplied session through:

`discovered -> connected -> handshaked/qualified -> producing -> first media/player ready`

Fix the first missing stage in this order:

1. DHT/tracker acquisition, bootstrap/routing diversity, failed-query causes;
2. candidate retention/deduplication/backoff;
3. TCP connect lifecycle;
4. BitTorrent/Ace Live handshake qualification;
5. metadata/live-window;
6. request scheduling/sent/chunk/piece/authentication;
7. TS output;
8. Player boundary only after upstream media production is proven.

Do not compensate for missing protocol progress by increasing global startup timeout, player buffering, request depth or peer caps.

## #233 — rapid playback request churn

Keep as a reproducibility item until a field run proves that focus movement, rather than explicit rapid selection, triggers heavy playback requests. Focus alone must not start each intermediate channel.

## EPG recovery state

PR #250 is merged and supplies the base matching/display recovery.

PR #251, when merged after a green exact-head run, supplies:

- device-local timezone as default display behavior;
- bounded manual EPG correction in 30-minute steps;
- one corrected timeline for Guide, Player and RecordingSchedule;
- 6/12/24h refresh cadence, default 24h;
- stale-on-start gating;
- removal of the old 30-minute periodic refresh;
- sequential/serialized XMLTV refresh;
- exclusion of virtual aggregate playlists from source-refresh accounting;
- freshness gating to avoid duplicate startup/periodic downloads.

Persistent XMLTV disk cache is intentionally **not** part of #251.

## Planned EPG infrastructure follow-up

Canonical design: [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md).

Next separate branch: `feat/epg-disk-cache-r1`.

Required properties:

- small parsed L1 memory cache + app-private raw XMLTV L2 disk cache;
- no full re-download after a simple process restart while source remains network-fresh;
- per-source freshness and conditional HTTP (`ETag`, `Last-Modified`, `304`);
- <=64 MiB per snapshot, <=128 MiB total, <=4 entries, deterministic LRU;
- hard stale age <=96h and failure-kind-aware stale fallback;
- atomic temp-write/validation/checksum/rename;
- no credential-bearing URL data in filenames/diagnostics;
- existing streaming parser and heap guards reused on disk reads;
- corruption, low-storage, restart, 304/200, eviction and concurrent-load tests;
- exact-head CI plus real TV Box restart/disk-hit validation.

## Current execution order

1. Finish #251 only if its clean exact head is green; then verify `main` CI.
2. Build a fresh APK from that exact `main` and perform the normal TV Box field run.
3. Export diagnostics and screenshots.
4. Analyse Torrent TV by first missing lifecycle stage and EPG by source -> match -> validity -> time -> UI.
5. Choose the next focused change from that evidence.
6. `feat/epg-disk-cache-r1` is the queued EPG infrastructure follow-up; later EPG/Player UX adds programme list/action and `Сейчас/Далее`/logos for nearby channels.
7. Re-run large-library memory/longer device soak before release readiness.

## Development loop

For each focused change:

1. start from current `main`;
2. one thematic branch/PR;
3. deterministic regression where possible;
4. minimal diff;
5. relevant unit/lint/Room/guard/build tests;
6. exact-head CI;
7. merge only green exact head;
8. verify `main` after merge;
9. field-test the exact integrated build when behavior depends on device/network conditions;
10. update `CURRENT_DEVELOPMENT_HANDOFF.md`, this file and `ROADMAP.md` when the actual state changes.

CI is a regression gate, not field proof. A field failure must be classified from evidence rather than masked by broader bounds or unrelated rewrites.
