# План bounded XMLTV disk cache

_Добавлен: 26 августа 2026 после review `feat/epg-time-refresh-r1`._

## Зачем нужен этот increment

Текущий XMLTV cache в `PlaylistRepositoryImpl` в основном process-memory. Он защищён лимитами по входному XMLTV и heap headroom, но после process death/restart приложение теряет уже загруженный XMLTV и при следующем запросе может снова обращаться в сеть и повторно парсить большой feed.

Цель следующего EPG infrastructure increment — добавить **bounded persistent raw XMLTV snapshot cache**, не увеличивая heap и не превращая EPG в неограниченное локальное хранилище.

Рекомендуемая ветка: `feat/epg-disk-cache-r1`.

Порядок: после завершения/merge текущего time/refresh слоя (`feat/epg-time-refresh-r1`) и до тяжёлого EPG/Player UI polish, чтобы последующий UI уже использовал устойчивый cache contract.

## Архитектура: двухуровневый cache

### L1 — parsed memory cache

Сохранить существующую роль L1:

- в памяти хранится уже распарсенный `XmlTvData`;
- не увеличивать текущий bounded entry count ради производительности;
- hot-memory TTL остаётся коротким и предназначен для повторных UI-запросов в одном процессе;
- OOM/low-headroom по-прежнему очищает memory cache и включает backoff;
- запрещено сериализовать в Room весь parsed EPG graph только ради cold-start cache.

### L2 — persistent raw XMLTV snapshot

Добавить app-private store, например `cacheDir/epg/xmltv/`.

Предлагаемые ответственности:

- `EpgSnapshotStore` — абстракция чтения/записи/удаления/eviction;
- `FileEpgSnapshotStore` — Android/file implementation;
- `EpgSnapshotMetadata` — `loadedAtMs`, `lastAccessAtMs`, byte size, `etag`, `lastModified`, checksum, cache format version;
- `EpgCacheKey` — SHA-256 от source identity/URL, чтобы URL с token/query credentials не попадал в имя файла и diagnostics.

В disk cache хранить **raw decoded XMLTV bytes**, а не materialized `XmlTvData`. При чтении файл снова проходит через тот же bounded streaming parser и те же heap guards. Запрещены `readBytes()`, `body.string()` и другие full-buffer операции для большого EPG.

## Bounds

Начальные production bounds:

- максимум одного snapshot: **64 MiB**, то есть не выше существующего `MAX_EPG_INPUT_BYTES`;
- общий disk budget: **128 MiB**;
- максимум disk entries: **4**;
- eviction: LRU по `lastAccessAtMs`, с fallback на `loadedAtMs`;
- перед записью должен оставаться safety reserve свободного места не меньше `snapshotSize + 64 MiB`; если места недостаточно, EPG продолжает работать без disk write;
- cleanup выполняется opportunistic/lazy при первом обращении процесса и перед записью; отдельный частый WorkManager cleanup не нужен.

Эти числа являются bounded baseline и могут уменьшаться после TV Box field measurement, но не увеличиваются без отдельного доказательства по storage/heap/network telemetry.

## Freshness policy

Нужно разделить три понятия, которые сейчас частично смешаны:

1. **parsed-memory freshness** — короткий hot TTL внутри процесса;
2. **network freshness** — когда разрешено не обращаться к provider после cold start;
3. **stale fallback lifetime** — сколько старый snapshot можно использовать при временной сетевой ошибке.

Для L2:

- network freshness должна учитывать выбранный EPG refresh cadence (`6/12/24h`) и `loadedAtMs`, а не заставлять cold-start делать повторный download только потому, что истёк 15-минутный L1 TTL;
- после истечения network freshness сначала выполнять conditional request;
- `ETag` -> `If-None-Match`;
- `Last-Modified` -> `If-Modified-Since`;
- HTTP `304 Not Modified` обновляет freshness metadata без повторного скачивания тела;
- HTTP `200` atomically заменяет snapshot;
- transient/low-memory failure может использовать stale disk snapshot только если это разрешено существующей `EpgStaleFallbackPolicy`;
- permanent/malformed cases не должны бесконечно удерживать повреждённый или заведомо неверный snapshot;
- hard stale age для L2: **не более 96 часов от `loadedAtMs`**, что совпадает с текущим верхним future-retention horizon парсера; после этого snapshot удаляется и не используется как fallback.

Важно: глобальный `lastSuccessfulRefreshAtMs` остаётся scheduler signal, но disk store должен иметь собственный per-source `loadedAtMs`; нельзя считать все источники одинаково свежими только по одному глобальному timestamp.

