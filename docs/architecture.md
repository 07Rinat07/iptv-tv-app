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

У канала намеренно существуют два разных уровня идентичности. `ChannelStableIdentity` отвечает на вопрос «это тот же логический телеканал?» и может совпадать между разными плейлистами/источниками. `CatalogNodeId` отвечает на вопрос «это тот же узел в конкретной иерархии?» и остаётся parent/provenance-scoped. Существующий `GlobalFavoriteIdentity` в `core:data` является compatibility adapter и делегирует общий алгоритм `ChannelStableIdentity`, поэтому Favorites и будущие catalog adapters не развивают две независимые схемы дедупликации.

Адаптер конкретного источника обязан передавать уже нормализованный `stableKey`. Политика Room persistence/migration, unified Favorites storage и UI-навигация намеренно выполняются следующими изолированными PR. Это позволяет менять storage/UI без изменения самой идентичности каталога.

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
