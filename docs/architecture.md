# Архитектура

Проект разделён на Android-модули:

- `app` — навигация и сборка приложения;
- `core/model` — модели данных;
- `core/domain` — интерфейсы репозиториев;
- `core/data` и `core/database` — хранение и реализация репозиториев;
- `core/network` — сетевые источники;
- `core/player` и `core/player-vlc` — Media3 и LibVLC;
- `core/engine` — Ace Stream;
- `feature/*` — независимые экраны и сценарии;
- `sync` — фоновые обновления.

Главный экран `feature/home` отвечает за быстрый выбор сохранённого или готового списка. После выбора навигация передаёт `playlistId` в `feature/player`.

Защищённые области при UI/плеерных изменениях:

- `feature/scanner/**`;
- `core/network/**`;
- `ScannerRepository`;
- `SearchPlaylistsUseCase`;
- логика ранжирования и фильтрации результатов поиска.
