# План bounded XMLTV disk cache

_Актуализирован: 26 августа 2026 после field evidence большого XMLTV source и merge PR #252._

## Статус

Persistent XMLTV disk cache **ещё не реализован**. PR #252 увеличил только streaming transport envelope до 128 MiB; это не L2 cache.

Планируемая отдельная ветка:

`feat/epg-disk-cache-r1`

Начинать её после того, как текущий `fix/epg-source-format-classification-r1` закроет source/parser correctness. Disk cache не должен скрывать malformed source и не должен смешиваться с P2P или Player UI.

## Field constraint, изменивший план

На TV Box был зафиксирован реальный EPG response размером **88,578,547 байт**. Старый transport cap 64 MiB блокировал этот источник; #252 перевёл production envelope на bounded 128 MiB.

Следствие: прежний disk snapshot cap 64 MiB устарел. Если оставить его, реально используемый 88.6 MB source никогда не сможет пережить process restart через L2 cache.

Новый contract:

- per-snapshot hard cap = `EpgInputSafetyPolicy.MAX_INPUT_BYTES`, сейчас **128 MiB**;
- aggregate disk budget остаётся **<=128 MiB**;
- максимум entries остаётся **4**;
- один большой snapshot может занять большую часть aggregate budget и вытеснить старые entries; per-entry cap не означает резервирование 128 MiB на каждый source;
- эти bounds можно уменьшать по field telemetry, но не увеличивать без отдельного storage/network evidence.

## Зачем нужен increment

Текущий hot path хранит parsed EPG в process memory. После process death/restart уже загруженный XMLTV теряется и может скачиваться/парситься снова.

Цель L2 — app-private bounded raw XMLTV snapshot, который:

- переживает process restart;
- не требует materialize всего EPG graph в Room;
- не увеличивает heap;
- не допускает unlimited files/bytes;
- использует тот же streaming parser, input envelope и heap/program/channel guards.

## Архитектура

### L1 — parsed memory cache

Сохранить маленький существующий parsed cache:

- короткий hot TTL;
- bounded entry count;
- no full-table persistence;
- OOM/low-headroom по-прежнему очищает L1 и включает backoff.

### L2 — raw XMLTV snapshot

App-private store, например `cacheDir/epg/xmltv/`.

Узкие ответственности:

- `EpgSnapshotStore` — read/write/delete/evict contract;
- `FileEpgSnapshotStore` — file implementation;
- `EpgSnapshotMetadata` — `loadedAtMs`, `lastAccessAtMs`, size, `etag`, `lastModified`, checksum, format version;
- `EpgCacheKey` — SHA-256 от source identity, чтобы credential-bearing URL не попадал в filename/diagnostics.

Хранить raw decoded XMLTV bytes, не `XmlTvData`. На чтении snapshot снова проходит source-format gate, `EpgBoundedInputStream`, streaming XML parser и heap guards. Запрещены full-buffer `readBytes()`, `body.string()` и аналогичные операции на большом feed.

## Bounds и storage policy

Production baseline:

- snapshot <= **128 MiB**;
- total cache <= **128 MiB**;
- entries <= **4**;
- deterministic LRU по `lastAccessAtMs`, fallback `loadedAtMs`;
- перед записью eviction освобождает aggregate budget;
- после eviction должен оставаться free-space safety reserve не меньше `snapshotSize + 64 MiB`;
- если reserve обеспечить нельзя — `write_skipped_low_storage`, но live EPG path продолжает работу;
- cleanup lazy/opportunistic, без частого отдельного WorkManager job.

Если `Content-Length` отсутствует, размер всё равно ограничивается streaming byte counter. Snapshot, превысивший current input envelope, не публикуется.

## Freshness policy

Разделять:

1. L1 parsed-memory freshness;
2. L2/network freshness;
3. stale fallback lifetime.

Для L2:

- network freshness учитывает выбранный cadence 6/12/24h и per-source `loadedAtMs`;
- глобальный `lastSuccessfulRefreshAtMs` остаётся scheduler signal, но не заменяет per-source metadata;
- после expiration использовать conditional HTTP;
- `ETag` -> `If-None-Match`;
- `Last-Modified` -> `If-Modified-Since`;
- `304` обновляет metadata без body download;
- `200` atomically заменяет snapshot;
- transient network failure может использовать stale snapshot только по `EpgStaleFallbackPolicy`;
- malformed/permanent source не удерживается как бесконечный stale success;
- hard stale age <= **96h**.

