# План проекта

## Цель стабильной версии

Получить реально устанавливаемое Android TV / TV Box приложение, которое без внешних обязательных P2P-зависимостей воспроизводит обычные IPTV-потоки, BitTorrent и Torrent TV/Ace Live. Главный технический приоритет проекта — собственный автономный live-P2P runtime: он должен быстро находить полезные пиры, удерживать непрерывный запас media, переживать деградацию отдельных peers и давать плееру стабильный локальный поток. Media3 остаётся основным декодером, LibVLC — изолированным fallback только для подтверждённых container/demux/codec проблем.

Целевой ориентир P2P — не просто функциональная совместимость, а измеримое качество: быстрый zap здорового swarm, минимальный rebuffer, bounded recovery, отсутствие stale-session гонок и длительная стабильность на реальном TV Box. На same-device A/B источниках, где Televizo + Ace Stream Engine стабильно запускает тот же Torrent TV канал примерно за 2–4 секунды, автономный runtime должен стремиться к сопоставимому времени и успешности запуска. Внешний Ace Stream Engine не является runtime-стратегией, fallback или требованием проекта; сторонние приложения/движки используются только как поведенческий benchmark при A/B-проверке тех же каналов и устройств.

## Текущий срез — 15 августа 2026

Автономный Torrent TV маршрут работает без внешнего Ace Engine. Базовая цепочка уже включает public tracker/DHT discovery, TCP peer pool, Ace Live handshake/window/chunk scheduling, bounded recovery, MPEG-TS resync, sliding loopback output и Media3/LibVLC playback.

После PR #101 последовательно завершены дополнительные hardening/adaptive-инкременты:

- PR #102 — playback latency analyzer и точная разбивка `play_request → resolve → READY`;
- PR #103 — cancellable rapid-zap coalescing P2P запросов;
- PR #104 — прямой Ace startup продолжает peer progress внутри существующего soft-window без ослабления абсолютных bounds;
- PR #105 — глобально уникальные playback session IDs, request ownership, отменяемый retry и защита от `A → B → C → late retry A`;
- PR #106 — полный Torrent TV каталог всегда видим; динамическая P2P-доступность показывается как информационный last-known status и больше не скрывает каналы;
- PR #107 — adaptive prebuffer v1: throughput clock начинается с first-media, используется EWMA media delivery, усилен startup reserve без увеличения failure bounds;
- PR #108 — media-producing peer accounting: discovered endpoints отделены от connected/handshaked/producing peers, добавлены freshness и aggregate EWMA media rate; exact-head Android CI #495 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно;
- PR #109 — requestability quality: `windowUseful/unchoked` привязаны к authoritative cursor и реальному peer-wire state; Android CI #497 и real smoke прошли успешно;
- PR #110 — persistent peer-quality diagnostics: structured snapshot хранится в существующем `SyncLogDao` path; Android CI #500 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно;
- PR #111 — media-producing evidence перенесён за authentication/resync/live-output boundary;
- PR #112 — добавлен `LiveBufferController` с `CRITICAL/LOW/TARGET/HIGH` и hysteresis;
- PR #113 — confirmed per-reader consumer cursor/rate и playable headroom;
- PR #114 — authoritative active-consumer selection при overlap/reconnect;
- PR #115 — authoritative lifecycle подключён к реальному loopback и прошёл Android CI #511 + real Torrent TV smoke;
- PR #116 — authoritative pressure подключён к bounded request depth `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`; Android CI #513, real smoke и signed ARM TV APK прошли успешно;
- PR #117 — pressure-aware additive refill `LOW +1 / CRITICAL +2`, без eviction; per-peer quality snapshots подготовлены для replacement; Android CI #515, real smoke и signed ARM TV APK прошли успешно.
- PR #118 — bounded replacement деградировавших peers только при свежем sustained `CRITICAL`, с producing/baseline/cooldown guards; Android CI #517, real smoke и signed ARM TV APK прошли успешно.
- PR #119 — stable-ready отменяет startup-only DHT probe/full-expansion, но сохраняет normal lightweight refill; Android CI #519 и real Torrent TV smoke без внешнего Ace Engine прошли успешно.
- PR #120 — bounded recovery игнорирует полностью lagging peer windows при выборе ближайшего future-window; Android CI #522, real Torrent TV smoke, core P2P tests и signed ARM TV APK прошли успешно.
- PR #121 — V4a добавил first localhost HTTP open/read, Media3 `BUFFERING/READY/first-frame` и rebuffer telemetry для P2P; Android CI #524, playback-latency tooling и real Torrent TV smoke прошли успешно.
- PR #122 — V4b выделил bounded P2P-specific Media3 LoadControl и dedicated P2P player smoke gate; Android CI #531 и dedicated real Torrent TV smoke #2 прошли успешно.

