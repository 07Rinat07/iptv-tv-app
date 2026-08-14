# План проекта

## Цель стабильной версии

Получить реально устанавливаемое Android TV / TV Box приложение, которое без внешних обязательных P2P-зависимостей воспроизводит обычные IPTV-потоки, BitTorrent и Torrent TV/Ace Live. Главный технический приоритет проекта — собственный автономный live-P2P runtime: он должен быстро находить полезные пиры, удерживать непрерывный запас media, переживать деградацию отдельных peers и давать плееру стабильный локальный поток. Media3 остаётся основным декодером, LibVLC — изолированным fallback только для подтверждённых container/demux/codec проблем.

Целевой ориентир P2P — не просто функциональная совместимость, а измеримое качество: быстрый zap здорового swarm, минимальный rebuffer, bounded recovery, отсутствие stale-session гонок и длительная стабильность на реальном TV Box. Внешний Ace Stream Engine не является runtime-стратегией, fallback или требованием проекта; сторонние приложения/движки используются только как поведенческий benchmark при A/B-проверке тех же каналов и устройств.

## Текущий срез — 14 августа 2026

Автономный Torrent TV маршрут работает без внешнего Ace Engine. Базовая цепочка уже включает public tracker/DHT discovery, TCP peer pool, Ace Live handshake/window/chunk scheduling, bounded recovery, MPEG-TS resync, sliding loopback output и Media3/LibVLC playback.

После PR #101 последовательно завершены дополнительные hardening/adaptive-инкременты:

- PR #102 — playback latency analyzer и точная разбивка `play_request → resolve → READY`;
- PR #103 — cancellable rapid-zap coalescing P2P запросов;
- PR #104 — прямой Ace startup продолжает peer progress внутри существующего soft-window без ослабления абсолютных bounds;
- PR #105 — глобально уникальные playback session IDs, request ownership, отменяемый retry и защита от `A → B → C → late retry A`;
- PR #106 — полный Torrent TV каталог всегда видим; динамическая P2P-доступность показывается как информационный last-known status и больше не скрывает каналы;
- PR #107 — adaptive prebuffer v1: throughput clock начинается с first-media, используется EWMA media delivery, усилен startup reserve без увеличения failure bounds;
- PR #108 — media-producing peer accounting: discovered endpoints отделены от connected/handshaked/producing peers, добавлены freshness и aggregate EWMA media rate; exact-head Android CI #495 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

Полевой прогон на ARM/TV Box изменил приоритет работ. Одни и те же публичные Torrent TV источники способны быстро находить tracker/DHT peers, но найденные endpoints не всегда превращаются в устойчивый media-producing pool. В логе встречались `peers=4..7` одновременно с итоговым no-peer/60-second timeout, а несколько успешно resolved потоков затем проводили около 66 секунд между `player_start` и `player_ready`. Это указало не на один общий codec-дефект, а на незавершённую связку peer usefulness → throughput → prebuffer → loopback consumption → player buffering.

V1 уже исправил конкретный дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. Базовый V2 из PR #108 также уже в `main` и перестал трактовать raw discovery count как качество swarm. Текущий инкремент **V2b peer quality** добавляет недостающие requestability-стадии: `windowUseful` вычисляется относительно authoritative `nextNeededPiece()`, `unchoked` берётся из фактического peer-wire state, а fresh media считается producing только пока peer остаётся `windowUseful + unchoked`. Requestability пересчитывается при live-window/cursor update и recovery advance; startup/stall bounds не увеличиваются.

Следующий порядок P2P-работ:

