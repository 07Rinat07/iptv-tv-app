# Архитектура

Проект разделён на Android-модули:

- `app` — навигация и сборка приложения;
- `core/model` — модели данных и стабильные межмодульные контракты;
- `core/domain` — интерфейсы репозиториев;
- `core/data` и `core/database` — хранение и реализация репозиториев;
- `core/network` — сетевые источники;
- `core/player` и `core/player-vlc` — Media3 и LibVLC;
- `core/engine` — legacy/compatibility transport integration;
- `core/p2p` — собственные embedded BitTorrent и Ace Live backends;
- `feature/*` — независимые экраны и сценарии;
- `sync` — фоновые обновления.

Главный экран `feature/home` отвечает за быстрый выбор сохранённого или готового списка. Каталожная часть постепенно переводится с локальных `playlistId/channelId` на каноническую иерархию в рамках Issue #45. PR #167/#168 закрыли модель дерева, navigator и реальную интеграцию в `feature:playlists`; PR #170–#172 добавили autonomous unified Favorites persistence, единый playback resolver и системный virtual Favorites aggregate. Legacy lookup IDs остаются compatibility payload для существующих repository/Player API, но больше не определяют lifetime пользовательского favorite.

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

`LegacyPlaylistCatalogAdapter` переводит текущие `Playlist + Channel` в рабочий canonical tree. Он строит `SOURCE -> PLAYLIST -> GROUP? -> CHANNEL`, использует `ChannelStableIdentity` для channel stable key, а legacy Room `channelId` оставляет только как payload lookup. Несколько concrete rows одного логического канала сохраняются в `channelVariantIdsByNodeId`, поэтому dedup не уничтожает варианты источника. Playlist rename не меняет canonical identity.

Для legacy source provenance адаптер создаёт только opaque SHA-256 key: URI user-info исключается, password/token/MAC-подобные query values редактируются до хеширования. Для `inline`-импорта, у которого исторически нет внешнего стабильного source id, playlist identity дополнительно получает детерминированный fingerprint набора `ChannelStableIdentity`. Это compatibility-путь для существующих данных, а не замена source-specific IDs.

Явные import adapters для Ready Catalog и Scanner передают origin через `PlaylistRepository`, а `LegacyPlaylistCatalogAdapter` включает его в provenance каждого узла. Navigation skeleton и Unified Favorites storage больше не являются будущими пунктами: пользовательская navigation часть слита PR #167/#168, а durable Favorites/virtual aggregate — PR #170–#172.

### Canonical navigation runtime

PR #167 добавил pure navigation contract поверх canonical tree. `CanonicalCatalogNavigator` хранит текущий путь, remembered focus для уровней и восстанавливает самый глубокий валидный checkpoint после rebuild дерева. Back никогда не прыгает через несколько уровней: он возвращает к непосредственному parent, а на source root завершает внутреннюю catalog navigation.

PR #168 подключил этот контракт в `feature:playlists`:

```text
PlaylistRepository.observeChannels(playlistId)
    ↓
LegacyPlaylistCatalogAdapter
    ↓
CanonicalCatalogNavigator
    ↓
PlaylistCatalogNavigationSession / PlaylistCatalogSnapshot
    ↓
PlaylistCatalogContent
    ↓
exact player/{playlistId}/{channelId}
```

`PlaylistsViewModel` пересобирает canonical tree из существующего `observeChannels` flow и передаёт предыдущий checkpoint при refresh/re-import. Невалидный хвост пути удаляется, но валидный parent path сохраняется.

`PlaylistCatalogContent` отображает только direct children текущего узла, breadcrumb-контекст и TV-friendly list. Focus хранится по `CatalogNodeId`, а не по позиции. Перед `requestFocus()` список прокручивается к сохранённой строке, поэтому восстановление работает и для элемента вне первоначального LazyColumn viewport.

CHANNEL остаётся leaf. При активации UI сначала фиксирует canonical focus (это важно для mouse/touch, где focus callback может не прийти до click), затем передаёт concrete `channelId` в существующий Player route. После Player пользователь возвращается в прежний hierarchy checkpoint.

Верхняя кнопка приложения «Назад» направляется через Android Back dispatcher. Поэтому hardware/remote Back, верхняя кнопка и внутренний catalog Back используют один child `BackHandler` и одинаковые one-level semantics.

`:feature:playlists:testDebugUnitTest` является постоянным Android CI gate. Regression coverage проверяет начальный `SOURCE -> PLAYLIST`, enter/back round-trip группы, сохранение channel focus после rebuild, fallback при удалённой группе, leaf semantics CHANNEL и завершение Back на source root.

### Unified Favorites persistence и virtual aggregate