## Read path

1. fresh L1 -> return;
2. eligible L2 -> source-format preflight + streaming parse -> bounded L1;
3. expired/revalidation-required L2 -> conditional HTTP;
4. `304` -> validate existing file/checksum, update metadata, parse snapshot;
5. `200` -> bounded temp file -> validate size/checksum/format -> atomic publish -> parse;
6. transient failure -> allowed stale L1/L2 fallback;
7. malformed/corrupt/permanent failure -> fail closed / drop invalid snapshot according to policy.

Один source не должен одновременно скачиваться несколькими callers. Existing EPG serialization остаётся owner refresh path.

## Atomicity

- temp file в том же directory;
- streaming byte count <= current 128 MiB envelope;
- checksum во время write;
- flush + best-effort fsync;
- atomic rename payload;
- metadata publish только после payload commit;
- old valid snapshot не удаляется до успешной замены;
- orphan temp cleanup lazy;
- checksum/size/version mismatch -> `corrupt_drop`, не crash.

## Source-format correctness

Disk cache не должен кэшировать произвольный HTML/error/binary response как XMLTV.

Перед publish применить тот же bounded source-format contract, что и network parser path:

- XMLTV-looking -> eligible;
- HTML/raw gzip/other XML/text/binary/empty -> fail closed до snapshot publish, если соответствующий формат явно не поддержан отдельным tested decoder;
- raw payload и URL secrets не логировать;
- никакой глобальной замены `&`.

Если provider реально отдаёт raw gzip, поддержка decompression должна быть отдельным bounded decoder layer с тестами; cache plan не предполагает её автоматически.

## Security / privacy

- app-private directory;
- filename/key не содержит raw URL, username, password, token/query secrets;
- diagnostics использует key prefix/source index, не payload;
- snapshot не входит автоматически в diagnostics export/backup;
- clear-app-data очищает cache штатно.

## Diagnostics

Bounded events:

- `epg_cache_memory_hit`;
- `epg_cache_disk_hit`;
- `epg_cache_network_200`;
- `epg_cache_network_304`;
- `epg_cache_stale_fallback`;
- `epg_cache_evicted`;
- `epg_cache_corrupt_drop`;
- `epg_cache_write_skipped_low_storage`.

Поля: ageMs, sizeBytes, freshness, failure kind, elapsed parse/load. Без URL secrets/XML payload.

## Tests

Обязательные gates:

- fresh process + valid disk snapshot -> no network;
- process restart -> snapshot reusable;
- 88.6 MB-class valid fixture policy is not rejected by obsolete 64 MiB snapshot bound;
- >128 MiB snapshot -> rejected without OOM;
- aggregate >128 MiB / >4 entries -> deterministic LRU eviction;
- expired + `304` -> no body download;
- expired + `200` -> atomic replacement;
- transient + allowed stale -> fallback;
- malformed/permanent -> fail closed;
- HTML/error body not published as XMLTV snapshot;
- checksum mismatch/truncated file -> drop without crash;
- low storage -> skip write, live path survives;
- concurrent same-source callers -> one network load;
- filenames do not expose secrets;
- disk parse reuses source-format, streaming byte and heap guards;
- exact-head `core:data` + Android/guard CI.

Large fixtures should be generated/streamed in tests where possible rather than committed as giant repository blobs.

## TV Box field acceptance

1. successful EPG load from a real source, including the observed ~88.6 MB class;
2. process restart;
3. reopen same EPG inside freshness interval;
4. diagnostics shows `disk_hit`, no full re-download;
5. later revalidation shows `304` or one bounded `200`;
6. offline/transient failure respects stale policy;
7. total cache <=128 MiB;
8. heap profile does not regress relative to current streaming baseline.

## Definition of Done

- L2 isolated behind narrow abstraction;
- no unbounded reads/files/entries;
- valid current large source can be cached within aggregate budget;
- no process-restart full re-download while network-fresh;
- conditional HTTP works;
- writes are atomic/corruption-safe;
- source-format gate prevents caching non-XMLTV garbage;
- secrets stay out of filenames/diagnostics;
- exact-head CI green;
- TV Box restart/disk-hit field gate passed.
