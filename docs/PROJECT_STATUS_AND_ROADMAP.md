# Project status and roadmap

_Last updated: 2026-08-26 after merge of PR #251/#252/#253 and the following TV Box field retest._

This is the canonical current-state document. For immediate continuation use [`CURRENT_DEVELOPMENT_HANDOFF.md`](CURRENT_DEVELOPMENT_HANDOFF.md). Historical dated reports remain evidence but do not override these documents.

## Current integration baseline

Current `main` field baseline:

- `a1b3e5e086c0470d8c21e75f8a288610c61b986a` — `feat(player): add programme guide and nearby channel logos`;
- PR #251 merged — timezone correction + bounded 6/12/24h EPG refresh policy;
- PR #252 merged — bounded large-XMLTV transport envelope raised from 64 MiB to 128 MiB after field evidence of an 88,578,547-byte legitimate source;
- PR #253 merged — Player `Программа` action/dialog and nearby-channel logos using existing EPG state.

Important: #252 is **not** a persistent XMLTV disk cache. It changes only the bounded streaming transport envelope. Persistent L2 cache remains a separate planned increment.

## Release blockers and current field evidence

### #232 — embedded Torrent TV peer/handshake gap

Still the primary release blocker. The latest field run shows mixed results rather than one universal failure:

- some embedded P2P sessions reach first audio + first video frame and play normally;
- one failure class is weak acquisition/connect: tracker may yield only one candidate, that candidate fails TCP connection, and bounded DHT probes return zero useful peers;
- a separate failure class reaches a large loopback transfer / Media3 load but still reports no first audio or first video frame.

Keep these as separate investigations:

`discovered -> connected -> handshaked/qualified -> producing -> TS -> Media3 tracks/first frame`

Do not widen global startup timeout, player buffer, request depth, peer caps or heap as a substitute for identifying the first missing stage.

### EPG — current active defect

The old 64 MiB input guard is no longer the first blocker. The same field source that previously reported 88,578,547 bytes now reaches the XML parser, where repeated channel requests fail with:

`Invalid XMLTV format: unterminated entity ref (position:TEXT @1:48 ...)`

The Player UI itself is no longer the primary EPG problem:

- `Программа` button/dialog is visible and opens;
- nearby logos appear where channel metadata contains a logo;
- programme text remains `Программа не найдена` because the upstream XMLTV load fails before usable EPG data is produced.

Active branch:

`fix/epg-source-format-classification-r1`

Current increment:

1. bounded prefix classification before XML parsing;
2. distinguish XMLTV-looking input from HTML/error response, raw gzip, other XML, text and binary/unknown input;
3. never log raw payload or credential-bearing URL;
4. push inspected bytes back into the stream so the existing 128 MiB byte guard still counts the body once;
5. no global `&` replacement or speculative XML sanitizer.

If the field error remains `unterminated entity ref` after this gate, that becomes evidence that the body is XMLTV-looking but internally malformed. Only then consider a separate narrowly tested compatibility repair based on actual payload evidence.

## EPG infrastructure already integrated

The merged EPG baseline now includes:

- conservative channel matching and invalid/placeholder programme filtering;
- device-local timezone as default display behavior;
- bounded manual correction in 30-minute steps;
- one corrected timeline for Guide, Player and RecordingSchedule;
- 6/12/24h refresh cadence, default 24h;
- stale-on-start gating;
- sequential/serialized refresh;
- virtual aggregate exclusion from EPG refresh accounting;
- freshness re-check to avoid duplicate startup/periodic downloads;
- Player programme dialog and nearby-channel logos.

## Planned persistent XMLTV disk cache

Canonical design: [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md).

Planned branch remains:

`feat/epg-disk-cache-r1`

It is queued **after source/parser correctness is understood**. The plan must use the same current transport envelope as production: a single valid snapshot may be up to the current `EpgInputSafetyPolicy.MAX_INPUT_BYTES` (128 MiB), while the aggregate disk budget remains separately bounded. The observed 88.6 MB source must not be made permanently non-cacheable by an obsolete 64 MiB snapshot cap.

## Memory/catalog stability

The current field failure does not justify increasing heap. Keep the existing bounded parser, retained programme/channel limits, sequential EPG work and large-catalog regressions. Persistent cache, when implemented, must store raw bounded snapshots rather than a materialized Room EPG graph.

## Execution order

1. Complete `fix/epg-source-format-classification-r1` with deterministic tests and exact-head CI.
2. Field-test that exact integrated build and classify the EPG source from the new failure/success signature.
3. If XMLTV-looking malformed input is proven, fix only the demonstrated XML compatibility defect; otherwise fix the actual source format path (for example raw gzip) in its own bounded change.
4. Return to #232 and keep discovery/connect and TS->Media3 no-first-frame failures as separate focused P2P increments.
5. Implement `feat/epg-disk-cache-r1` after EPG source correctness is stable; do not mix it into parser recovery or P2P.
6. Re-run large-catalog memory and longer TV Box soak before release readiness.

## Development discipline

For each focused change:

1. branch from current `main`;
2. one thematic PR;
3. deterministic regression where possible;
4. minimal bounded diff;
5. relevant unit/lint/guard/build tests;
6. exact-head CI;
7. merge only a green exact head;
8. verify integrated `main`;
9. field-test device/network behavior on the exact integrated build;
10. update handoff/status/roadmap when actual state changes.

CI is a regression gate, not field proof. New field evidence selects the next change; broader limits or unrelated rewrites do not.
