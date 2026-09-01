# План проекта

`ROADMAP.md` хранит устойчивые технические приоритеты, последовательность архитектурных работ и release gates. Текущий SHA, активные ветки, единичные CI runs и field evidence проверяются непосредственно в GitHub Issues, Pull Requests и Actions и не копируются сюда как быстро устаревающий snapshot.

## Цель

Стабильное Android TV / TV Box приложение, которое:

- работает с большими IPTV/Torrent TV каталогами без OOM и UI freeze;
- имеет TV-first Home/Player с предсказуемым D-pad и mouse/touchpad управлением;
- воспроизводит ordinary IPTV через Media3 с bounded LibVLC fallback;
- воспроизводит Torrent TV через собственный embedded P2P/Ace Live runtime без обязательной зависимости от внешнего Ace Engine;
- загружает и сопоставляет EPG потоково и с контролируемыми memory/network/storage bounds;
- показывает archive/catch-up только при реальной поддержке источником;
- сохраняет диагностику по первой отсутствующей стадии вместо маскировки дефектов увеличением timeout/buffer/heap лимитов.

## Приоритеты

### P0 — embedded Torrent TV

Основная P2P задача ведётся в Issue #232.

Диагностика и исправления должны сохранять явную лестницу:

`discovered -> connected -> handshaked/qualified -> useful window -> producing -> TS -> Media3 tracks -> first frame/audio`

Discovery/acquisition, transport qualification, request/producer и P2P-to-Media3 boundary — отдельные defect boundaries. Не расширять глобальные startup timeout, request depth, peer caps, buffers или heap без измеренного evidence конкретного лимита.

Устойчивый контракт discovery-state описан в [`P2P_DISCOVERY_STATE.md`](P2P_DISCOVERY_STATE.md).

#### Текущая архитектурная база P2P

Следующие механизмы считаются базовым контрактом и должны сохраняться при дальнейших изменениях:

- tracker, DHT и `ut_pex` являются независимыми источниками одного bounded candidate pool;
- TCP/uTP `connected` не означает qualified peer: квалификация происходит только после успешного Ace Live protocol handshake;
- final pre-handshake failures сохраняются между короткими runtime для точного `swarm + endpoint`, а повторные ошибки получают bounded escalating backoff;
- успешная protocol qualification очищает negative transport memory для endpoint;
- независимые tracker sources опрашиваются конкурентно в bounded fan-out, поэтому один dead tracker не блокирует здоровые источники;
- endpoint diagnostics позволяют коррелировать повторные transport races без сохранения raw IP/host/port;
- DHT routing/reputation state является optimization, а не обязательным условием bootstrap/playback correctness;
- stale playback generation не может получить ownership после более новой generation.

#### Последовательность P0 работ

Работы выполняются в указанном порядке. Следующий слой не должен использоваться для маскировки дефекта предыдущего слоя.

##### 1. Acquisition и discovery diversity

1. Сначала использовать уже известные eligible candidates из текущего runtime, включая `ut_pex`/recent same-swarm candidates, не делая новый tracker/DHT walk обязательным gate перед ними.
2. Выполнять bounded tracker+DHT acquisition только для оставшегося qualification demand.
3. Сохранять один общий `maxStartsPerCycle` и hard peer/socket cap независимо от источника кандидата.
4. Продолжать bounded alternative discovery, если tracker fast path дал endpoints, но они не квалифицируются/не производят media.
5. Поддерживать bounded verified DHT routing contacts между runtime/process restarts.
6. Не позволять одному source class монополизировать candidate set: tracker/DHT/PEX diversity должна сохраняться до достаточного qualified/productive peer set.
7. Event-driven/coalesced refill может сокращать задержку PEX, но не должен создавать overlapping ownership или бесконтрольные discovery loops.

##### 2. Shared transport eligibility, backoff и reputation

1. Один и тот же same-swarm endpoint должен подчиняться общей failure-memory независимо от того, повторно пришёл он из tracker, DHT, PEX или runtime memory.
2. Финальная candidate selection обязана исключать endpoint до истечения shared `retryNotBefore`, а не только локального refill backoff.
3. First failure остаётся кратковременным bounded retry; повторные pre-handshake failures получают более длинный bounded backoff, но не permanent ban.
4. Successful Ace handshake очищает negative eligibility state.
5. Candidate ranking учитывает verified useful-window/media evidence выше неподтверждённой source multiplicity.
6. Reputation всегда scoped точным swarm key, имеет TTL/size bounds и не превращается в глобальный blacklist.
7. Нельзя повторно тратить ограниченные TCP/uTP race slots на endpoint, который уже находится в authoritative backoff.

##### 3. Qualification и productive peer set

1. Различать `connected`, `handshaked/qualified`, useful-window и producing peers в runtime state и diagnostics.
2. Неподтверждённые active sockets потребляют hard capacity, но не должны удовлетворять qualified target.
3. Квалифицировать небольшой набор независимых candidates параллельно, не превышая hard peer cap.
4. При stale/non-producing pool разрешать bounded replacement/probe, но не открывать дополнительные sockets сверх hard capacity.
5. Request scheduler не должен считать peer полезным только по факту connection/handshake, если advertised window не покрывает playback demand.

