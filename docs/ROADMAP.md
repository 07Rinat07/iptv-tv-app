# План проекта

_Актуализирован: 26 августа 2026 после merge PR #251/#252/#253 и нового TV Box field retest._

Канонический handoff: [`CURRENT_DEVELOPMENT_HANDOFF.md`](CURRENT_DEVELOPMENT_HANDOFF.md). Статус: [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md). Persistent EPG cache plan: [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md).

## Цель

Стабильное Android TV / TV Box приложение, которое:

- работает с большими IPTV/Torrent TV каталогами без OOM/UI freeze;
- использует TV-first Home/Player и корректный D-pad focus;
- воспроизводит ordinary IPTV через Media3 с bounded fallback;
- воспроизводит Torrent TV через собственный embedded P2P/Ace Live runtime без обязательного внешнего Ace Engine;
- показывает EPG в локальном времени TV Box с bounded manual correction;
- обновляет EPG без повторных лишних загрузок и без full-body heap buffering;
- сохраняет bounded cache/provenance и не маскирует protocol/data defects увеличением абсолютных лимитов.

## Integration baseline

Текущий field baseline `main`:

`a1b3e5e086c0470d8c21e75f8a288610c61b986a`

Merged:

- #249 — P2P DHT bootstrap diversity;
- #250 — EPG field matching/invalid programme recovery;
- #251 — EPG timezone correction + low-load refresh;
- #252 — bounded 128 MiB XMLTV transport envelope;
- #253 — Player programme dialog + nearby logos.

#252 не реализует persistent disk cache.

## Release gates

### P0 — memory/catalog stability (#229)

- bounded paging/projections;
- no OOM/process death on large catalog;
- no heap inflation as workaround;
- deterministic large fixture + TV Box soak.

### P0 — Home/Player integration (#230/#231)

Original field regressions recovered. Reopen only on new evidence. Programme/nearby UI now exists; EPG data failure is upstream.

### P0 — embedded Torrent TV parity (#232)

Primary release blocker. Latest field run proves mixed behavior:

- some P2P sessions play successfully;
- some fail at discovery/connect;
- a separate class transfers substantial loopback/Media3 data but never reaches first audio/video frame.

Keep diagnosis staged:

`discovery -> connect -> handshake/qualification -> producer -> TS -> Media3 tracks -> first frame`

Do not increase global timeout/buffer/request-depth/peer-cap bounds without evidence.

### P1 — unintended playback churn (#233)

Keep as reproducibility item. Focus movement alone must not start intermediate channels.

## EPG sequence

### Integrated

- #250: fail-closed matching + invalid programme filtering;
- #251: TV Box timezone, manual correction, 6/12/24h refresh, serialized work;
- #252: valid large XMLTV sources up to current 128 MiB transport envelope;
- #253: `Программа` dialog and nearby logos.

### Active: source-format/parser recovery

Branch:

`fix/epg-source-format-classification-r1`

Field signature:

`Invalid XMLTV format: unterminated entity ref (position:TEXT @1:48 ...)`

Work:

- bounded 8 KiB prefix inspection;
- XMLTV / HTML / raw gzip / other XML / text / binary classification;
- `PushbackInputStream` so inspected bytes are not lost and the body is counted once;
- preserve 128 MiB byte limit + heap/program/channel limits;
- fail closed, no payload/secret logging;
- no speculative global ampersand sanitizer.

Acceptance is deterministic unit coverage + exact-head CI + TV Box retest. If the parser error remains after XMLTV classification, the next increment targets only the demonstrated malformed XML pattern.

### Planned: persistent XMLTV disk cache

Branch:

`feat/epg-disk-cache-r1`

Start only after source/parser correctness is understood. Required properties:

- L1 parsed memory + L2 app-private raw snapshot;
- process restart inside network-fresh interval without full re-download;
- `ETag`/`Last-Modified`/`304`;
- per-snapshot cap equals current 128 MiB XMLTV input envelope;
- aggregate disk budget <=128 MiB;
- <=4 entries, deterministic LRU;
- stale <=96h and failure-kind aware;
- atomic temp write/checksum/rename;
- secret-safe cache key;
- low storage -> skip write, not EPG failure;
- disk reads reuse streaming parser/headroom guards;
- restart/disk-hit/304/200/corruption/eviction/concurrency tests.

The observed 88,578,547-byte field source must fit the snapshot contract; the obsolete 64 MiB snapshot cap is removed.

## Current execution order

1. Complete `fix/epg-source-format-classification-r1`.
2. Exact-head CI, then merge only if green.
3. TV Box retest of exact integrated build.
4. If needed, one evidence-specific XML compatibility/source-format increment.
5. Return to #232 and split discovery/connect from TS->Media3 no-first-frame work.
6. Implement `feat/epg-disk-cache-r1` separately after EPG source correctness is stable.
7. Long large-catalog / memory / runtime soak before release readiness.

## Field gate

For every field build record the exact commit. Check:

- ordinary IPTV first frame and switching;
- several Torrent TV channels, including previously failing sessions;
- EPG load, programme dialog, timezone/correction and channel matching;
- logos/nearby list/D-pad/layout at TV resolution;
- process restart and heap/runtime cleanup;
- diagnostics export with the first missing stage, not only the final UI symptom.

## Workflow

1. current `main`;
2. one thematic branch;
3. deterministic regression where possible;
4. minimal bounded diff;
5. relevant unit/lint/guard/build gates;
6. exact-head CI;
7. squash merge only green exact head;
8. verify integrated `main`;
9. field-test device/network behavior on that exact build;
10. sync handoff/status/roadmap.

CI is a regression gate, not field proof. P2P, EPG source/parser, EPG disk cache and Player UI stay in separate PRs.
