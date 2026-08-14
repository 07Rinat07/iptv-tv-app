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
AceLivePeerSessionCoordinator
    ↓
chunk requests / authenticated piece reassembly
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

### Adaptive streaming architecture

Главный следующий слой строится поверх уже работающего protocol runtime:

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

`peer count` должен иметь явные стадии:

`discovered → connected → handshaked → windowUseful/unchoked → media-producing`.

Найденный tracker/DHT endpoint не считается playable peer. Scheduler в конечном состоянии должен опираться на media freshness/rate, usefulness authoritative cursor, timeout history и текущий buffer pressure.

`LiveBufferController` должен оперировать запасом в bytes и seconds, использовать `critical/low/target/high` watermarks и hysteresis. Throughput не выводится из возраста runtime или непроверенного raw descriptor bitrate. Adaptive startup v1 уже переносит throughput clock на первый media-byte и использует EWMA реального media growth.

Текущие default startup bounds остаются конечными failure guards; их увеличение не является способом улучшения производительности.

### Player boundary

P2P buffer и Media3 LoadControl являются двумя разными feedback loops. Для localhost Ace Live нужен отдельный измеримый contract: `first_media_byte`, `buffer_ready`, `http_open/read`, producer/consumer rate, Media3 `BUFFERING/READY`, first frame и rebuffer duration. Только после этого P2P-specific LoadControl настраивается отдельно от generic IPTV.

Media3 остаётся primary decoder. LibVLC используется при подтверждённой container/demux/codec несовместимости. Upstream no-peer/stall/insufficient-throughput не лечатся заменой decoder.

### Discontinuity/media format

P2P recovery уже способен выдать typed output discontinuity при подтверждённом live-window jump. MPEG-TS слой должен отдельно выполнить resync/PAT/PMT/random-access recovery и при необходимости инициировать decoder recovery. Media-format правила не должны попадать внутрь peer scheduler.

Подробный текущий план находится в [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md), runtime invariants — в [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md), фактические field results — в [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md).

## Порядок зависимостей

1. завершить кодовую regression-baseline TV navigation (#40), а реальную BlueStacks/TV Box приёмку вести параллельно;
2. довести автономный P2P/Ace Live runtime до устойчивого startup/sustained playback: adaptive prebuffer, producing peers, scheduler feedback, player/TS boundary и hardware acceptance (#44);
3. стабилизировать canonical catalog identity/provenance и unified Favorites (#45);
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