## Read path

Порядок `getOrLoadXmlTv(source)`:

1. свежий L1 parsed entry -> вернуть без disk/network;
2. подходящий L2 snapshot -> streaming parse -> положить bounded parsed result в L1 -> вернуть;
3. если L2 требует revalidation -> conditional HTTP;
4. `304` -> обновить metadata -> parse существующего snapshot;
5. `200` -> stream network body одновременно через input bound во временный файл и parser/или сначала в bounded temp snapshot, затем atomically publish и parse;
6. transient network failure -> разрешённый stale L1/L2 fallback;
7. permanent/malformed/corrupt snapshot -> удалить invalid entry и вернуть контролируемую ошибку/backoff.

Не должно быть двух параллельных download одного source. Существующий EPG load serialization/mutex должен оставаться owner сетевого refresh path.

## Atomicity и восстановление после сбоя

Запись snapshot:

- только во временный файл в том же каталоге;
- проверка фактического byte count против 64 MiB limit;
- вычисление checksum во время streaming write;
- flush + best-effort `fsync`;
- atomic rename temp -> final;
- metadata публикуется только после успешного payload commit;
- orphan temp files удаляются при lazy cleanup;
- checksum/size/version mismatch на чтении означает `corrupt_drop`, а не crash приложения.

Process kill между download и rename не должен уничтожать предыдущий валидный snapshot.

## Security / privacy

- каталог cache только app-private;
- raw source URL, username/password, token/query secrets не использовать в filename;
- diagnostics логирует cache key prefix/source index, но не credential-bearing URL;
- snapshot не входит автоматически в diagnostics export/backup;
- clear-app-data очищает cache штатно;
- при удалении playlist/provider orphan snapshot удаляется opportunistically или eviction policy.

## Diagnostics

Добавить bounded telemetry без noisy per-program logging:

- `epg_cache_memory_hit`;
- `epg_cache_disk_hit`;
- `epg_cache_network_200`;
- `epg_cache_network_304`;
- `epg_cache_stale_fallback`;
- `epg_cache_evicted`;
- `epg_cache_corrupt_drop`;
- `epg_cache_write_skipped_low_storage`.

Полезные поля: ageMs, sizeBytes, freshness class, failure kind, elapsed parse/load time. Не логировать весь URL или XMLTV payload.

## Тесты

Обязательные regression gates:

- fresh process + valid disk snapshot -> network client не вызывается;
- simulated process restart -> snapshot переживает новый repository instance;
- expired snapshot + `304` -> body не скачивается, metadata обновляется;
- expired snapshot + `200` -> atomic replacement;
- transient failure + stale snapshot в разрешённом возрасте -> fallback success;
- permanent/malformed failure -> stale policy не обходится;
- snapshot >64 MiB -> rejected без OOM;
- total cache >128 MiB / >4 entries -> deterministic LRU eviction;
- checksum mismatch/truncated file -> entry удаляется, приложение не падает;
- insufficient free storage -> disk write пропущен, memory/network path продолжает работать;
- concurrent same-source callers -> один network load;
- разные source keys не раскрывают URL/token в filenames;
- parsing disk snapshot использует existing bounded parser/headroom guards;
- CI: `core:data`, `sync`, Android lint/unit/build exact-head green.

## Field acceptance на TV Box

Increment считается завершённым после CI и обычного пользовательского прогона:

1. открыть EPG и дождаться успешной загрузки;
2. полностью перезапустить приложение/процесс;
3. повторно открыть тот же EPG в пределах refresh interval;
4. подтвердить по diagnostics `disk_hit` и отсутствие полного повторного XMLTV download;
5. после истечения freshness подтвердить `304` либо один bounded `200`;
6. временно недоступный provider не должен лишать guide последнего допустимого stale snapshot;
7. disk budget не превышает 128 MiB, heap profile не ухудшается относительно текущего memory-safe baseline.

## Definition of Done

- persistent cache вынесен за `PlaylistRepositoryImpl` через узкую abstraction, а не разрастается внутри monolithic repository;
- L1 и L2 имеют отдельные явно тестируемые freshness policy;
- нет unbounded files, unbounded entries или unbounded reads;
- нет повторного полного XMLTV download после простого process restart внутри refresh interval;
- conditional HTTP поддержан;
- stale fallback bounded и failure-kind aware;
- corruption/power-loss safe atomic write path покрыт тестами;
- secrets не попадают в filenames/diagnostics;
- exact-head CI green;
- TV Box cold-start/restart field gate пройден;
- после этого можно продолжать EPG/Player UX increment без повторного изменения cache ownership.
