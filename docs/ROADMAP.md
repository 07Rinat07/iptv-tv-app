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
3. считать tracker/DHT/PEX endpoints кандидатами, а не признаком успешного startup;
4. использовать уже известные и PEX-learned candidates до нового сетевого discovery pass, не заставляя их ждать tracker/DHT latency;
5. продолжать bounded tracker+DHT acquisition после fast-path старта до достаточного qualified/productive peer set и необходимой source diversity;
6. делить единый `maxStartsPerCycle` между learned и newly discovered candidates, не расширяя hard peer/socket cap;
7. квалифицировать небольшой набор независимых candidates параллельно в пределах hard peer cap;
8. измерять first qualified peer, first producer, first media и first frame вместо оправдания 20–60-секундного ожидания общим timeout.

Следующие P2P boundaries после learned-candidate fast path:

- coalesced event-driven refill wakeup на новый PEX evidence и освобождение qualification capacity вместо обязательного ожидания periodic refresh;
- bounded same-swarm warm peer cache / RePEX-подобное повторное использование недавно подтверждённых peers между соседними runtime;
- сравнительная проверка peer acquisition против зрелых open-source реализаций (AceStream/RePEX, TorrServer/anacrolix, WebTorrent) по фактическим механизмам, а не по увеличению timeout;
- field A/B на одном TV Box/network с одинаковыми Torrent TV каналами и полным diagnostic ladder.

Persistent routing/reputation/peer cache — только optimization. Повреждение/отсутствие cache не должно ломать bootstrap, tracker discovery или playback correctness. Learned peer никогда не становится permanent allow-list entry: handshake/media evidence, bounded TTL и failure backoff остаются обязательными.

### EPG и archive

Функциональный scope ведётся в Issue #47. Источник должен пройти последовательность:

`source -> decode/classify -> XMLTV parse -> channel matching -> programme state -> Player/EPG UI`

После устранения oversized XMLTV ingestion текущий field boundary для источников, которые загружаются без parser failure, — точное сопоставление playlist metadata (`tvg-id`, canonical/display name, provider aliases) с XMLTV channel metadata. Matcher меняется только по фактическим playlist/XMLTV данным и regression fixtures; широкое fuzzy matching без контролируемых правил запрещено.

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
3. добавить deterministic regression test, если поведение допускает воспроизводимую проверку;
4. выполнить релевантные unit/lint/guard/build проверки;
5. проверить CI именно на exact PR head;
6. review findings исправлять до merge, не расширяя scope без необходимости;
7. squash merge только после прохождения требуемых gates;
8. проверить integrated `main` после merge;
9. device/network-dependent behavior считать принятым только после field validation exact integrated build.

CI является regression gate, но не заменяет TV Box/network field proof для P2P, реальных EPG источников, focus/layout и hardware playback compatibility.

## Архитектурная дисциплина

- P2P, EPG source/parser/cache, Player UI и catalog changes не смешивать в один PR.
- Не материализовать большие playlist/EPG bodies целиком в heap в production path.
- Absolute safety bounds менять только по измеренному evidence.
- Stale generation/session не может получить ownership после более новой playback generation.
- Логи не должны содержать credential-bearing URL, токены, content IDs или полный пользовательский payload без явной безопасной необходимости.
- Compatibility fallback не должен скрывать первичный failure stage.
- Persistent DHT state хранит только verified routing contacts; peer reputation всегда scoped точным swarm key и имеет bounded TTL.
- Learned/PEX candidate fast path не отменяет bounded tracker/DHT diversity acquisition и не расширяет `maxStartsPerCycle`/hard peer cap.
- Periodic, adaptive и event-driven refill не должны выполнять overlapping discovery/start cycles; orchestration обязана иметь единый serialized ownership boundary.
- Disk I/O persistent P2P state не должен выполняться под socket/pool/refill ownership lock.

## Документация и evidence

`docs/` содержит повторяемые контракты и инструкции. Exact SHA, активная ветка, результаты единичного field run и временный handoff должны жить в соответствующем GitHub Issue/PR/Actions artifact. При изменении устойчивого пользовательского или архитектурного контракта обновляются профильные документы, а не создаётся новый датированный status-файл.

Документация является частью Definition of Done: если PR меняет устойчивый архитектурный, протокольный, safety или пользовательский контракт, соответствующий `docs/` документ обновляется в том же тематическом PR. Документы не должны объявлять ещё не реализованный механизм завершённым: будущие stages явно помечаются как следующие boundaries.