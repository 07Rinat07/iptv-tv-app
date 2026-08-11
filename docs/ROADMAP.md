# План проекта

## Цель стабильной версии

Получить реально устанавливаемое Android TV / TV Box приложение, которое без внешних обязательных зависимостей воспроизводит обычные IPTV-потоки и BitTorrent-источники, умеет автоматически переключаться Media3 → LibVLC, показывает EPG при наличии/обнаружении источника и удобно управляется пультом, мышью и тачпадом.

## Завершено

- сканирование и поиск публичных плейлистов;
- импорт URL, файла, текста и провайдеров;
- редактор, избранное, история и базовый EPG;
- стартовый экран просмотра с готовыми списками;
- Media3 и LibVLC fallback с автоматическим переключением;
- внешний Ace Stream Android Service/AIDL backend;
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
1. **Issue #45 — canonical catalog hierarchy + unified Favorites.** Стабильные identity, parent/source provenance, adapters, navigation skeleton, dedup/source variants и единое избранное.
2. **Master #44 — P2P transport blocker.** После стабильного source provenance завершить собственное разрешение Ace content-id и затем отдельный Ace Live/.acelive backend, не смешивая transport с Player UX.
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

Внешний Ace Stream больше не считается достаточным решением. В APK должен находиться собственный BitTorrent/P2P backend. Ordinary BitTorrent основа уже реализована; следующий приоритет после canonical source identity — transport metadata для Ace content-id и отдельный Ace Live backend.

1. Добавить модуль `core:p2p` на базе libtorrent/libtorrent4j.
2. Поддержать `magnet:`, infohash, локальный `.torrent` и HTTP(S) URL на `.torrent`.
3. Запускать DHT, trackers и PeX внутри приложения.
4. Выбирать медиафайл торрент-сессии и скачивать его с приоритетом для просмотра.
5. Реализовать потоковую подкачку с приоритетом ближайших pieces и seek.
6. Поднять локальный HTTP Range server (`127.0.0.1`) и отдавать поток Media3/LibVLC.
7. Добавить `P2pEngineRouter`: встроенный engine — основной; внешний Ace Stream — compatibility fallback.
8. Отдельно исследовать `acestream://content_id` и `.acelive`. Не считать Ace Stream закрытым, пока реальные торрент-ТВ каналы не воспроизводятся.
9. Проверять P2P сначала в BlueStacks 5, затем на ARM TV Box.

### Детализация Ace transport / torrent-TV

1. Не считать `content_id` эквивалентом BitTorrent infohash. Явный `acestream:?infohash=...` может идти во встроенный BitTorrent backend, а чистый `acestream://content_id` сначала должен быть разрешён через transport metadata.
2. Использовать публичный контракт официального `acestream/acestream-android-sdk`: `get_media_files` в полном режиме с `expand_wrapper=1` и `dump_transport_file=1`; хранить раздельно `infohash`, `transport_file_data`, `transport_file_cache_key`, `files[]` и `wrapper_data`.
3. Различать `type=vod|live` и `transport_type=bt|hls|wrapper`. Только подтверждённый non-live BitTorrent transport разрешается передавать стандартному libtorrent.
4. Добавить явную модель `.acelive`/Ace Live descriptor и отдельную диагностику маршрута. До появления собственного совместимого live backend сохранять внешний Ace как compatibility fallback.
5. Для Ace Live отдельно исследовать transport descriptor, live window, piece/chunk scheduling, live seek, peer discovery и восстановление после пропусков. Реализацию строить только по публичной спецификации или открытой реализации; бинарные внутренности сторонних APK не копировать.
6. После стабилизации transport metadata выполнить реальную приёмку на нескольких `acestream://content_id`, обычных magnet/.torrent и `.acelive` источниках.

### Исследовательские ориентиры и лицензионные границы

- `acestream/acestream-android-sdk` — основной открытый эталон transport descriptor/API. Лицензия MIT; публичные модели и API можно адаптировать с соблюдением notice.
- `Flux-1.38.apk` — статический анализ подтвердил архитектуру встроенного torrent streaming через локальный HTTP и Go-слой на базе `anacrolix/torrent`/`tsynik/torrent`. APK используется только для поиска публичных upstream-реализаций и архитектурных ориентиров; декомпилированный/бинарный код не переносится.
- `YouROK/TorrServer` — полезный эталон поведения для streaming read-ahead, reader lifecycle, cache windows и приоритетов pieces. Проект GPL-3.0, поэтому прямое копирование его реализации в текущую кодовую базу без отдельного лицензионного решения не допускается.
- `proninyaroslav/libretorrent` — полезный эталон libtorrent4j для sequential download, first/last piece priority, alerts и metadata lifecycle. Проект GPL-3.0; использовать для сравнения поведения и API, а не для прямого копирования кода.
- `tsynik/torrent` — fork torrent engine, обнаруженный как upstream в Flux; лицензия MPL-2.0. Можно исследовать алгоритмы и поведение, но прямое включение исходных файлов потребует соблюдения MPL на уровне затронутых файлов.
- `AceStreamCoreLive` и AceServe APK — использовать как доказательство существования отдельного Ace Live subsystem и как карту компонентов/терминов. Закрытые/AOT/native реализации не переносить в исходный код приложения.

### Следующие P2P-инкременты

1. Завершить структурированную full transport-metadata модель и тесты.
2. Реализовать собственное разрешение transport data для `acestream://content_id` без обязательного внешнего Ace Engine.
3. Добавить `.acelive` descriptor/routing/backend/diagnostics без ошибочного преобразования в обычный magnet.
4. Сохранять ordinary BitTorrent regression baseline: magnet/infohash/local/HTTP torrent, read-ahead, seek и local HTTP Range.
5. Добавить/сохранить измеримые P2P diagnostics: metadata time, first-piece time, startup time, peers, download rate, seek recovery time и fallback reason.
6. Провести реальные тесты: быстрое переключение каналов, seek, слабая сеть, потеря peers, повторное открытие, долгий просмотр.

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
4. Проверить EPG на нескольких независимых XMLTV/провайдерских источниках.
5. Выполнить минимум двухчасовой тест непрерывного просмотра.
6. Выполнить восьмичасовой soak-тест перед стабильным релизом.
7. Проверить слабую сеть: 2–5 Мбит/с, задержка 120–250 мс, потеря 1–3%.
8. Подготовить production keystore, собрать подписанный APK и проверить подпись.
9. Заполнить отчёт приёмки и создать GitHub Release.

## После релиза

- сбор обратной связи по конкретным моделям TV Box;
- оптимизация проблемных кодеков, P2P-сессий и потоков по реальным логам;
- улучшение каталога готовых IPTV/EPG источников без изменения работающего сканера.
