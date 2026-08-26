# Документация Rinat IPTV

В этом каталоге находятся актуальные архитектурные документы, текущий план, пользовательское руководство и датированные field-evidence материалы. Датированные отчёты сохраняются как исторические свидетельства и не переписываются под более новое состояние проекта.

## С чего начинать

- [`CURRENT_DEVELOPMENT_HANDOFF.md`](CURRENT_DEVELOPMENT_HANDOFF.md) — **точка входа для следующей сессии разработки: integration baseline, открытые gates, порядок анализа новых diagnostics/скриншотов и следующий focused increment**.
- [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md) — канонический текущий статус проекта, active blocker, merge gates и порядок следующих решений.
- [`ROADMAP.md`](ROADMAP.md) — технический порядок разработки и field-validation loop.
- [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md) — следующий отдельный EPG infrastructure increment: bounded memory/disk XMLTV cache, cold-start reuse, conditional HTTP, stale fallback, atomic writes и TV Box acceptance.
- [`PLAYER_DASHBOARD_AND_PLAYBACK_COMPATIBILITY_PLAN.md`](PLAYER_DASHBOARD_AND_PLAYBACK_COMPATIBILITY_PLAN.md) — TV-first Player/Home visual and playback contract.
- [`USER_GUIDE.md`](USER_GUIDE.md) — пользовательское управление, плейлисты, Favorites, Player и базовая диагностика.
- [`REMOTE_AND_MOUSE.md`](REMOTE_AND_MOUSE.md) — D-pad, клавиатура, мышь/тачпад и Player focus boundaries.
- [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — диагностика импорта, Scanner, Player, EPG/archive, Favorites, сети и P2P.
- [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md) — подтверждённый статус IPTV/Torrent TV, известные проблемы и критерии готовности.

## Catalog / Favorites

- [`architecture.md`](architecture.md) — canonical hierarchy, navigation runtime, logical channel identity и границы каталог/Favorites/P2P.
- [`USER_GUIDE.md`](USER_GUIDE.md) — ordinary playlists, системное `Избранное`, recent/all virtual views и пользовательские сценарии.
- [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md) — актуальные memory/catalog recovery gates.

## Ace Live / P2P

- [`ACE_LIVE_IMPLEMENTATION_PLAN.md`](ACE_LIVE_IMPLEMENTATION_PLAN.md) — история clean-room Ace Live protocol/runtime инкрементов и архитектурные ограничения.
- [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md) — media-producing peers, adaptive prebuffer/scheduler, player boundary и quality metrics.
- [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md) — runtime invariants, timeout ownership, buffering и acceptance rules.
- [`P2P_CONTENT_TRANSPORT.md`](P2P_CONTENT_TRANSPORT.md) — границы `content_id`, BitTorrent transport и Ace Live.
- [`ACE_LIVE_STARTUP_TIMELINE.md`](ACE_LIVE_STARTUP_TIMELINE.md) — startup milestones и диагностические границы.
- [`ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md`](ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md) — lifecycle reason telemetry для `connected / handshake / disconnect` evidence.
- [`ROADMAP_V4D_FIELD_2026-08-16.md`](ROADMAP_V4D_FIELD_2026-08-16.md) — исторический V4d field execution record; current decisions берутся из canonical status/handoff выше.
- `ACE_LIVE_FIELD_VALIDATION_*.md` и [`ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md) — датированные field-evidence и процедуры повторяемой проверки.
- [`ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md`](ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md) — clean-room/reference findings; внешние реализации не являются runtime dependency проекта.

## EPG

- [`features/EPG_SOURCE_INTEGRATION_PLAN.md`](features/EPG_SOURCE_INTEGRATION_PLAN.md) — EPG source catalog, XMLTV/GZIP/JTV adapters, matching и UX references.
- [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md) — persistent bounded XMLTV cache contract для process restart/network-load reduction без увеличения heap.
- [`CURRENT_DEVELOPMENT_HANDOFF.md`](CURRENT_DEVELOPMENT_HANDOFF.md) — текущий порядок EPG recovery -> time/refresh -> disk cache -> Player programme UX.

## Остальная документация

- [`architecture.md`](architecture.md) — структура модулей и границы ответственности.
- [`features/player.md`](features/player.md) — стартовый сценарий и плеер.
- [`features/scanner-import-editor.md`](features/scanner-import-editor.md) — сканер, импорт и редактор.
- [`features/diagnostics.md`](features/diagnostics.md) — логи, экспорт и сброс ошибок.
- [`integrations/acestream.md`](integrations/acestream.md) — compatibility-интеграция Ace Stream; не является целевым Torrent TV backend/fallback.
- [`testing/device-acceptance.md`](testing/device-acceptance.md) — проверка на TV Box.
- [`testing/playback-log-analysis-2026-08-12.md`](testing/playback-log-analysis-2026-08-12.md) — исторический разбор EPG OOM и memory-safety criteria.
- [`testing/playback-log-analysis-2026-08-11.md`](testing/playback-log-analysis-2026-08-11.md) — исторический разбор долгих переключений, timeout и EPG.
- [`testing/playback-log-analysis-2026-08-20.md`](testing/playback-log-analysis-2026-08-20.md) — handoff race, production-lifetime DHT, stale `SEARCHING` и Media3/TS evidence.
- [`release/release-process.md`](release/release-process.md) — подготовка релиза.
- [`archive/development-stages.md`](archive/development-stages.md) — краткая история завершённых этапов.
- [`legal/`](legal/) — уведомления сторонних компонентов.