Полевой прогон 15 августа 2026 на реальном Android/TV Box path подтвердил, что переход сразу от V4c к широкому acceptance преждевременен. Для одного запуска `channelId=13` первый localhost reader открылся только при `elapsed_ms=29587` embedded runtime и уже имел около 6.8 MB retained media, однако `player_ready` пришёл ещё через `startupMs=66391`. Второй reader открылся примерно при `elapsed_ms=95874`, после чего READY наступил практически сразу. До этого первый reader доставил около 55.6 MB, а authoritative headroom почти постоянно оставался `CRITICAL` — около 458656 bytes, то есть лишь десятки/сотни миллисекунд по текущей duration-оценке. Одновременно peer pool в основном имел только один connected/handshaked peer, чья `windowUseful/producing` активность чередовалась с многосекундными провалами. Для rapid-zap следующих каналов initial tracker возвращал один endpoint быстро (176–355 ms), но он не становился useful producer до следующего переключения; startup DHT probe в одном случае занял 4316 ms и снова дал лишь одного peer. Детальный разбор и V4d-план зафиксированы в [`ACE_LIVE_FIELD_VALIDATION_2026-08-15.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-15.md).

V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3i (PR #112–#120) завершили stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, bounded request depth/refill/replacement, stable-ready discovery shutdown и bounded recovery selection. V4a/PR #121 закрыл измеряемую `loopback/player boundary`: first localhost HTTP open/read плюс Media3 `BUFFERING/READY/first-frame` и rebuffer telemetry. V4b/PR #122 выделил отдельный bounded Media3 LoadControl только для P2P localhost playback. Текущий **V4c** усиливает discontinuity/TS hardening: после подтверждённого recovery jump output заново проходит stable TS sync и ждёт свежие PAT/PMT плюс video random-access/IDR evidence перед возобновлением Media3; scheduler/recovery bounds не меняются. Следующий обязательный блок **V4d** — startup/zap latency parity: выяснить и устранить первый-reader/reopen bottleneck, убрать сериализованные многосекундные startup-паузы там, где metadata/peer alternatives уже actionable, и не использовать pre-READY parser burst как безусловную playback-rate оценку.

Следующий порядок P2P-работ:

1. ✅ **Adaptive prebuffer v1 (PR #107)** — first-media throughput clock, EWMA и безопасный startup reserve без увеличения 30/60-second failure bounds.
2. ✅ **Media-producing peer accounting (PR #108–#111)** — lifecycle/requestability/persistent diagnostics и post-authenticated/post-output semantics.
3. ✅ **Adaptive live scheduler (PR #112–#120)** — `LiveBufferController`, watermarks, authoritative pressure, adaptive request depth/refill/replacement, stable-ready discovery shutdown и bounded recovery selection.
4. ✅ **Loopback/player boundary + P2P LoadControl (PR #121–#122)** — first HTTP read, Media3 boundary telemetry и отдельный bounded P2P LoadControl.
5. 🚧 **Discontinuity/TS hardening (V4c / PR #123)** — PAT/PMT/random-access/IDR gating после подтверждённого live-window jump.
6. **Startup/zap latency parity (V4d, blocker перед acceptance)** — полный startup timeline, reader close/reopen reason, Media3 load/retry/extractor readiness, устранение serial direct-soft-window → metadata startup там, где это подтверждено, ранний конкурентный useful-peer path и корректная pre-READY buffer-pressure модель. Same-device benchmark: здоровые каналы должны стремиться к 2–4 s start/zap и сопоставимой успешности с Televizo + Ace Stream Engine без превращения внешнего engine в runtime dependency.
7. **Acceptance** — фиксированная A/B матрица Torrent TV, 20 rapid switches, weak network, peer loss, 2h/8h ARM soak без внешнего Ace Engine после закрытия V4d blocker.

Актуальные доказательства и критерии находятся в [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md), детали runtime — в [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md), целевая adaptive-архитектура — в [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md), полевой V4d-разбор — в [`ACE_LIVE_FIELD_VALIDATION_2026-08-15.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-15.md), а пошаговый clean-room план Ace Live — в [`ACE_LIVE_IMPLEMENTATION_PLAN.md`](ACE_LIVE_IMPLEMENTATION_PLAN.md).

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
- PR #109: `windowUseful/unchoked` и authoritative-cursor requestability добавлены в quality snapshot; exact-head CI #497 и real smoke прошли без внешнего Ace Engine;
- PR #110: persistent `embedded_ace_live_peer_quality` diagnostics добавлены в существующий structured diagnostics path; exact-head CI #500 и real smoke прошли без внешнего Ace Engine;
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
2. **Master #44 — автономный P2P/Ace Live engine.** Это текущий главный приоритет. Ownership/rapid-zap базовые гонки закрыты PR #103/#105; adaptive streaming core прошёл V1–V3 и V4a/V4b. Текущий порядок внутри #44: `V4c TS/discontinuity hardening → V4d startup/zap latency parity → fixed A/B matrix → weak-network/peer-loss/soak acceptance`.
3. **Issue #45 — canonical catalog hierarchy + unified Favorites.** Identity/provenance и пользовательская navigation часть закрыты PR #167/#168; durable Favorites/virtual aggregate/Player consumer закрыты PR #170–#172; portable backup backend закрыт PR #174. Текущий кодовый этап — безопасный TXT/M3U8 + `.riptv` export, затем `.riptv` file-picker import, source-variant picker и оставшиеся virtual/performance checks.
4. **Issue #47 — EPG / Now-Next / real archive.** Полноценный ingestion/cache/matching/catch-up redesign строить поверх стабильной channel identity из #45.
5. **Issue #46 — Player UX redesign.** Строить fullscreen/overlay/channel selector/Now-Next/Archive/P2P controls поверх уже готовых Catalog + P2P + EPG contracts.
6. **Issue #43 — contextual Help + built-in Help + docs baseline.** Catalog navigation уже имеет встроенную краткую справку и `USER_GUIDE`; полное contextual покрытие остальных экранов продолжить после стабилизации Catalog/EPG/Player.
7. **Master #44 release gate.** Hardware/playback hardening, автономная P2P acceptance, D-pad-only/mouse-only sessions, weak network, 2h/8h soak, signed release и финальная синхронизация docs. Только после этого закрывать #44 и объявлять stable.

