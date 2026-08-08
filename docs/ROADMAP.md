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
- адаптивный буфер для слабых устройств;
- автоматические lint, unit, debug и release сборки;
- очистка документации, workflow и бинарных артефактов.

## Блокирующий этап 1: встроенный P2P engine

Внешний Ace Stream больше не считается достаточным решением. В APK должен находиться собственный BitTorrent/P2P backend.

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
2. Добавить `.acelive` descriptor/routing/diagnostics без ошибочного преобразования в обычный magnet.
3. Независимо улучшить bounded read-ahead и tiered piece priorities нашего libtorrent backend, сравнивая поведение с публичными torrent-streaming реализациями, но сохраняя собственную реализацию.
4. Добавить измеримые P2P diagnostics: metadata time, first-piece time, startup time, peers, download rate, seek recovery time и fallback reason.
5. Провести реальные тесты: быстрое переключение каналов, seek, слабая сеть, потеря peers, повторное открытие, долгий просмотр.

## Блокирующий этап 2: EPG и архив

1. Сохранять EPG URL из `url-tvg`, `x-tvg-url`, `tvg-url` и провайдерских API.
2. Автоматически обнаруживать XMLTV для плейлистов без явного EPG URL.
3. Добавить управляемый каталог EPG-источников и авто-сопоставление по `tvg-id`, нормализованному имени и стране.
4. Показывать диагностику: источник найден/не найден, сколько каналов сопоставлено, время последнего обновления.
5. Поддержать catch-up/archive только когда плейлист или провайдер реально предоставляет соответствующий URL/шаблон. Не показывать фиктивный архив.

## Блокирующий этап 3: удобство сканера без изменения алгоритма поиска

1. Не менять `ScannerViewModel`, провайдеры и поисковую логику без отдельной причины.
2. `ScannerScreen` использует общий `TvScrollableLazyColumn`: видимые кнопки в начало/Page Up/Page Down и аппаратные PageUp/PageDown/ChannelUp/ChannelDown.
3. Штатную прокрутку колесом мыши/тачпадом и scrollbar drag сохранить без изменения query/provider/search/import semantics.
4. Общий TV-focus modifier запрашивает вывод сфокусированной карточки в видимую область; отдельно проверить это после прокрутки и возврата из предпросмотра на BlueStacks/TV Box.

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