PR #170 перевёл Favorites с lifetime конкретной строки `channels` на user-owned logical storage database v10:

```text
favorite_channels
  logicalKey (PK)
  display/preferred snapshot
  preferred legacy lookup hints

favorite_channel_variants
  logicalKey + variantKey (PK)
  streamUrl
  original playlist/source provenance snapshot

favorite_legacy_seeds
  temporary 9→10 migration safety snapshot
```

SQL migration намеренно не пытается воспроизвести `ChannelStableIdentity`. Миграция 9→10 сначала копирует legacy `favorites JOIN channels` в raw seed snapshot, затем `core:data` консолидирует seeds точным Kotlin-алгоритмом и очищает seed table. Поэтому favorite защищён даже в окне сразу после upgrade и не зависит от последующего существования source rows.

`UnifiedFavoritesRepositoryImpl` является durable source of truth. Legacy `favorites(channelId)` остаётся compatibility mirror для ещё не переведённых consumers, но не владеет логическим favorite.

При добавлении favorite все найденные live equivalents одного `ChannelStableIdentity` сохраняются как source variants. При удалении исходного плейлиста durable snapshot/variants не удаляются. Re-import совпадающего logical channel снова делает live row доступным без создания второго favorite.

PR #171 добавил `FavoritePlaybackContext`. Resolver выбирает:

1. запрошенный live equivalent;
2. preferred live equivalent;
3. другой live equivalent;
4. persisted variant по requested/preferred id или stream;
5. durable favorite snapshot как последний fallback.

Это data-layer selection contract. Media3/LibVLC/P2P Player runtime не дублируется и не получает отдельную Favorites implementation.

PR #172 добавил `VirtualFavoritesPlaylistRepository` и стабильный отрицательный `VIRTUAL_FAVORITES_PLAYLIST_ID`. В Room физическая playlist row не создаётся. Decorator публикует `Избранное` через существующий `PlaylistRepository` contract, поэтому тот же canonical catalog и `player/{playlistId}/{channelId}` flow работают для durable favorites, включая orphan favorite после удаления исходного списка.

`FavoritesRepositoryFacade` сохраняет стабильный representative favorite ID для UI/★ compatibility, но подставляет playlist/source/stream fields выбранного live или persisted playback variant. Это важно: virtual aggregate не должен возвращать stale snapshot URL, если durable resolver уже выбрал лучший persisted variant.

В `PlaylistsScreen` virtual Favorites разрешает каталог и Player, но физические `refresh/delete/editor` actions отключены. Repository-level guards повторяют этот запрет, чтобы UI не был единственной защитой destructive semantics.

Следующие outer decorators публикуют `Все каналы`, а затем `Недавние`. Recent объединяет bounded history flow с All Channels, durable Favorites и parental settings: exact legacy ID разрешается только в доступный concrete/persisted channel, после чего применяются hidden/parental filters и `ChannelStableIdentity` dedup в детерминированном MRU-порядке. Результат ограничен 100 каналами при lookback 250; Room schema, Scanner, Player и P2P policy не меняются. Общий `virtualPlaylistContentSummary` сохраняет прежние summary contracts трёх system aggregates без дублирования расчётов.

PR #174–#176 закрыли versioned portable backup/import и безопасный interoperability export, а PR #177–#178 — reconciliation/preferred-source contract и TV picker. All Channels + Recent aggregate завершены в `main` `03f8e8e`; текущий catalog contract #45 — focus/performance hardening, затем off-Main latest-wins rebuild и aggregate flow/summary hardening. Обычный M3U/M3U8 не заменяет полный backup.

Пользовательское описание находится в [`USER_GUIDE.md`](USER_GUIDE.md), а краткая встроенная справка — в `AboutScreen`.

## P2P / Ace transport boundary

### Ownership

Torrent TV/Ace Live является собственностью embedded runtime. Внешний Ace Stream Engine не является целевым backend, автоматическим fallback или release requirement. Legacy AIDL/HTTP integration остаётся изолированным compatibility-кодом для явно совместимых сценариев, но не используется, чтобы скрыть сбой собственного Torrent TV path.

`core:p2p` содержит две разные подсистемы:

- ordinary BitTorrent — magnet, BTIH, `.torrent`, libtorrent streaming/read-ahead и local HTTP Range;
- Ace Live — отдельные identity/transport contracts, tracker/DHT discovery, peer-wire session, live scheduling/reassembly, recovery и local MPEG-TS stream.

Чистый `acestream://content_id` не считается BitTorrent infohash. `AceLiveSwarmKey`, `AceContentIdDhtKey` и ordinary BTIH имеют разные доменные роли даже если отдельные wire-протоколы используют 20-byte ключи.

