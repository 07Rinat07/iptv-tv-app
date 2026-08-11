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

`core:engine` содержит отдельный `AceContentTransportResolver`. Он переводит transport metadata в одно из трёх решений: безопасный non-live BitTorrent для embedded libtorrent, Ace Live transport, либо unsupported transport. Текущая compatibility-реализация получает metadata через внешний Ace Engine API; embedded resolver добавляется перед ней поэтапно, без изменения player-facing routing. Live transport намеренно не пропускается в стандартный libtorrent только на основании наличия `infohash`.

Следующий сетевой шаг после Content-ID DHT identity/codec — отдельный lookup/discovery и transport-metadata bootstrap. Только после верификации transport metadata обычный BT infohash может перейти в libtorrent; Ace Live transport должен использовать существующие Ace Live peer/discovery/window/reassembly компоненты.

Это разделение введено после реальной регрессии Torrent-TV, где `acestream://content_id` ошибочно выглядел как «embedded» сценарий, хотя metadata всё ещё требовала внешний Ace Engine. До завершения собственного resolver/backend внешний Engine остаётся optional compatibility fallback, а не обязательной частью стандартного BitTorrent пути.

## Порядок зависимостей

1. завершить кодовую regression-baseline TV navigation (#40), а реальную BlueStacks/TV Box приёмку вести параллельно;
2. стабилизировать canonical catalog identity/provenance и unified Favorites (#45);
3. завершить P2P transport contracts/Ace content-id и Ace Live поверх стабильного source provenance (#44);
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
