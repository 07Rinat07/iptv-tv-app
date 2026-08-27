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

`discovered -> connected -> handshaked/qualified -> producing -> TS -> Media3 tracks -> first frame/audio`

Discovery/connect, protocol/producer и P2P-to-Media3 boundary — отдельные defect boundaries. Не расширять глобальные startup timeout, request depth, peer caps, buffers или heap без измеренного evidence конкретного лимита.

### EPG и archive

Функциональный scope ведётся в Issue #47. Источник должен пройти последовательность:

`source -> decode/classify -> XMLTV parse -> channel matching -> programme state -> Player/EPG UI`

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

## Документация и evidence

`docs/` содержит повторяемые контракты и инструкции. Exact SHA, активная ветка, результаты единичного field run и временный handoff должны жить в соответствующем GitHub Issue/PR/Actions artifact. При изменении устойчивого пользовательского или архитектурного контракта обновляются профильные документы, а не создаётся новый датированный status-файл.