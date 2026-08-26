# Текущий handoff разработки

_Актуализирован: 26 августа 2026 после merge PR #249 и #250 и перед финальной проверкой PR #251._

Этот документ — точка входа для следующей сессии разработки. Перед любыми изменениями сначала сверить фактические `main`, open PR, branch head и CI в GitHub: сохранённые SHA фиксируют состояние на момент handoff, но не заменяют проверку текущего репозитория.

## Что читать сначала

1. `docs/CURRENT_DEVELOPMENT_HANDOFF.md` — ближайший порядок действий.
2. `docs/PROJECT_STATUS_AND_ROADMAP.md` — канонический статус и release gates.
3. `docs/ROADMAP.md` — технический порядок разработки.
4. `docs/EPG_DISK_CACHE_PLAN.md` — следующий отдельный XMLTV cache increment.
5. `docs/FIELD_VALIDATION_2026-08-25.md` — исходный recovery field evidence.
6. Issue #232 — P0 Torrent TV peer discovery/handshake blocker.

Исторические датированные документы используются как evidence, но не переопределяют этот handoff и `PROJECT_STATUS_AND_ROADMAP.md`.

## Зафиксированный integration baseline

На момент этого handoff в `main` уже последовательно вошли:

- PR #249 `fix(p2p): diversify DHT bootstrap roots after field retest` — squash commit `f2cb6e02c8725e49032038942521232438e18f38`;
- PR #250 `fix(epg): recover field matching and invalid programme handling` — squash commit `1d4c86569537a525661525dfb949c0aa6754b284`.

PR #251 `feat(epg): add timezone correction and low-load refresh policy` должен попасть в `main` только после чистого переноса поверх указанного baseline и зелёного exact-head Database Unit CI + Player Refactor Guard + Android CI. В следующей сессии первым действием проверить, был ли #251 фактически merged; не предполагать наличие его кода в APK только по этому документу.

## Что уже содержит P2P baseline

PR #249 расширяет bounded production bootstrap diversity дополнительными независимыми Mainline DHT roots. Он не увеличивает:

- количество startup DHT probe rounds;
- 7-секундный budget каждого probe;
- query caps/branching;
- player buffers;
- global content preparation timeout.

Его pre-merge exact-head Android CI #1043 и Player Refactor Guard #179 были зелёными. Реальным доказательством улучшения остаётся новый TV Box run: нужно сравнить стадии `discovered -> connected -> handshaked/qualified -> producing` с предыдущим failure signature.

## Что уже содержит EPG recovery baseline

PR #250:

- отбрасывает zero/negative-duration programme entries;
- отбрасывает известные schedule placeholders;
- расширяет консервативную нормализацию названий каналов для resolution/status decorations;
- сохраняет fail-closed поведение при неоднозначном match;
- содержит regression tests для matching/display policy.

Его Database Unit CI #160, Player Refactor Guard #180 и Android CI #1044 были зелёными до merge.

## Scope PR #251, если он присутствует в тестируемом `main`

- device-local timezone остаётся default display behavior;
- ручная коррекция EPG ограничена `-12h...+12h` с шагом 30 минут;
- correction применяется на внешнем `PlaylistRepository` boundary, чтобы Guide, Player now/next и RecordingSchedule использовали одну timeline;
- EPG cadence ограничен 6/12/24h, default 24h;
- stale-on-start выполняется только если данные действительно устарели;
- старый агрессивный 30-минутный periodic EPG refresh удалён;
- XMLTV refresh выполняется последовательно и serialized;
- системные virtual playlists не считаются реальными EPG sources;
- freshness gate внутри worker предотвращает двойной sequential download при race periodic/startup;
- будущая явная команда `Обновить EPG` использует forced refresh.

Persistent XMLTV disk cache и Player programme UI не относятся к #251.

## Когда получены новые diagnostics и скриншоты

Новые diagnostics/logs/скриншоты — основной field evidence. Сначала установить, какая точная сборка тестировалась: commit/build metadata должны соответствовать фактическому `main`. Если build SHA не подтверждён, не приписывать тесту изменения из draft/open PR.

### Torrent TV

Для каждого реально протестированного P2P запуска восстановить:

`discovered -> connected -> handshaked/qualified -> producing -> first media/player ready`

Зафиксировать первую отсутствующую стадию и время до ключевых стадий, если поля есть в diagnostics.

Порядок решения #232:

1. tracker/DHT peer-set acquisition, bootstrap/routing diversity и причины failed KRPC queries;
2. candidate retention/deduplication/backoff;
3. TCP connect lifecycle;
4. BitTorrent/Ace Live handshake qualification;
5. metadata/live-window;
6. request selection/sent -> chunk ingress -> piece completion/authentication;
7. TS resync/output;
8. Player boundary только после доказанного upstream media production.

Если `discovered` слабый — не менять Player/request scheduler/media buffer. Если peers найдены, но `connected=0` — идти в TCP. Если TCP есть, но `handshaked=0` — handshake. Если qualified peer есть, но `producing=0` — producer stages.

