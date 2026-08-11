# План проекта

## Цель стабильной версии

Получить реально устанавливаемое Android TV / TV Box приложение, которое без внешних обязательных зависимостей воспроизводит обычные IPTV-потоки и BitTorrent-источники, умеет автоматически переключаться Media3 → LibVLC, показывает EPG при наличии/обнаружении источника и удобно управляется пультом, мышью и тачпадом.

## Текущий срез — 11 августа 2026

Автономный Torrent TV маршрут уже воспроизвёл активный `content_id` на чистом эмуляторе без Ace Engine и повторно запустился после полной остановки. Это закрывает технический bootstrap, но не закрывает стабильность воспроизведения.

Текущий главный блокер — **playback hardening**:

1. переключение каналов занимает слишком много времени и иногда завершается ошибкой;
2. скользящий Ace Live buffer существует, но на части источников не удерживает достаточную непрерывную подкачку;
3. часть swarm недоступна, закрывает соединения до handshake либо перестаёт публиковать новые pieces;
4. длительная приёмка и реальные ARM TV Box ещё не пройдены;
5. звук на большинстве запустившихся каналов нормальный; единичные audio-дефекты остаются отдельной проверкой, а не главным P2P-блокером.

Актуальные доказательства, ограничения и критерии завершения находятся в [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md). До выполнения этих критериев stable release не объявляется.

Разбор журнала от 16:27 зафиксировал 29 запросов переключения, 6 успешных P2P-подготовок за 21,2–47,3 с, 4 ошибки через 75,9–87,2 с и отсутствие buffer telemetry. План исправления lifecycle, общего deadline, подкачки и EPG находится в [`testing/playback-log-analysis-2026-08-11.md`](testing/playback-log-analysis-2026-08-11.md).

## Завершено

- сканирование и поиск публичных плейлистов;
- импорт URL, файла, текста и провайдеров;
- редактор, избранное, история и базовый EPG;
- стартовый экран просмотра с готовыми списками;
- Media3 и LibVLC fallback с автоматическим переключением;
- legacy Ace Stream Android Service/AIDL compatibility integration;
- встроенный libtorrent backend для `magnet:`, infohash и `.torrent`;
- встроенный Ace Live runtime для Torrent TV `content_id`/live infohash без автоматического внешнего fallback;
- прямой peer discovery/handshake, live-window, chunk reassembly и локальная MPEG-TS выдача;
- пересоздание P2P-сессии при retry, ограниченный stall watchdog и корректная остановка при переключении;
- улучшенное обнаружение актуального Ace Stream приложения/службы;
- TV-интерфейс, пульт, мышь, тачпад и полноэкранный режим;
- группы, подгруппы, восстановление фокуса и постраничная прокрутка в основных разделах;
- Editor использует общий `TvScrollableLazyColumn`: TV-кнопки начала/Page Up/Page Down, PageUp/PageDown и ChannelUp/ChannelDown, mouse wheel/touchpad и scrollbar без изменения операций редактирования;
- меню «Разделы» при открытии фокусирует первый доступный маршрут, удерживает TV-focus видимым и возвращает фокус на кнопку «Разделы» после закрытия/перехода; диалог выхода по умолчанию фокусирует безопасную «Отмена»;
- общий `tvBringIntoViewOnFocus()` поддерживает focus-follow без изменения стандартного вида кнопок; `tvFocusOutline()` использует тот же механизм; standalone actions подключены на About и History, верхние вкладки Diagnostics, основная action-группа Favorites и action-группы Downloads также удерживаются в видимой области; Settings selection/section controls, основные source action-группы Importer, action controls Network Test и standalone action-группа Playlists также используют focus-follow; аппаратная приёмка на BlueStacks/TV Box остаётся обязательной;
- D-pad smoke-тест меню «Разделы» проверяет реальное перемещение focus Scanner → Importer и активацию DPAD_CENTER; Android CI компилирует `:app:assembleDebugAndroidTest`, чтобы instrumentation-тесты не оставались непроверенным исходным кодом;
- адаптивный буфер для слабых устройств;
- автоматические lint, unit, debug и release сборки;
- очистка документации, workflow и бинарных артефактов.

## Актуальный оптимизированный порядок выполнения

Этот порядок имеет приоритет над исторической нумерацией технических блоков ниже. Причина — сначала стабилизировать contracts данных/transport, затем строить зависимые EPG и Player UI, чтобы не переделывать одни и те же экраны несколько раз.