1. ✅ **Adaptive prebuffer v1 (PR #107)** — first-media throughput clock, EWMA и безопасный startup reserve без увеличения 30/60-second failure bounds.
2. 🚧 **Media-producing peer accounting** — базовый `discovered / connected / handshaked / producing + freshness/rate` слит в PR #108; текущий V2b добавляет `windowUseful / unchoked`, затем persistent peer-quality diagnostics и post-output semantics.
3. **Adaptive live scheduler** — `LiveBufferController`, low/target/high watermarks, buffer-pressure feedback, adaptive request depth/in-flight и быстрый replacement деградировавших peers.
4. **Loopback/player boundary** — измерять first HTTP read, producer/consumer rate и Media3 `BUFFERING/READY/first-frame`; выделить отдельный P2P LoadControl вместо generic IPTV assumptions.
5. **Discontinuity/TS hardening** — PAT/PMT/random-access/IDR gating и корректное decoder recovery после подтверждённого live-window jump.
6. **Acceptance** — фиксированная матрица Torrent TV, 20 rapid switches, weak network, peer loss, 2h/8h ARM soak без внешнего Ace Engine.

Актуальные доказательства и критерии находятся в [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md), детали runtime — в [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md), целевая adaptive-архитектура — в [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md), а пошаговый clean-room план Ace Live — в [`ACE_LIVE_IMPLEMENTATION_PLAN.md`](ACE_LIVE_IMPLEMENTATION_PLAN.md).

## Завершено

- сканирование и поиск публичных плейлистов;
- импорт URL, файла, текста и провайдеров;
- редактор, избранное, история и базовый EPG;
- стартовый экран просмотра с готовыми списками;
- Media3 и LibVLC fallback с автоматическим переключением для decoder/container/demux проблем;
- legacy Ace Stream Android Service/AIDL compatibility integration как изолированный compatibility-код, не как Torrent TV fallback;
- встроенный libtorrent backend для `magnet:`, infohash и `.torrent`;
- встроенный Ace Live runtime для Torrent TV `content_id`/live infohash без автоматического внешнего fallback;
- прямой peer discovery/handshake, live-window, chunk reassembly и локальная MPEG-TS выдача;
- пересоздание P2P-сессии при retry, ограниченный stall watchdog и корректная остановка при переключении;
- PR #98: direct Ace Live startup и metadata resolution выполняются конкурентно с first-success semantics; отмена проигравших путей и regression coverage прошли real Torrent TV smoke без внешнего Ace Engine;
- PR #99: Media3/MediaCodec stack переиспользуется при zapping обычных IPTV-каналов вместо полного `release()/rebuild` на каждый session change;
- PR #100: XMLTV читается bounded streaming parser, тяжёлые EPG loads сериализованы, malformed/low-memory failures имеют ограниченный negative-cache/fail-safe;
- PR #101: bounded Ace Live discovery согласован с no-connected-peer guard, первый DHT peer возвращается без ожидания полного обхода, диагностика переживает перезапуск процесса, oversized EPG получает длительный backoff, а готовый Torrent TV каталог содержит 279 уникальных Ace Stream каналов;
- PR #102: добавлен analyzer фактической playback latency и получен baseline rapid-zap/resolve/READY;
- PR #103: P2P rapid-zap coalescing отменяет промежуточные запросы, не затрагивая direct HTTP;
- PR #104: direct Ace progress сохраняется внутри существующего 8-second soft-window при конкурентном metadata resolve;
- PR #105: playback ownership/stale-session hardening исключает reuse sessionId, zombie retry и поздний takeover старого канала;
- PR #106: Torrent TV каталог не фильтруется по временной доступности; UI показывает last-known P2P status без массового peer probing;
- PR #107: adaptive prebuffer v1 использует first-media throughput clock, EWMA и усиленный startup reserve; exact-head CI и real Torrent TV smoke прошли без внешнего Ace Engine;
- PR #108: `AceLivePeerProductionTracker` отделяет discovery от реальной media production, учитывает connected/handshaked lifecycle, freshness и aggregate delivery rate; exact-head CI #495 полностью зелёный;
- TV-интерфейс, пульт, мышь, тачпад и полноэкранный режим;
- группы, подгруппы, восстановление фокуса и постраничная прокрутка в основных разделах;
- Editor использует общий `TvScrollableLazyColumn`: TV-кнопки начала/Page Up/Page Down, PageUp/PageDown и ChannelUp/ChannelDown, mouse wheel/touchpad и scrollbar без изменения операций редактирования;
- меню «Разделы» при открытии фокусирует первый доступный маршрут, удерживает TV-focus видимым и возвращает фокус на кнопку «Разделы» после закрытия/перехода; диалог выхода по умолчанию фокусирует безопасную «Отмена»;
- общий `tvBringIntoViewOnFocus()` поддерживает focus-follow без изменения стандартного вида кнопок; `tvFocusOutline()` использует тот же механизм; standalone actions подключены на About и History, верхние вкладки Diagnostics, основная action-группа Favorites и action-группы Downloads также удерживаются в видимой области; Settings selection/section controls, основные source action-группы Importer, action controls Network Test и standalone action-группа Playlists также используют focus-follow; аппаратная приёмка на BlueStacks/TV Box остаётся обязательной;
- D-pad smoke-тест меню «Разделы» проверяет реальное перемещение focus Scanner → Importer и активацию DPAD_CENTER; Android CI компилирует `:app:assembleDebugAndroidTest`, чтобы instrumentation-тесты не оставались непроверенным исходным кодом;
- адаптивный Media3 буфер для слабых устройств;
- автоматические lint, unit, debug и release сборки;
- очистка документации, workflow и бинарных артефактов.

## Актуальный оптимизированный порядок выполнения

Этот порядок имеет приоритет над исторической нумерацией технических блоков ниже. Подтверждённый crash/ANR/memory regression всегда получает приоритет над функциональным roadmap.

0. **Issue #40 — regression baseline TV navigation.** Кодовая база D-pad/mouse уже стандартизирована; реальные BlueStacks/TV Box проверки идут параллельно. Подтверждённая ручная регрессия получает отдельный минимальный hotfix PR и не ждёт конца roadmap.
1. **Playback safety acceptance — EPG OOM.** Код PR #100 слит: streaming XMLTV, bounded memory/cache, negative-cache, serialized load и low-memory fail-safe. Ручные логи после фикса не показывают возврата прежнего OOM в просмотренном окне; hardware regression продолжает входить в release gate.
2. **Master #44 — автономный P2P/Ace Live engine.** Это текущий главный приоритет. Ownership/rapid-zap базовые гонки закрыты PR #103/#105; adaptive streaming core уже прошёл V1 (PR #107) и базовый producing accounting (PR #108). Текущий порядок внутри #44: `windowUseful/unchoked + persistent peer diagnostics → LiveBufferController/scheduler feedback → player/TS boundary → weak-network/soak acceptance`.
3. **Issue #45 — canonical catalog hierarchy + unified Favorites.** Продолжать после стабилизации критического playback path; PR #106 уже обеспечивает полный Torrent TV выбор без временного availability-filter.
4. **Issue #47 — EPG / Now-Next / real archive.** Полноценный ingestion/cache/matching/catch-up redesign строить поверх стабильной channel identity из #45.
5. **Issue #46 — Player UX redesign.** Строить fullscreen/overlay/channel selector/Now-Next/Archive/P2P controls поверх уже готовых Catalog + P2P + EPG contracts.
6. **Issue #43 — contextual Help + built-in Help + docs baseline.** Завершать после стабилизации основных экранов, чтобы не переписывать подсказки после Catalog/EPG/Player изменений.
7. **Master #44 release gate.** Hardware/playback hardening, автономная P2P acceptance, D-pad-only/mouse-only sessions, weak network, 2h/8h soak, signed release и финальная синхронизация docs. Только после этого закрывать #44 и объявлять stable.

## Этап 1: canonical catalog hierarchy и provenance (#45)

Целевая структура:

`Source/Catalog -> Subcatalog -> Playlist/List -> Group/Subgroup -> Channel`.

1. ✅ Введены в `core/model` стабильные `CatalogNodeId`, `CatalogNodeKind`, `CatalogProvenance` и parent/order contract без зависимости от Room auto-generated id.
2. ✅ Общий `ChannelStableIdentity` синхронизирует логическую identity канала между canonical catalog и существующим global Favorites.
3. ✅ Persist provenance: `catalogOrigin` сохраняется в Room/domain; provider/local источники, Ready Catalog и Scanner imports получают однозначный origin, который попадает в canonical tree.
4. Построить navigation skeleton с predictable Back, breadcrumb-equivalent context и focus restore.
5. Перевести Favorites на единый агрегированный слой, сохраняя исходный playlist/group/channel provenance.
6. Добавить dedup и source variants так, чтобы повторный импорт не плодил безымянные копии.
7. Добавить virtual views: All channels, Favorites, Recent/History, позднее Now/Next/EPG/archive/P2P filters.
8. Проверить lazy rendering, кэш подготовленной структуры и non-blocking rebuild больших наборов.

Первый contract-инкремент не менял Room/UI/Player/EPG. Следующий provenance-инкремент добавляет безопасное хранение происхождения и консервативную классификацию однозначных provider/local источников; Scanner search/query semantics, Player и EPG при этом не меняются.

## Этап 2: встроенный P2P engine и Ace transport

Внешний Ace Stream Engine не является целевым backend, обязательной зависимостью или fallback для Torrent TV. В APK развиваются собственные ordinary BitTorrent и Ace Live backends. Текущий главный приоритет — довести автономный Ace Live streaming path до более быстрого и устойчивого поведения на реальном TV Box за счёт peer selection, scheduling, adaptive buffering и восстановления, а не за счёт увеличения таймаутов.

1. ✅ Добавлен модуль `core:p2p` на базе libtorrent/libtorrent4j.
2. ✅ Поддержаны `magnet:`, infohash, локальный `.torrent` и HTTP(S) URL на `.torrent`.
3. ✅ DHT, trackers и PeX запускаются внутри приложения.
4. ✅ Для обычного BitTorrent выбирается медиафайл и настраиваются streaming priorities.
5. ✅ Реализованы read-ahead/seek для ordinary BitTorrent и отдельный live-window scheduler для Ace Live.
6. ✅ Локальный HTTP Range/stream endpoint отдаёт данные Media3/LibVLC.
7. ✅ `P2pEngineRouter` оставляет embedded backend основным; Torrent TV `content_id` и live infohash не падают автоматически во внешний Ace Engine.
8. ✅ Отдельная модель Ace Live не смешивает `content_id`, live infohash и BitTorrent BTIH.
9. ✅ PR #98 сократил serial startup wait: direct swarm и metadata resolution теперь гоняются first-success, проигравшие операции отменяются; regression тест и real Torrent TV smoke прошли перед merge.
10. ✅ PR #101: bounded startup discovery, exact-head CI, ARM-логи, persistent diagnostics и готовый каталог из 279 Ace Stream каналов завершены.
11. ✅ PR #103/#105: rapid-zap и playback ownership защищены от промежуточных/zombie requests.
12. ✅ PR #106: availability больше не удаляет Torrent TV канал из пользовательского выбора.
13. ✅ PR #107: adaptive prebuffer v1 — first-media throughput clock, EWMA и более безопасный startup reserve.
14. ✅ PR #108: базовый media-producing peer accounting — connected/handshaked/producing, freshness и aggregate delivery snapshot.
15. 🚧 V2b peer quality — `windowUseful/unchoked`, authoritative cursor usefulness и подготовка structured persistent diagnostics перед scheduler feedback.

### Детализация Ace transport / torrent-TV

1. Не считать `content_id` эквивалентом BitTorrent infohash. Явный `acestream:?infohash=...` и ordinary BTIH маршрутизируются только по подтверждённому transport contract; чистый `acestream://content_id` остаётся Ace transport identity.
2. Хранить раздельно `content_id`, live swarm identity, `transport_file_data`, transport metadata и ordinary BitTorrent identity; не угадывать преобразования между ними.
3. Различать `type=vod|live` и `transport_type=bt|hls|wrapper`. Только подтверждённый non-live BitTorrent transport разрешается передавать стандартному libtorrent.
4. Поддерживать явную модель `.acelive`/Ace Live descriptor и отдельную диагностику маршрута; не скрывать сбои `content_id` запуском внешнего Ace Engine.
5. Встроенный Ace Live backend покрывает transport descriptor, live window, bounded piece/chunk scheduling, DHT/tracker peer discovery, восстановление пула и локальный MPEG-TS output. Текущий шаг — измеримая непрерывная media delivery и feedback-driven scheduling.
6. Выполнить аппаратную приёмку на нескольких `acestream://content_id`, прямых Ace live identities, обычных magnet/.torrent и поддерживаемых `.acelive` источниках.

### Исследовательские ориентиры и лицензионные границы

- открытые спецификации BitTorrent/BEP и публичные API/модели используются как первичные контракты там, где применимо;
- `Flux`, TorrServe/TorrServer, LibreTorrent и другие продукты используются как архитектурные/поведенческие ориентиры streaming lifecycle, reader ownership, caching и peer scheduling; чужой код не переносится без совместимой лицензии;
- официальный Ace Stream Engine и приложения, использующие его, служат только A/B benchmark: время запуска, устойчивость, recovery и поведение на тех же каналах/устройстве. Закрытые/AOT/native реализации не копируются и не становятся зависимостью проекта;
- развитие собственного Ace Live runtime остаётся clean-room и опирается на самостоятельно проверяемые wire/runtime контракты и наши тестовые наблюдения.

### Следующие P2P-инкременты

1. ✅ **Adaptive prebuffer v1 / PR #107:** throughput clock начинается с первого media-byte; EWMA строится по реальному приросту media; discovery latency не занижает target; AUTO больше не стартует с прежнего 512-KiB floor.
2. ✅ **Producing peer accounting / PR #108:** `discovered/connected/handshaked/producing`, media bytes/rate/freshness и aggregate media delivery snapshot.
3. 🚧 **Peer quality V2b:** добавить `windowUseful/unchoked`; `windowUseful` считать относительно authoritative `nextNeededPiece()`, refresh выполнять при metadata/cursor/recovery changes, а producing требовать только от fresh requestable peers.
4. Вывести peer-quality snapshot в persistent diagnostics/UI: `discovered/connected/handshaked/windowUseful/unchoked/producing`, aggregate Mbps и freshest media age; уточнить media contribution до post-authenticated/post-output boundary.
5. Добавить `LiveBufferController`: buffer seconds + critical/low/target/high watermarks и hysteresis.
6. Сделать request depth/in-flight адаптивным к buffer pressure и фактической производительности peers вместо постоянного `2 pieces/peer`.
7. При устойчивом `buffer-ready` завершать startup-specific DHT expansion и переходить в lightweight refill; discovery не должен конкурировать с уже работающим media path.
8. Добавить player-boundary telemetry: first localhost HTTP open/read, producer/consumer rate, Media3 BUFFERING/READY/first-frame и rebuffer count.
9. Выделить P2P-specific Media3 LoadControl для уже стабилизированного localhost live stream; не использовать generic IPTV buffering как единственный feedback layer.
10. Разделить unavailable source, dead/stale swarm, insufficient throughput/buffer, transport timeout и decoder/demux error в экспортируемой диагностике.
11. Wire output discontinuity в TS keyframe/PAT/PMT recovery без переноса media-format логики внутрь peer protocol scheduler.
12. Сохранять ordinary BitTorrent regression baseline: magnet/infohash/local/HTTP torrent, read-ahead, seek и local HTTP Range.
13. Провести матрицу из обычных IPTV и Torrent TV каналов: cold/warm start, 20 переключений, повторное открытие, слабая сеть, потеря peers, двухчасовой и восьмичасовой просмотр.

## Этап 3: EPG, Now/Next и реальный архив (#47)

Текущий EPG OOM hotfix — prerequisite безопасности, а не замена полного #47. Полноценный EPG redesign выполняется только после стабилизации playback и channel identity.

1. Сохранять EPG URL из `url-tvg`, `x-tvg-url`, `tvg-url` и провайдерских API.
2. Автоматически обнаруживать XMLTV для плейлистов без явного EPG URL.
3. Добавить управляемый каталог EPG-источников и авто-сопоставление прежде всего по `tvg-id`, затем по контролируемому fallback нормализованного имени/alias/страны.
4. Добавить локальный cache/TTL/last-valid и очистку устаревших данных с жёсткими memory/disk bounds.
5. Показывать диагностику: источник найден/не найден, matched/unmatched channels, время последнего успешного обновления и bounded failure/backoff status.
6. Реализовать Now/Next, программу на день, переход по датам, описание и progress текущей передачи.
7. Поддержать catch-up/archive только когда playlist/provider реально предоставляет capability/template; строить корректный playback URL/range и не показывать фиктивный архив.
8. Интегрировать archive launch с Player только после появления проверенного playback context.

## Этап 4: Player UX (#46)

1. Стабилизировать fullscreen/overlay state machine и правило Back: сначала закрыть overlay, затем выйти из Player.
2. Построить channel/group selector поверх canonical catalog model, сохраняя специализированную Player D-pad navigation.
3. Показывать channel/source/group, Now/Next и progress из готового EPG contract.
4. Явно различать Live / Archive / P2P режимы и поддерживаемые действия.
5. Добавить retry/reconnect, live edge, archive seek, audio/subtitle и aspect controls там, где capability действительно поддерживается.
6. Использовать единый Favorites contract из #45.
7. Проверить мышь/тачпад, крупные click targets и отсутствие случайного channel/volume switch от wheel.
8. Добавить hooks для onboarding/`Управление`; полный текст Help завершить на этапе #43.

## Этап 5: contextual Help и документация (#43)

1. Добавить reusable helper components, читаемые с TV-distance и доступные D-pad/mouse.
2. Покрыть Home, Scanner, Importer, catalogs, Editor, Favorites, History, EPG/Archive, Player, Downloads, Settings, Network Test, Diagnostics и About.
3. Создать top-level `Помощь / Инструкция` с быстрым стартом, каталогами, Scanner, Favorites, Player, EPG/archive, remote/mouse, P2P status и troubleshooting.
4. Создать/поддерживать `docs/USER_GUIDE.md`, `docs/TROUBLESHOOTING.md`, `docs/REMOTE_AND_MOUSE.md` и при необходимости `docs/CATALOG_AND_EPG.md`.
5. Синхронизировать README, ROADMAP, architecture, testing/release docs и встроенный Help с фактическим поведением.

## Issue #40: кодовая baseline завершена, ручная TV-приёмка продолжается

1. Не менять `ScannerViewModel`, провайдеры и поисковую логику без отдельной причины.
2. `ScannerScreen` использует общий `TvScrollableLazyColumn`: видимые кнопки в начало/Page Up/Page Down и аппаратные PageUp/PageDown/ChannelUp/ChannelDown.
3. Штатную прокрутку колесом мыши/тачпадом и scrollbar drag сохранить без изменения query/provider/search/import semantics.
4. Общий TV-focus modifier запрашивает вывод сфокусированной карточки/standalone action в видимую область.
5. Sections D-pad regression compile проверяется Android CI; реальное поведение D-pad/mouse/Back/Exit дополнительно проверяется в BlueStacks/TV Box.
6. Любой воспроизводимый дефект из ручного тестирования исправляется отдельным минимальным hotfix PR от свежего `main`, после чего разработка возвращается к текущему roadmap этапу.

## Приёмка перед стабильным релизом

1. Проверить D-pad, оптическую мышь и тачпад на BlueStacks 5, слабом и среднем TV Box.
2. Проверить готовые списки и Media3 → LibVLC fallback на реальных потоках.
3. Проверить rapid-zap обычного IPTV на 256-MiB/аналогичном heap: malformed/large EPG не должен вызывать OOM/crash.
4. Проверить magnet/torrent и реальные Torrent TV источники через **только встроенный** P2P engine; отсутствие внешнего Ace Engine является нормальной тестовой конфигурацией.
5. Проверить, что Torrent TV live-буфер продолжает пополняться после старта, а producer/consumer rate, buffer seconds, rebuffer и stall измеряются.
6. Выполнить серию из 20 переключений без зависшей старой P2P-сессии; долгое ожидание должно завершаться ограниченной ошибкой.
7. Проверить деградацию одного/нескольких producing peers: playback либо восстанавливается в bounded budget, либо выдаёт точную причину без бесконечного retry.
8. Проверить EPG на нескольких независимых XMLTV/провайдерских источниках, включая malformed и oversized fixture.
9. Выполнить минимум двухчасовой тест непрерывного просмотра.
10. Выполнить восьмичасовой soak-тест перед стабильным релизом.
11. Проверить слабую сеть: 2–5 Мбит/с, задержка 120–250 мс, потеря 1–3%.
12. Подготовить production keystore, собрать подписанный APK и проверить подпись.
13. Заполнить отчёт приёмки и создать GitHub Release.

## После релиза

- сбор обратной связи по конкретным моделям TV Box;
- оптимизация проблемных кодеков, P2P-сессий и потоков по реальным логам;
- улучшение каталога готовых IPTV/EPG источников без изменения работающего сканера.