## Статус B5 / Issue #45 — 22 августа 2026

- ✅ PR #167 — canonical tree navigation contract: path/checkpoint, breadcrumb context, one-level Back и stable focus semantics.
- ✅ PR #168 — реальная интеграция в `feature:playlists`: `observeChannels → LegacyPlaylistCatalogAdapter → CanonicalCatalogNavigator → UI`, exact `playlistId + channelId` route в Player, off-screen focus restore и единый Android Back dispatcher.
- ✅ PR #170 — автономный durable Favorites persistence с `ChannelStableIdentity`, snapshot и persisted source variants, переживающими удаление исходного playlist/channel row.
- ✅ PR #171/#172 — единый live/persisted playback context, virtual Favorites aggregate и подключение к существующему Player без второго playback runtime.
- ✅ PR #174 — versioned `rinat-iptv-favorites` portable backend: logical identity, provenance/source variants, validate-before-write import, idempotent merge/relink и default credential redaction; exact-head Database Unit CI #10 + Android CI #708 прошли перед squash merge.
- 🚧 PR #175 — TXT/M3U8 переводятся на ту же data-layer credential policy; unsafe preferred source использует safe alternate variant при наличии, TXT пишет `[REDACTED]`, M3U8 пропускает favorite без safe URL; `.riptv` export открыт в Favorites UI.
- ⏳ Следующий fresh-main инкремент — `.riptv` import через существующий `ActivityResultContracts.OpenDocument()` pattern с отображением imported/merged/redacted/skipped результата.
- ⏳ Затем — source-variant picker и оставшиеся virtual aggregate views/performance/non-blocking rebuild проверки.

## Этап 1: canonical catalog hierarchy и provenance (#45)

Целевая структура:

`Source/Catalog -> Subcatalog -> Playlist/List -> Group/Subgroup -> Channel`.

