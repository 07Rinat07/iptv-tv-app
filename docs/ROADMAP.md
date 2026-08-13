# План проекта

## Цель стабильной версии

Получить реально устанавливаемое Android TV / TV Box приложение, которое без внешних обязательных зависимостей воспроизводит обычные IPTV-потоки и BitTorrent-источники, умеет автоматически переключаться Media3 → LibVLC, показывает EPG при наличии/обнаружении источника и удобно управляется пультом, мышью и тачпадом.

## Текущий срез — 13 августа 2026

Автономный Torrent TV маршрут работает без обязательного внешнего Ace Engine. PR #98 добавил first-success/fast-switch стратегию Ace Live, PR #99 убрал полное пересоздание Media3/MediaCodec при обычном IPTV zapping, а PR #100 перенёс XMLTV на bounded streaming parse и закрыл известный EPG OOM в коде.

PR #101 завершает текущий bounded-startup инкремент и после зелёного exact-head CI сливается в `main`. В него вошли короткие DHT probe batches с немедленным возвратом первого валидного peer, фоновое расширение discovery, согласованный 30-секундный no-connected-peer guard, постоянная crash/lifecycle диагностика, 15-минутный backoff для oversized EPG и готовый каталог `📡 Ace Stream TV-Торрент ТВ` из 279 уникальных каналов.

Ручные ARM-прогоны подтвердили, что встроенный Ace Live путь способен запускать реальные источники без внешнего Ace Engine и стал немного быстрее. При этом переключение всё ещё занимает примерно в 3–5 раз больше желаемого времени, отдельные источники доходят до bounded timeout, а сообщения о закрытии процесса требуют точной классификации по persistent log и ADB logcat. Поэтому это не stable release, а завершённая база для следующего узкого fast-zap/crash-hardening этапа.

Следующий порядок работ:

1. ✅ слить PR #101 после полного exact-head CI и удалить его временную ветку;
2. измерить на одном наборе каналов `play_request → peer_connected → first_media_byte → player_ready`, отдельно для обычного IPTV и Ace Live;
3. отменять устаревшие discovery/metadata/player requests сразу при новом выборе канала и coalesce промежуточные rapid-zap запросы;
4. сократить ожидание достижимых Ace Live источников без ослабления абсолютных bounds; недоступный источник должен завершаться понятной ошибкой, а не удерживать UI;
5. классифицировать каждый выход процесса как Java crash, native crash, ANR или system kill и устранить подтверждённую причину;
6. после короткой матрицы выполнить слабую сеть, двухчасовой и восьмичасовой release gate.

Актуальные доказательства и критерии находятся в [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md). Подробный разбор новых OOM-журналов и точный scope текущего PR сохранены в [`testing/playback-log-analysis-2026-08-12.md`](testing/playback-log-analysis-2026-08-12.md). Исторический P2P-разбор 11 августа остаётся в [`testing/playback-log-analysis-2026-08-11.md`](testing/playback-log-analysis-2026-08-11.md).

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
- PR #98: direct Ace Live startup и metadata resolution выполняются конкурентно с first-success semantics; отмена проигравших путей и regression coverage прошли real Torrent TV smoke без внешнего Ace Engine;
- PR #99: Media3/MediaCodec stack переиспользуется при zapping обычных IPTV-каналов вместо полного `release()/rebuild` на каждый session change;
- PR #100: XMLTV читается bounded streaming parser, тяжёлые EPG loads сериализованы, а malformed/low-memory failures имеют ограниченный negative-cache/fail-safe;
- PR #101: bounded Ace Live discovery согласован с no-connected-peer guard, первый DHT peer возвращается без ожидания полного обхода, диагностика переживает перезапуск процесса, oversized EPG получает длительный backoff, а готовый Torrent TV каталог содержит 279 уникальных Ace Stream каналов;
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

Этот порядок имеет приоритет над исторической нумерацией технических блоков ниже. Подтверждённый crash/ANR/memory regression всегда получает приоритет над функциональным roadmap.

0. **Issue #40 — regression baseline TV navigation.** Кодовая база D-pad/mouse уже стандартизирована; реальные BlueStacks/TV Box проверки идут параллельно. Подтверждённая ручная регрессия получает отдельный минимальный hotfix PR и не ждёт конца roadmap.
1. **Playback safety acceptance — EPG OOM.** Код PR #100 слит: streaming XMLTV, bounded memory/cache, negative-cache, serialized load и low-memory fail-safe. Осталось ручное подтверждение, что повреждённый/огромный EPG отключает только программу, а не playback process.
2. **Master #44 — playback/P2P hardening.** PR #101 слит как bounded-startup baseline. Следующий узкий инкремент — измерить и сократить fast-zap latency, исключить stale requests, классифицировать закрытия процесса, затем улучшить непрерывную подкачку и peer/stall recovery.
3. **Issue #45 — canonical catalog hierarchy + unified Favorites.** После стабилизации критического playback path продолжить identity, navigation skeleton, dedup/source variants и единое избранное.
4. **Issue #47 — EPG / Now-Next / real archive.** Полноценный ingestion/cache/matching/catch-up redesign строить поверх стабильной channel identity из #45; текущий OOM hotfix не должен превращаться в полный #47 redesign.
5. **Issue #46 — Player UX redesign.** Строить fullscreen/overlay/channel selector/Now-Next/Archive/P2P controls поверх уже готовых Catalog + P2P + EPG contracts.
6. **Issue #43 — contextual Help + built-in Help + docs baseline.** Завершать после стабилизации основных экранов, чтобы не переписывать подсказки после Catalog/EPG/Player изменений.
7. **Master #44 release gate.** Hardware/playback hardening, P2P acceptance, D-pad-only/mouse-only sessions, weak network, 2h/8h soak, signed release и финальная синхронизация docs. Только после этого закрывать #44 и объявлять stable.

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