Запрещено маскировать protocol gap увеличением global timeout, startup failure bound, player buffer, request depth, peer caps или другими абсолютными bounds без конкретного evidence.

### EPG

Для каждого проблемного канала классифицировать причину:

1. EPG source не найден/не загрузился;
2. source загрузился, channel не matched;
3. match есть, programmes отсутствуют/invalid/placeholders;
4. programme есть, но время неверно;
5. данные корректны, но UI их не показывает.

Проверять `tvg-id`, XMLTV channel id, display-name, HD/FHD/UHD/4K, `(720p)/(1080p)`, `[Geo-blocked]`, `[Not 24/7]` и подобные decorations. Не расширять fuzzy matching так, чтобы похожий канал получал чужую программу; ambiguity остаётся fail-closed.

XMLTV timestamp с явным timezone offset трактуется как absolute instant. Отображение по умолчанию использует timezone TV Box. Manual correction — отдельная поправка для некорректного feed и должна одинаково влиять на Guide, Player и RecordingSchedule.

### Player/UI screenshots

Проверить минимум:

- видео и выбранный канал остаются главным visual focus;
- D-pad focus видим и не запускает playback только от перемещения;
- nearby/quick channels должны получить logo + компактный `Сейчас/Далее`, когда EPG существует;
- в Player нужна отдельная action `Программа`;
- programme panel должен показывать передачи выбранного канала, время, current progress и description при наличии;
- 1280x720 layout не обрезается;
- dashboard/fullscreen используют одну playback session и один P2P runtime.

### Память и фоновые задачи

На большом field run проверить:

- OOM/process death/low-memory;
- устойчивый рост heap после переключений и EPG;
- параллельные XMLTV refresh;
- лишнее повторное скачивание EPG source;
- large-catalog hot flows;
- stale P2P runtimes после переключений.

Не повышать heap как исправление.

## Следующий отдельный EPG infrastructure increment

После подтверждённого integration baseline следующий инфраструктурный PR:

`feat/epg-disk-cache-r1`

Полный контракт: `docs/EPG_DISK_CACHE_PLAN.md`.

Invariants:

- L1 — маленький parsed memory cache;
- L2 — app-private raw XMLTV snapshots;
- cold process restart внутри refresh interval не вызывает полный повторный download;
- conditional HTTP `ETag` / `Last-Modified` / `304`;
- snapshot <=64 MiB;
- total disk budget <=128 MiB;
- <=4 entries;
- deterministic LRU;
- hard stale age <=96h;
- atomic temp-write -> size/checksum validation -> rename;
- corrupt/truncated snapshot удаляется без crash;
- URL credentials/tokens не попадают в filename/diagnostics;
- disk read проходит через существующий bounded streaming parser и heap guards;
- low storage означает skip disk write, а не failure EPG;
- concurrent same-source callers не создают несколько downloads.

Disk cache остаётся отдельным PR, а не расширением time/refresh или Player UI.

## Порядок EPG/Player после cache boundary

1. EPG settings UI: manual offset, 6/12/24h, refresh-on-start-if-stale, manual `Обновить`;
2. отдельная `Программа` action в Player;
3. список передач выбранного канала с `Сейчас`, `Далее`, временем, progress и description;
4. channel logo + `Сейчас/Далее` у nearby/quick channels;
5. TV Box validation: timezone, matching, refresh, disk hit, RAM, D-pad;
6. archive/catch-up polish после подтверждения базовых EPG flows.

## Merge и branch discipline

- каждый новый head требует собственного exact-head CI; старый зелёный run не доказывает новый SHA;
- P2P, EPG data/cache и Player UI не смешивать в один PR;
- stacked EPG history после squash-merge родителей необходимо очищать/переносить поверх актуального `main` до merge;
- merge только зелёный exact head;
- после merge проверить CI самого `main`;
- временные branches удалять только после проверки, что нужные commits присутствуют в `main`.

## Следующая field-сборка

После финального integration merge:

1. `git fetch --prune origin`;
2. перейти на `main` и выполнить fast-forward update;
3. проверить последний commit;
4. собрать свежий APK без локальных незакоммиченных изменений;
5. установить именно эту сборку на TV Box;
6. обычным способом проверить IPTV, несколько Torrent TV каналов, EPG, переключения, Player overlays и restart приложения;
7. экспортировать diagnostics и сохранить скриншоты проблем/успешных состояний;
8. следующую разработку начинать с этого документа и анализа нового field evidence.

## Минимальный результат следующего разбора

Нужно явно ответить:

1. Какая точная сборка/commit тестировалась?
2. Где первая отсутствующая стадия Torrent TV в проблемных сессиях?
3. Почему конкретные EPG-каналы не показывают правильную программу: source, matching, validity, time или UI?
4. Какой один focused increment следует из evidence и какие tests являются его acceptance gate?

После этого продолжать соответствующую ветку без повторного восстановления всей истории проекта.