### Ace Live runtime pipeline

Текущая цепочка:

```text
content/live identity
    ↓
bounded tracker + Mainline DHT discovery
    ↓
AceLiveTcpConnectionPool
    ↓
handshake + live-window + choke state
    ↓
AceLivePeerProductionTracker
    ↓
AceLivePeerSessionCoordinator
    ↓
chunk requests / contiguous piece reassembly
    ↓
AceLiveMpegTsResynchronizer
    ↓
AceLiveMediaBuffer
    ↓
LoopbackHttpLiveServer (127.0.0.1/live.ts)
    ↓
Media3 / LibVLC decoder fallback
```

Peer-wire слой поддерживает standard/observed Ace HAVE/status/window variants, bounded frame parsing и explicit ownership. Live scheduler не имеет права самостоятельно перескакивать через недостающий authoritative cursor: forward jump выполняется только через typed recovery discontinuity.

`AceLivePeerProductionTracker` является observation/quality layer, а не scheduler policy. Базовый V2 отделяет discovery от connected/handshaked/fresh media production и строит aggregate delivery snapshot. Requestability `windowUseful` вычисляется относительно authoritative `nextNeededPiece()`, `unchoked` отражает фактический peer-wire state, а producing требует fresh contiguous media при одновременно полезном window и unchoked peer.

### Adaptive streaming architecture

Главный adaptive слой строится поверх protocol runtime:

```text
PeerPool
  + MediaProducingPeerTracker
  + PeerQualityScorer
        ↓
AdaptiveLiveScheduler
        ↓
LiveBufferController
        ↓
loopback/player telemetry
```

`peer count` имеет явные стадии:

`discovered → connected → handshaked → windowUseful → unchoked → media-producing`.

Найденный tracker/DHT endpoint не считается playable peer. `windowUseful` также не означает «peer прислал любую metadata»: его advertised live-window должен содержать текущий authoritative cursor. Scheduler должен опираться на media freshness/rate, usefulness authoritative cursor, timeout history и текущий buffer pressure.

`LiveBufferController` оперирует запасом в bytes/seconds, watermarks и hysteresis. Throughput не выводится из возраста runtime или непроверенного raw descriptor bitrate. Текущие startup/failure bounds остаются конечными guards; их увеличение не является способом улучшения производительности.

### Player boundary

P2P buffer и Media3 LoadControl являются двумя разными feedback loops. Для localhost Ace Live измеряются `first_media_byte`, `buffer_ready`, `http_open/read`, producer/consumer rate, Media3 `BUFFERING/READY`, first frame и rebuffer duration.

Media3 остаётся primary decoder. LibVLC используется при подтверждённой container/demux/codec несовместимости. Upstream no-peer/stall/insufficient-throughput не лечатся заменой decoder.

### Discontinuity/media format

P2P recovery способен выдать typed output discontinuity при подтверждённом live-window jump. MPEG-TS слой отдельно выполняет resync/PAT/PMT/random-access recovery и при необходимости decoder recovery. Media-format правила не должны попадать внутрь peer scheduler.

Подробный текущий план находится в [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md), runtime invariants — в [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md), фактические field results — в [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md) и canonical project status — в [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md).

## Порядок зависимостей

1. кодовая regression-baseline TV navigation (#40) стабилизирована; реальную BlueStacks/TV Box приёмку вести параллельно;
2. P2P/Ace Live transport policy меняется только по real-device evidence Issue #159; без новой producer-stage/rapid-switch evidence не менять DHT/peer/request-depth/timeout/buffer assumptions (#44);
3. canonical catalog navigation, autonomous Favorites, portable transfer, preferred-source picker, All Channels и Recent стабилизированы до `main` `03f8e8e`; текущий изолированный этап — focus/performance hardening, затем off-Main rebuild и aggregate collector/summary hardening (#45);
4. построить EPG/Now-Next/real archive поверх стабильной channel identity (#47);
5. переработать Player UX поверх готовых Catalog + Favorites + P2P + EPG contracts (#46);
6. Favorites/source-picker user guide и встроенная Help синхронизированы PR #179; contextual Help остальных экранов продолжает задачу #43;
7. выполнить hardware/soak/release gate и только после этого закрыть master-roadmap #44.

Защищённые области при catalog/Favorites изменениях:

- `feature/scanner/**`;
- `core/network/**` Scanner discovery/query behavior;
- `ScannerRepository`;
- `SearchPlaylistsUseCase`;
- логика ранжирования и фильтрации Scanner results;
- P2P DHT/peer/request-depth/timeout/buffer policy без новой hardware evidence.