0. **Issue #40 — завершить regression baseline TV navigation.** Кодовая база D-pad/mouse уже стандартизирована; реальные BlueStacks/TV Box проверки идут параллельно. Подтверждённая ручная регрессия получает отдельный минимальный hotfix PR и не ждёт конца roadmap.
1. **Master #44 — playback/P2P hardening.** Сократить и ограничить время переключения, обеспечить измеримую непрерывную подкачку, улучшить peer/stall recovery и пройти аппаратную приёмку без внешнего Ace Engine.
2. **Issue #45 — canonical catalog hierarchy + unified Favorites.** После стабилизации критического playback path продолжить identity, navigation skeleton, dedup/source variants и единое избранное.
3. **Issue #47 — EPG / Now-Next / real archive.** Строить ingestion/cache/matching и catch-up поверх стабильной channel identity из #45.
4. **Issue #46 — Player UX redesign.** Строить fullscreen/overlay/channel selector/Now-Next/Archive/P2P controls поверх уже готовых Catalog + P2P + EPG contracts.
5. **Issue #43 — contextual Help + built-in Help + docs baseline.** Завершать после стабилизации основных экранов, чтобы не переписывать подсказки после Catalog/EPG/Player изменений.
6. **Master #44 release gate.** Hardware/playback hardening, P2P acceptance, D-pad-only/mouse-only sessions, weak network, 2h/8h soak, signed release и финальная синхронизация docs. Только после этого закрывать #44 и объявлять stable.

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

Внешний Ace Stream больше не считается достаточным решением. В APK находятся собственные ordinary BitTorrent и Ace Live backends; текущий приоритет — стабильная подкачка, быстрое переключение и аппаратная приёмка автономного playback path.

1. ✅ Добавлен модуль `core:p2p` на базе libtorrent/libtorrent4j.
2. ✅ Поддержаны `magnet:`, infohash, локальный `.torrent` и HTTP(S) URL на `.torrent`.
3. ✅ DHT, trackers и PeX запускаются внутри приложения.
4. ✅ Для обычного BitTorrent выбирается медиафайл и настраиваются streaming priorities.
5. ✅ Реализованы read-ahead/seek для ordinary BitTorrent и отдельный live-window scheduler для Ace Live.
6. ✅ Локальный HTTP Range/stream endpoint отдаёт данные Media3/LibVLC.
7. ✅ `P2pEngineRouter` оставляет embedded backend основным; Torrent TV `content_id` и live infohash не падают автоматически во внешний Ace Engine.
8. ✅ Отдельная модель Ace Live не смешивает `content_id`, live infohash и BitTorrent BTIH.
9. ⏳ Завершить playback hardening и приёмку на эмуляторе, затем на ARM TV Box.

### Детализация Ace transport / torrent-TV

1. Не считать `content_id` эквивалентом BitTorrent infohash. Явный `acestream:?infohash=...` может идти во встроенный BitTorrent backend, а чистый `acestream://content_id` сначала должен быть разрешён через transport metadata.
2. Использовать публичный контракт официального `acestream/acestream-android-sdk`: `get_media_files` в полном режиме с `expand_wrapper=1` и `dump_transport_file=1`; хранить раздельно `infohash`, `transport_file_data`, `transport_file_cache_key`, `files[]` и `wrapper_data`.
3. Различать `type=vod|live` и `transport_type=bt|hls|wrapper`. Только подтверждённый non-live BitTorrent transport разрешается передавать стандартному libtorrent.
4. Поддерживать явную модель `.acelive`/Ace Live descriptor и отдельную диагностику маршрута; не скрывать сбои `content_id` запуском внешнего Ace Engine.
5. Встроенный Ace Live backend покрывает transport descriptor, live window, bounded piece/chunk scheduling, DHT/tracker peer discovery, восстановление пула и локальный MPEG-TS output. Следующий шаг — измеримая подкачка, быстрое переключение и длительная аппаратная проверка.
6. Выполнить аппаратную приёмку на нескольких `acestream://content_id`, прямых Ace `infohash`, обычных magnet/.torrent и `.acelive` источниках.

### Исследовательские ориентиры и лицензионные границы

