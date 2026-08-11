# Архитектура

Проект разделён на Android-модули:

- `app` — навигация и сборка приложения;
- `core/model` — модели данных и стабильные межмодульные контракты;
- `core/domain` — интерфейсы репозиториев;
- `core/data` и `core/database` — хранение и реализация репозиториев;
- `core/network` — сетевые источники;
- `core/player` и `core/player-vlc` — Media3 и LibVLC;
- `core/engine` — Ace Stream compatibility/transport integration;
- `core/p2p` — встроенный BitTorrent/P2P backend;
- `feature/*` — независимые экраны и сценарии;
- `sync` — фоновые обновления.

Главный экран `feature/home` отвечает за быстрый выбор сохранённого или готового списка. Текущие feature-модули ещё используют существующие локальные `playlistId/channelId`; их постепенная миграция на каноническую иерархию выполняется отдельными PR в рамках Issue #45.

## Каноническая модель каталога

Целевая иерархия проекта:

`Source/Catalog -> Subcatalog -> Playlist/List -> Group/Subgroup -> Channel`.

Фундаментальный контракт расположен в `core/model` и состоит из:

- `CatalogNodeKind` — тип узла иерархии;
- `CatalogOriginKind` — происхождение данных: user import, ready catalog, Scanner import, provider, local, P2P или system;
- `CatalogProvenance` — стабильная ссылка на источник без паролей, токенов и credential-bearing URL;
- `CatalogNodeId` / `CatalogNodeIdFactory` — детерминированная identity, не зависящая от Room auto-generated id и отображаемого названия;
- `CanonicalCatalogNode` — минимальный контракт `id + kind + name + parentId + order + provenance`;
- `ChannelStableIdentity` — единый provenance-agnostic logical key канала для dedup/Favorites с совместимым приоритетом `tvg-id -> normalized name -> normalized URL`.

У канала намеренно существуют два разных уровня идентичности. `ChannelStableIdentity` отвечает на вопрос «это тот же логический телеканал?» и может совпадать между разными плейлистами/источниками. `CatalogNodeId` отвечает на вопрос «это тот же узел в конкретной иерархии?» и остаётся parent/provenance-scoped. Существующий `GlobalFavoriteIdentity` в `core:data` является compatibility adapter и делегирует общий алгоритм `ChannelStableIdentity`, поэтому Favorites и catalog adapters не развивают две независимые схемы дедупликации.

`Playlist` и таблица `playlists` хранят `catalogOrigin`. Однозначные provider/file источники получают `PROVIDER`/`LOCAL`; новые импорты из Ready Catalog и Scanner явно записывают `READY_CATALOG`/`SCANNER_IMPORT`. Остальные старые записи остаются `USER_IMPORT`: существующие URL нельзя ретроспективно и надёжно разделить на ручной URL, Ready Catalog и Scanner import.

`LegacyPlaylistCatalogAdapter` переводит текущие `Playlist + Channel` в первый рабочий canonical tree без изменения feature-экранов. Он строит `SOURCE -> PLAYLIST -> GROUP? -> CHANNEL`, использует `ChannelStableIdentity` для channel stable key, а legacy Room `channelId` оставляет только как payload lookup. Несколько concrete rows одного логического канала сохраняются в `channelVariantIdsByNodeId`, поэтому dedup не уничтожает варианты источника. Playlist rename не меняет canonical identity.

Для legacy source provenance адаптер создаёт только opaque SHA-256 key: URI user-info исключается, password/token/MAC-подобные query values редактируются до хеширования. Для `inline`-импорта, у которого исторически нет внешнего стабильного source id, playlist identity дополнительно получает детерминированный fingerprint набора `ChannelStableIdentity`. Это compatibility-путь для существующих данных, а не замена будущих source-specific IDs.

Явные import adapters для Ready Catalog и Scanner передают origin через `PlaylistRepository`, а `LegacyPlaylistCatalogAdapter` включает его в provenance каждого узла. Provider account IDs, navigation skeleton и Unified Favorites storage выполняются следующими изолированными PR. Это позволяет мигрировать UI постепенно, не меняя уже зафиксированную identity-модель.

## P2P / Ace transport boundary

`core:p2p` обрабатывает только подтверждённый BitTorrent transport: magnet, infohash и `.torrent`. Чистый `acestream://content_id` не считается BitTorrent infohash.

Для автономного Ace bootstrap введён отдельный `AceContentIdDhtKey`: только валидный 20-byte/40-hex Content ID может использоваться как DHT lookup target. Этот тип намеренно не является ни `AceLiveSwarmKey`, ни BitTorrent infohash. На BEP-5 wire значение помещается в стандартное поле `info_hash`, но его доменная семантика не меняется и из него не строится `magnet:?xt=urn:btih:...`.

`core:engine` содержит `AceContentTransportResolver`, который переводит transport metadata в безопасный non-live BitTorrent, Ace Live transport либо unsupported transport. `core:p2p` самостоятельно разрешает публичный `content_id`, выполняет DHT/tracker discovery, подписанное рукопожатие, распознаёт live-window, собирает chunks/pieces и отдаёт MPEG-TS через локальный HTTP stream. Live transport не передаётся стандартному libtorrent только на основании наличия 40-символьного идентификатора.

Peer-wire слой поддерживает стандартный HAVE, расширенный HAVE со stream index, отдельный stream HAVE и ограниченно разбираемый compact live status. Runtime держит ограниченный скользящий output buffer, начинает выдачу после стартового порога, ограничивает число peers и запросов и применяет watchdog для media stall. Retry со стороны Player пересоздаёт всю P2P-сессию, чтобы не переиспользовать остановившийся loopback URL.

Автоматический внешний Ace fallback для Torrent TV `content_id` и live infohash отключён: успешный тест такого канала должен доказывать работу собственного runtime. AIDL/HTTP integration остаётся изолированным compatibility-кодом для явно неподдерживаемых legacy descriptor, но не скрывает сбой автономного маршрута.

Текущий архитектурный блокер находится уже не в наличии маршрута, а в playback hardening: быстрое переключение, стабильная подкачка live-буфера, классификация недоступных swarm и длительная аппаратная приёмка. Актуальные результаты и критерии собраны в `PLAYBACK_STATUS.md`.

## Порядок зависимостей

1. завершить кодовую regression-baseline TV navigation (#40), а реальную BlueStacks/TV Box приёмку вести параллельно;
2. стабилизировать canonical catalog identity/provenance и unified Favorites (#45);
3. завершить P2P playback hardening: переключение, непрерывная подкачка, stall recovery и аппаратная приёмка (#44);
4. построить EPG/Now-Next/real archive поверх стабильной channel identity (#47);
5. переработать Player UX поверх готовых Catalog + P2P + EPG contracts (#46);
6. завершить contextual Help и пользовательскую документацию после стабилизации экранов (#43);
7. выполнить hardware/soak/release gate и только после этого закрыть master-roadmap #44.

Защищённые области при UI/плеерных изменениях:

- `feature/scanner/**`;
- `core/network/**`;
- `ScannerRepository`;
- `SearchPlaylistsUseCase`;
- логика ранжирования и фильтрации результатов поиска.