##### 4. Streaming-aware piece/request scheduler

Этот этап начинается только после того, как field evidence показывает достаточную acquisition/qualification стабильность.

1. Playback-critical window получает sequential/high priority scheduling от текущего consumer cursor.
2. Вне критического окна использовать swarm-friendly selection, включая rarest-first/availability-aware выбор там, где это применимо к protocol model.
3. Предпочитать completion уже частично собранных critical pieces перед созданием избыточного fragmented in-flight state.
4. Per-peer request pipeline остаётся bounded и адаптируется по measured peer quality/latency, а не глобальным увеличением request depth.
5. Seek/new playback generation немедленно инвалидирует obsolete priority window и stale requests.
6. Choked, stalled, slow или repeatedly non-producing peers не должны удерживать critical request ownership бесконечно.
7. Scheduler должен сохранять возможность requeue critical requests при disconnect/replacement без дублирования completed/authenticated media.

##### 5. Bounded read-ahead и cache lifecycle

1. Read-ahead строится вокруг текущего playback cursor и ограниченного future window, а не всего torrent/live window.
2. Размер read-ahead может адаптироваться к measured bitrate, consumer pressure и rebuffer evidence только внутри safety bounds.
3. Consumed, obsolete после seek или вышедшие из active window pieces должны освобождаться/deprioritize, чтобы старые priorities не конкурировали с текущим playback.
4. Cache/piece retention имеет явные memory/storage bounds и не материализует большие media bodies целиком в heap.
5. Startup read-ahead и steady-state read-ahead рассматриваются отдельно: быстрый first media не должен достигаться ценой постоянного чрезмерного network/memory pressure.

##### 6. Endgame и rebuffer recovery

1. Duplicate request разрешается только bounded для playback-critical block/piece после normal request timeout или в явном endgame/recovery состоянии.
2. После первого accepted/authenticated результата остальные duplicate requests отменяются/снимаются с ownership.
3. Recovery сначала использует requeue/reprioritize, productive-peer replacement и bounded discovery/reannounce, а не увеличение Media3 buffer.
4. Rebuffer должен иметь измеримую причину: no qualified peers, no useful window, stalled requests, missing authenticated piece, TS discontinuity или downstream player stall.
5. Никакой recovery loop не может бесконечно повторять один и тот же endpoint/request без backoff и generation ownership.

##### 7. Local streaming boundary

1. Local HTTP/media producer boundary остаётся отделённым от peer discovery и protocol state.
2. HTTP Range/seek semantics должны корректно перестраивать playback demand без сохранения старого priority ownership.
3. Media3 получает только media, прошедшее protocol/authentication/resync boundary; Player не компенсирует отсутствие swarm progress.
4. READY/first-frame считается downstream acceptance, а не доказательством здоровья peer acquisition во всех следующих sessions.

#### P0 acceptance metrics

Каждый field build должен позволять сравнить не только общий success/fail, но и конверсию между стадиями:

- discovered candidates и source diversity;
- unique transport endpoints и repeated endpoint races;
- `discovered -> connected` conversion;
- `connected -> handshaked/qualified` conversion;
- qualified -> useful-window -> producing conversion;
- time to first connected / qualified / producing / media / first frame;
- completed/authenticated piece throughput и critical request timeout/requeue count;
- startup success rate на фиксированной channel matrix;
- rebuffer count, суммарное rebuffer time и failure stage;
- same-device A/B против Televizo + Ace Stream Engine для тех же каналов и сети.

CI подтверждает regression safety, но финальный P0 acceptance — ручной TV Box/network field proof на exact integrated build.

### EPG и archive

Функциональный scope ведётся в Issue #47; oversized XMLTV field acceptance — в Issue #317. Источник должен пройти последовательность:

`source -> decode/classify -> XMLTV parse -> channel matching -> programme state -> Player/EPG UI`

Oversized XMLTV ingestion должен оставаться streaming/bounded. Если источник уже проходит parse, но программа отсутствует у конкретных каналов, следующий defect boundary — matching по реальным `tvg-id`/XMLTV channel id/`display-name`/explicit aliases из того же источника. Не вводить глобальный fuzzy matching или эвристику по истории EPG без воспроизводимого source evidence.

Persistent EPG cache реализуется отдельно по [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md): bounded storage, conditional HTTP, atomic writes, stale policy и безопасные cache keys. Parser/source compatibility меняется только по воспроизводимому входному evidence.

Archive/catch-up подчиняется [`CATCHUP_ARCHIVE_CONTRACT.md`](CATCHUP_ARCHIVE_CONTRACT.md): приложение не должно фабриковать archive capability для live-only источников.

### TV-first Home / Player

Долговременный UX scope ведётся в Issue #210. Home и Player должны оставаться одной адаптивной TV-first design family, сохранять видимый focus и не создавать лишние playback/P2P runtimes.

Dashboard/fullscreen/overlay изменения обязаны сохранять ownership одной playback session. EPG-панели, channel rails, search и transient controls не должны запускать канал только из-за перемещения focus.