- `acestream/acestream-android-sdk` — основной открытый эталон transport descriptor/API. Лицензия MIT; публичные модели и API можно адаптировать с соблюдением notice.
- `Flux-1.38.apk` — статический анализ подтвердил архитектуру встроенного torrent streaming через локальный HTTP и Go-слой на базе `anacrolix/torrent`/`tsynik/torrent`. APK используется только для поиска публичных upstream-реализаций и архитектурных ориентиров; декомпилированный/бинарный код не переносится.
- `YouROK/TorrServer` — полезный эталон поведения для streaming read-ahead, reader lifecycle, cache windows и приоритетов pieces. Проект GPL-3.0, поэтому прямое копирование его реализации в текущую кодовую базу без отдельного лицензионного решения не допускается.
- `proninyaroslav/libretorrent` — полезный эталон libtorrent4j для sequential download, first/last piece priority, alerts и metadata lifecycle. Проект GPL-3.0; использовать для сравнения поведения и API, а не для прямого копирования кода.
- `tsynik/torrent` — fork torrent engine, обнаруженный как upstream в Flux; лицензия MPL-2.0. Можно исследовать алгоритмы и поведение, но прямое включение исходных файлов потребует соблюдения MPL на уровне затронутых файлов.
- `AceStreamCoreLive` и AceServe APK — использовать как доказательство существования отдельного Ace Live subsystem и как карту компонентов/терминов. Закрытые/AOT/native реализации не переносить в исходный код приложения.

### Следующие P2P-инкременты

1. Добавить метрики retained buffer, download/consume rate, first-media-byte, startup, rebuffer, peer count и stall reason.
2. Разделить `playbackRequestId` и active `playbackSessionId`; все callbacks обязаны соблюдать `latest request wins`.
3. Обеспечить непрерывное пополнение live-буфера: adaptive low/target/high-water, feedback-driven request pipeline и ограниченное восстановление после потери peers.
4. Сократить переключение: cooperative cancellation, общий startup deadline поверх всех фаз и гарантированное освобождение loopback stream.
5. Разделить unavailable source, dead/stale swarm, insufficient buffer, transport timeout и decoder/demux error в UI и экспортируемой диагностике.
6. Кэшировать одинаковую ошибку XMLTV и не считать штатную EPG-отмену playback error.
7. Расширить `.acelive` routing/diagnostics без ошибочного преобразования в обычный magnet.
8. Сохранять ordinary BitTorrent regression baseline: magnet/infohash/local/HTTP torrent, read-ahead, seek и local HTTP Range.
9. Провести матрицу из обычных IPTV и Torrent TV каналов: 20 переключений, повторное открытие, слабая сеть, потеря peers, двухчасовой и восьмичасовой просмотр.

## Этап 3: EPG, Now/Next и реальный архив (#47)

1. Сохранять EPG URL из `url-tvg`, `x-tvg-url`, `tvg-url` и провайдерских API.
2. Автоматически обнаруживать XMLTV для плейлистов без явного EPG URL.
3. Добавить управляемый каталог EPG-источников и авто-сопоставление прежде всего по `tvg-id`, затем по контролируемому fallback нормализованного имени/alias/страны.
4. Добавить локальный cache/TTL/last-valid и очистку устаревших данных.
5. Показывать диагностику: источник найден/не найден, matched/unmatched channels, время последнего успешного обновления.
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
3. Проверить magnet/torrent и реальные торрент-ТВ источники через встроенный P2P engine.
4. Проверить, что Torrent TV live-буфер продолжает пополняться после старта, а rebuffer и stall измеряются.
5. Выполнить серию из 20 переключений без зависшей старой P2P-сессии; долгое ожидание должно завершаться ограниченной ошибкой.
6. Проверить EPG на нескольких независимых XMLTV/провайдерских источниках.
7. Выполнить минимум двухчасовой тест непрерывного просмотра.
8. Выполнить восьмичасовой soak-тест перед стабильным релизом.
9. Проверить слабую сеть: 2–5 Мбит/с, задержка 120–250 мс, потеря 1–3%.
10. Подготовить production keystore, собрать подписанный APK и проверить подпись.
11. Заполнить отчёт приёмки и создать GitHub Release.

## После релиза

- сбор обратной связи по конкретным моделям TV Box;
- оптимизация проблемных кодеков, P2P-сессий и потоков по реальным логам;
- улучшение каталога готовых IPTV/EPG источников без изменения работающего сканера.
