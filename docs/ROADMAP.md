# План проекта

`ROADMAP.md` хранит устойчивые технические приоритеты и release gates. Текущий SHA, активные ветки, CI и field evidence проверяются непосредственно в GitHub Issues, Pull Requests и Actions и не копируются сюда как быстро устаревающий snapshot.

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

Discovery/connect, protocol/producer и P2P-to-Media3 boundary — отдельные defect boundaries. Не расширять глобальные startup timeout, request depth, peer caps, buffers или heap без измеренного evidence конкретного лимита.

Устойчивый контракт discovery-state описан в [`P2P_DISCOVERY_STATE.md`](P2P_DISCOVERY_STATE.md). Архитектурное направление:

1. сохранять bounded verified DHT routing contacts между runtime/process restarts;
2. сохранять короткую same-swarm peer reputation по `swarm + endpoint`;
3. считать tracker, DHT и `ut_pex` равноправными источниками одного bounded candidate pool, а не признаком успешного startup;
4. сначала использовать уже известные/PEX/recent same-swarm candidates, не ставя новый tracker/DHT walk обязательным gate перед ними;
5. выполнять bounded tracker+DHT acquisition только для оставшегося qualification demand и продолжать diversity acquisition до достаточного qualified/productive peer set;
6. сохранять общий `maxStartsPerCycle`/hard peer cap на весь refill cycle независимо от того, пришёл кандидат из памяти, PEX, tracker или DHT;
7. квалифицировать небольшой набор независимых candidates параллельно в пределах hard peer cap;
8. измерять first qualified peer, first producer, first media и first frame вместо оправдания 20–60-секундного ожидания общим timeout.

Следующие архитектурные проверки P2P выполняются по реальным field logs и по зрелым open-source BitTorrent реализациям. В качестве reference behavior используются прежде всего AceStream/RePEX для learned peer reuse, TorrServer/anacrolix для цельной DHT/uTP/PEX acquisition subsystem и WebTorrent/torrent-discovery для независимых discovery sources. Код не копируется вслепую: переносится только подтверждённый protocol/architecture behavior с regression tests и сохранением Android/Ace Live ownership contracts.

Persistent routing/reputation — только optimization. Повреждение/отсутствие cache не должно ломать bootstrap, tracker discovery или playback correctness.

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

## Постоянные release gates

Для production change:

1. начать от актуального `main`;
2. один defect/feature boundary — одна тематическая ветка и PR;
3. до написания кода проверить field evidence и релевантные protocol/reference implementations, если boundary зависит от сетевого/форматного поведения;
4. добавить deterministic regression test, если поведение допускает воспроизводимую проверку;
5. выполнить релевантные unit/lint/guard/build проверки;
6. проверить CI именно на exact PR head;
7. review findings исправлять до merge, не расширяя scope без необходимости;
8. squash merge только после прохождения требуемых gates;
9. проверить integrated `main` после merge;
10. device/network-dependent behavior считать принятым только после field validation exact integrated build.

CI является regression gate, но не заменяет TV Box/network field proof для P2P, реальных EPG источников, focus/layout и hardware playback compatibility.

## Архитектурная дисциплина

- P2P, EPG source/parser/cache, Player UI и catalog changes не смешивать в один PR.
- Перед сетевым/protocol изменением отделять source-derived факт от предположения; код писать только для подтверждённого boundary.
- Не материализовать большие playlist/EPG bodies целиком в heap в production path.
- Absolute safety bounds менять только по измеренному evidence.
- Stale generation/session не может получить ownership после более новой playback generation.
- Логи не должны содержать credential-bearing URL, токены, content IDs или полный пользовательский payload без явной безопасной необходимости.
- Compatibility fallback не должен скрывать первичный failure stage.
- Persistent DHT state хранит только verified routing contacts; peer reputation всегда scoped точным swarm key и имеет bounded TTL.
- Disk I/O persistent P2P state не должен выполняться под socket/pool/refill ownership lock.
- Candidate acquisition и candidate qualification остаются разными слоями; endpoint из tracker/DHT/PEX не считается usable peer до protocol qualification.

## Документация и evidence

`docs/` содержит повторяемые контракты и инструкции. Exact SHA, активная ветка, результаты единичного field run и временный handoff должны жить в соответствующем GitHub Issue/PR/Actions artifact. При изменении устойчивого пользовательского или архитектурного контракта профильные документы обновляются в том же тематическом PR; изменение не считается завершённым, если код и документация описывают разное поведение.