### Catalog / Favorites

Каноническая иерархия и единое избранное ведутся в Issue #45:

`source/catalog -> subcatalog -> playlist/list -> group/subgroup -> channel`

Агрегированные представления не должны терять source provenance или физически дублировать исходные данные без необходимости.

### Help / navigation

Контекстные подсказки и пользовательская помощь ведутся в Issue #43. Непреднамеренный playback churn при browsing остаётся отдельной проверяемой задачей #233.

## Open-source integration policy

Перед проектированием нового network/torrent primitive необходимо проверить зрелые open-source реализации и определить, существует ли уже проверенный подход к той же проблеме.

Основные reference projects для P2P/streaming:

- **libtorrent** — tracker retry/backoff, peer/piece selection, sequential + rarest-first стратегии, bounded endgame/recovery;
- **TorrServer / anacrolix torrent stack** — целостный torrent lifecycle, DHT/uTP/PEX, streaming read-ahead, priority/cache lifecycle;
- **WebTorrent / torrent-discovery / WebTorrent Desktop** — независимые discovery sources, общий peer pool, stream-demand-driven selection и seek lifecycle;
- **Stremio stream-server / stremio-native** — playback generation ownership, intent-driven priority windows, bounded emergency reannounce и строгий backend/player boundary;
- **RapidBay, OwnTV, PlayTorrio** — применимые local proxy/player/stream orchestration patterns после проверки конкретного defect boundary;
- **AceStream/RePEX references** — learned/PEX peer reuse и совместимость поведения, где это подтверждается доступным публичным protocol/reference evidence.

Правила интеграции:

1. OSS reference выбирается под измеренный локальный defect, а не ради добавления возможностей.
2. Перед прямым reuse проверяется лицензия и совместимость с лицензией проекта.
3. GPL-код не копируется в несовместимый кодовый контур; такие проекты используются как behavioral/architecture reference с clean-room реализацией собственного кода.
4. Не импортировать целую torrent engine/library, если для закрытия defect достаточно существующего внутреннего boundary и небольшого адаптера/policy change.
5. Не смешивать чужой generic BitTorrent behavior с Ace Live protocol assumptions без wire/field evidence.
6. Каждая адаптация получает deterministic regression tests и сохраняет существующие hard bounds/cancellation/ownership contracts.
7. Если mature OSS уже решает retry/backoff, candidate diversity, request scheduling, range streaming или cache eviction, сначала сравнить его invariant с нашим текущим кодом, затем исправлять конкретное расхождение.

## Постоянные release gates

Для production change:

1. начать от актуального `main`;
2. один defect/feature boundary — одна тематическая ветка и PR;
3. до написания сетевого/protocol кода проверить field evidence и релевантные mature OSS/reference implementations;
4. добавить deterministic regression test, если поведение допускает воспроизводимую проверку;
5. выполнить релевантные unit/lint/guard/build проверки;
6. проверить CI именно на exact PR head;
7. review findings исправлять до merge, не расширяя scope без необходимости;
8. successful exact-head PR squash-merge в `main` без накопления готовых временных веток;
9. устаревший/дублирующий PR закрывается после появления корректной replacement-ветки;
10. после merge временная branch должна быть удалена, если доступный GitHub workflow/tooling поддерживает branch deletion;
11. проверить integrated `main` после merge;
12. device/network-dependent behavior считать принятым только после field validation exact integrated build.

CI является regression gate, но не заменяет TV Box/network field proof для P2P, реальных EPG источников, focus/layout и hardware playback compatibility.

## Архитектурная дисциплина

- P2P, EPG source/parser/cache, Player UI и catalog changes не смешивать в один PR.
- Перед сетевым/protocol изменением отделять source-derived факт от предположения; код писать только для подтверждённого boundary.
- Candidate acquisition и candidate qualification остаются разными слоями; endpoint из tracker/DHT/PEX не считается usable peer до protocol qualification.
- Shared transport failure memory должна применяться одинаково ко всем candidate sources.
- Не материализовать большие playlist/EPG/media bodies целиком в heap в production path.
- Absolute safety bounds менять только по измеренному evidence.
- Stale generation/session не может получить ownership после более новой playback generation.
- Логи не должны содержать credential-bearing URL, токены, content IDs или полный пользовательский payload без явной безопасной необходимости.
- Compatibility fallback не должен скрывать первичный failure stage.
- Persistent DHT state хранит только verified routing contacts; peer reputation всегда scoped точным swarm key и имеет bounded TTL.
- Disk I/O persistent P2P state не должен выполняться под socket/pool/refill ownership lock.
- Scheduler/cache/recovery не должны компенсировать отсутствие qualified/productive peers искусственным увеличением буферов.

## Документация и evidence

`docs/` содержит повторяемые контракты и инструкции. Exact SHA, активная ветка, результаты единичного field run и временный handoff должны жить в соответствующем GitHub Issue/PR/Actions artifact. При изменении устойчивого пользовательского или архитектурного контракта профильные документы обновляются в том же тематическом PR; изменение не считается завершённым, если код и документация описывают разное поведение.