Внешний Ace Stream больше не считается достаточным решением. В APK находятся собственные ordinary BitTorrent и Ace Live backends; текущий приоритет — стабильная подкачка, быстрое переключение и аппаратная приёмка автономного playback path после устранения общего EPG memory crash.

1. ✅ Добавлен модуль `core:p2p` на базе libtorrent/libtorrent4j.
2. ✅ Поддержаны `magnet:`, infohash, локальный `.torrent` и HTTP(S) URL на `.torrent`.
3. ✅ DHT, trackers и PeX запускаются внутри приложения.
4. ✅ Для обычного BitTorrent выбирается медиафайл и настраиваются streaming priorities.
5. ✅ Реализованы read-ahead/seek для ordinary BitTorrent и отдельный live-window scheduler для Ace Live.
6. ✅ Локальный HTTP Range/stream endpoint отдаёт данные Media3/LibVLC.
7. ✅ `P2pEngineRouter` оставляет embedded backend основным; Torrent TV `content_id` и live infohash не падают автоматически во внешний Ace Engine.
8. ✅ Отдельная модель Ace Live не смешивает `content_id`, live infohash и BitTorrent BTIH.
9. ✅ PR #98 сократил serial startup wait: direct swarm и metadata resolution теперь гоняются first-success, проигравшие операции отменяются; regression тест и real Torrent TV smoke прошли перед merge.
10. ✅ PR #101: bounded startup discovery, exact-head CI, ARM-логи, persistent diagnostics и готовый каталог из 279 Ace Stream каналов завершены; дальнейшее ускорение переключения вынесено в следующий инкремент.

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
- `AceStreamCoreLive` 3.1.67, официальный Ace Stream Engine 3.1.73 и AceServe APK — использовать как доказательство существования отдельного Ace Live subsystem и как поведенческую карту компонентов/терминов. Статическое сравнение DHT подтвердило практику параллельных запросов с коротким request timeout; текущая clean-room реализация выбирает более консервативное ветвление 4 для TV memory/IO bounds. Закрытые/AOT/native реализации не переносить в исходный код приложения.

### Следующие P2P-инкременты

1. Добавить метрики retained buffer, download/consume rate, first-media-byte, startup, rebuffer, peer count и stall reason.
2. Разделить `playbackRequestId` и active `playbackSessionId`; все callbacks обязаны соблюдать `latest request wins`.
3. Обеспечить непрерывное пополнение live-буфера: adaptive low/target/high-water, feedback-driven request pipeline и ограниченное восстановление после потери peers.
4. После PR #101 измерить остаточную задержку переключения и не возвращать serial 60-second wait, уже устранённый PR #98.
5. Разделить unavailable source, dead/stale swarm, insufficient buffer, transport timeout и decoder/demux error в UI и экспортируемой диагностике.
6. Подтвердить PR #100 на 256-MiB/аналогичном устройстве; не считать штатную EPG-отмену playback error.
7. Расширить `.acelive` routing/diagnostics без ошибочного преобразования в обычный magnet.
8. Сохранять ordinary BitTorrent regression baseline: magnet/infohash/local/HTTP torrent, read-ahead, seek и local HTTP Range.
9. Провести матрицу из обычных IPTV и Torrent TV каналов: 20 переключений, повторное открытие, слабая сеть, потеря peers, двухчасовой и восьмичасовой просмотр.

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
4. Проверить magnet/torrent и реальные торрент-ТВ источники через встроенный P2P engine.
5. Проверить, что Torrent TV live-буфер продолжает пополняться после старта, а rebuffer и stall измеряются.
6. Выполнить серию из 20 переключений без зависшей старой P2P-сессии; долгое ожидание должно завершаться ограниченной ошибкой.
7. Проверить EPG на нескольких независимых XMLTV/провайдерских источниках, включая malformed и oversized fixture.
8. Выполнить минимум двухчасовой тест непрерывного просмотра.
9. Выполнить восьмичасовой soak-тест перед стабильным релизом.
10. Проверить слабую сеть: 2–5 Мбит/с, задержка 120–250 мс, потеря 1–3%.
11. Подготовить production keystore, собрать подписанный APK и проверить подпись.
12. Заполнить отчёт приёмки и создать GitHub Release.

## После релиза

- сбор обратной связи по конкретным моделям TV Box;
- оптимизация проблемных кодеков, P2P-сессий и потоков по реальным логам;
- улучшение каталога готовых IPTV/EPG источников без изменения работающего сканера.