1. ✅ Введены в `core/model` стабильные `CatalogNodeId`, `CatalogNodeKind`, `CatalogProvenance` и parent/order contract без зависимости от Room auto-generated id.
2. ✅ Общий `ChannelStableIdentity` синхронизирует логическую identity канала между canonical catalog и unified Favorites.
3. ✅ Persist provenance: `catalogOrigin` сохраняется в Room/domain; provider/local источники, Ready Catalog и Scanner imports получают однозначный origin, который попадает в canonical tree.
4. ✅ PR #167/#168: navigation skeleton подключён к реальному Playlists UI — predictable one-level Back, breadcrumb context, exact-channel Player route и focus restore после Player/rebuild.
5. ✅ PR #170–#172: Favorites переведён на единый агрегированный durable persistence/playback слой с provenance/source variants и virtual aggregate view.
6. ✅ Dedup/re-import identity и source variants используют `ChannelStableIdentity`; повторный импорт live-эквивалента не создаёт второй logical favorite.
7. 🚧 Virtual views: Favorites уже подключён; All channels, Recent/History и позднее Now/Next/EPG/archive/P2P filters остаются следующими отдельными инкрементами.
8. Проверить lazy rendering, кэш подготовленной структуры и non-blocking rebuild больших наборов.
9. ✅ PR #174 + текущий #175: versioned portable Favorites contract и безопасный share/export слой не используют локальные Room IDs как portable identity и не раскрывают credential-bearing provider URLs по умолчанию.

Contract/identity/provenance, durable Favorites, virtual Favorites и Player consumer уже слиты отдельными малыми PR. Текущие export/import инкременты меняют только Favorites data/UI contract и не переписывают Scanner discovery/query, Player playback policy или P2P budgets. После safe export следующий production PR начинается только от свежего `main` и добавляет `.riptv` file-picker import.

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
15. ✅ PR #109: `windowUseful/unchoked` и authoritative-cursor requestability добавлены в quality snapshot.
16. ✅ PR #110: persistent structured peer-quality diagnostics добавлены в существующий diagnostics repository path.
17. ✅ PR #111: production accounting перенесён на post-authenticated/post-output boundary с сохранением peer provenance.
18. ✅ PR #112–#120: buffer-pressure controller, authoritative consumer, adaptive request depth/refill/replacement, stable-ready discovery shutdown и bounded recovery selection.
19. ✅ PR #121–#122: loopback/player boundary telemetry и P2P-specific Media3 LoadControl.
20. 🚧 PR #123 / V4c: decoder-safe TS discontinuity gate после подтверждённого recovery jump.
21. **V4d:** startup/zap latency parity blocker по реальным TV Box логам; до его закрытия broad acceptance не считается начатым.

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
2. ✅ **Producing peer accounting / PR #108–#111:** `discovered/connected/handshaked/windowUseful/unchoked/producing`, media bytes/rate/freshness, persistent diagnostics и post-output provenance.
3. ✅ **Adaptive scheduler / PR #112–#120:** buffer pressure, authoritative reader ownership, request depth, refill/replacement, stable-ready discovery shutdown и bounded recovery.
4. ✅ **Player boundary / PR #121–#122:** first localhost open/read, Media3 BUFFERING/READY/first-frame/rebuffer telemetry и bounded P2P-specific LoadControl.
5. 🚧 **V4c / PR #123:** output discontinuity → stable TS sync → PAT/PMT → video random-access/IDR gate.
6. **V4d / startup timeline:** добавить structured timestamps `transport selection/direct/metadata → first candidate → connected → handshake → useful window → first media → buffer ready → HTTP reader lifecycle → Media3 READY/first frame`.
7. **V4d / reader-reopen:** логировать HTTP method/range/start offset, reader close reason/delivered bytes/duration и Media3 load/retry/extractor readiness, чтобы доказать причину второго reader, после которого READY наступает почти сразу.
8. **V4d / pre-READY pressure:** не считать parser/read burst безусловной playback-rate оценкой; проверить bounded pre-READY estimator на byte headroom/producer rate, сохранив post-READY authoritative consumer semantics.
9. **V4d / startup race:** проверить и устранить сериализованный 8-second direct soft window перед metadata runtime там, где metadata transport уже actionable, не ослабляя absolute startup/no-peer/stall bounds.
10. **V4d / useful peers:** один быстро найденный, но не handshaked/useful endpoint не должен блокировать ранний конкурентный DHT/alternative-peer path; не увеличивать таймауты вместо peer-quality оптимизации.
11. Разделить unavailable source, dead/stale swarm, insufficient throughput/buffer, transport timeout и decoder/demux error в экспортируемой диагностике.
12. Сохранять ordinary BitTorrent regression baseline: magnet/infohash/local/HTTP torrent, read-ahead, seek и local HTTP Range.
13. После V4d провести fixed same-device A/B матрицу обычных IPTV и Torrent TV каналов: cold/warm start, 20 переключений, повторное открытие, слабая сеть, потеря peers, двухчасовой и восьмичасовой просмотр.

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
3. 🚧 Catalog navigation имеет встроенную краткую Help-карточку в About; отдельный top-level `Помощь / Инструкция` для всех функций остаётся задачей #43.
4. 🚧 `docs/USER_GUIDE.md` создан и покрывает catalog navigation; `docs/TROUBLESHOOTING.md`, `docs/REMOTE_AND_MOUSE.md` и дополнительное тематическое покрытие добавлять по мере стабилизации остальных экранов.
5. 🚧 README, ROADMAP и architecture синхронизируются с фактическим canonical catalog поведением в текущем docs/help PR; testing/release docs обновлять при изменении соответствующих acceptance contracts.

## Issue #40: кодовая baseline завершена, ручная TV-приёмка продолжается

1. Не менять `ScannerViewModel`, провайдеры и поисковую логику без отдельной причины.
2. `ScannerScreen` использует общий `TvScrollableLazyColumn`: видимые кнопки в начало/Page Up/Page Down и аппаратные PageUp/PageDown/ChannelUp/ChannelDown.
3. Штатную прокрутку колесом мыши/тачпадом и scrollbar drag сохранить без изменения query/provider/search/import semantics.
4. Общий TV-focus modifier запрашивает вывод сфокусированной карточки/standalone action в видимую область.
5. Sections D-pad regression compile проверяется Android CI; реальное поведение D-pad/mouse/Back/Exit дополнительно проверяется в BlueStacks/TV Box.
6. Любой воспроизводимый дефект из ручного тестирования исправляется отдельным минимальным hotfix PR от свежего `main`, после чего разработка возвращается к текущему roadmap этапу.

## Приёмка перед стабильным релизом

1. Проверить D-pad, оптическую мышь и тачпад на BlueStacks 5, слабом и среднем TV Box.
2. Проверить canonical catalog на реальном устройстве: breadcrumb, one-level Back, exact-channel launch и возврат focus после Player/refresh длинного списка.
3. Проверить готовые списки и Media3 → LibVLC fallback на реальных потоках.
4. Проверить rapid-zap обычного IPTV на 256-MiB/аналогичном heap: malformed/large EPG не должен вызывать OOM/crash.
5. Проверить magnet/torrent и реальные Torrent TV источники через **только встроенный** P2P engine; отсутствие внешнего Ace Engine является нормальной тестовой конфигурацией.
6. До broad acceptance закрыть V4d startup/zap blocker: фиксировать полный startup timeline, HTTP reader reopen reason и Media3 load/retry path; здоровые same-device A/B каналы должны стремиться к диапазону 2–4 s и не иметь систематически худшую успешность запуска относительно benchmark.
7. Проверить, что Torrent TV live-буфер продолжает пополняться после старта, а producer/consumer rate, buffer seconds, rebuffer и stall измеряются; pre-READY parser burst не должен ошибочно считаться устойчивой playback consumption rate.
8. Выполнить серию из 20 переключений без зависшей старой P2P-сессии; долгое ожидание должно завершаться ограниченной ошибкой.
9. Проверить деградацию одного/нескольких producing peers: playback либо восстанавливается в bounded budget, либо выдаёт точную причину без бесконечного retry.
10. Проверить EPG на нескольких независимых XMLTV/провайдерских источниках, включая malformed и oversized fixture.
11. Выполнить минимум двухчасовой тест непрерывного просмотра.
12. Выполнить восьмичасовой soak-тест перед стабильным релизом.
13. Проверить слабую сеть: 2–5 Мбит/с, задержка 120–250 мс, потеря 1–3%.
14. Подготовить production keystore, собрать подписанный APK и проверить подпись.
15. Заполнить отчёт приёмки и создать GitHub Release.

## После релиза

- сбор обратной связи по конкретным моделям TV Box;
- оптимизация проблемных кодеков, P2P-сессий и потоков по реальным логам;
- улучшение каталога готовых IPTV/EPG источников без изменения работающего сканера.