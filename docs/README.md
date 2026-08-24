# Документация Rinat IPTV

В этом каталоге находятся актуальные архитектурные документы, текущий план, пользовательское руководство и датированные field-evidence материалы. Датированные отчёты сохраняются как исторические свидетельства и не переписываются под более новое состояние проекта.

## С чего начинать

- [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md) — **канонический текущий статус проекта, активный blocker, merge gates и порядок следующих решений**. Если старый датированный roadmap называет другой «текущий increment», приоритет имеет этот файл.
- [`PLAYER_DASHBOARD_AND_PLAYBACK_COMPATIBILITY_PLAN.md`](PLAYER_DASHBOARD_AND_PLAYBACK_COMPATIBILITY_PLAN.md) — подтверждённая после PR #209 последовательность: mechanical Player shell split → Home/Player TV dashboard (#210) → dashboard/fullscreen без перезапуска session → EPG/Favorites polish → multicodec/Media3→LibVLC hardening (#211) → TV Box codec matrix.
- [`USER_GUIDE.md`](USER_GUIDE.md) — пользовательское управление, «Мои плейлисты», canonical catalog, durable unified Favorites, системный virtual Favorites list, Back/focus restore, Player и базовая диагностика.
- [`REMOTE_AND_MOUSE.md`](REMOTE_AND_MOUSE.md) — подробный контракт D-pad, клавиатуры, мыши/тачпада и production Player input/focus boundaries.
- [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — диагностика импорта, Scanner, Player, EPG/archive, Favorites, сети, TV-input и evidence-driven P2P проблем без ослабления runtime safety gates.
- [`ROADMAP.md`](ROADMAP.md) — длинная история выполненных и следующих этапов проекта.
- [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md) — подтверждённый статус IPTV/Torrent TV, известные проблемы и критерии готовности.

## Catalog / Favorites

- [`architecture.md`](architecture.md) — canonical hierarchy, navigation runtime, logical channel identity и границы каталог/Favorites/P2P.
- [`USER_GUIDE.md`](USER_GUIDE.md) — фактический пользовательский сценарий: ordinary playlists, системное `Избранное`, сохранение favorites после удаления источника и текущий export scope.
- `PROJECT_STATUS_AND_ROADMAP.md` — текущая последовательность Issue #45: PR #174–#179 закрыли portable backup/import/export и preferred-source picker, All Channels + Recent завершены в `03f8e8e`, focus hot path — в `90a285c`, off-Main latest-wins rebuild — в production `2d22aea`.

## Ace Live / P2P

- [`ACE_LIVE_IMPLEMENTATION_PLAN.md`](ACE_LIVE_IMPLEMENTATION_PLAN.md) — подробная история clean-room Ace Live protocol/runtime инкрементов и архитектурные ограничения.
- [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md) — архитектурная цель: media-producing peers, adaptive prebuffer/scheduler, player boundary и метрики качества.
- [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md) — runtime invariants, timeout ownership, buffering и acceptance rules.
- [`P2P_CONTENT_TRANSPORT.md`](P2P_CONTENT_TRANSPORT.md) — границы `content_id`, BitTorrent transport и Ace Live.
- [`ACE_LIVE_STARTUP_TIMELINE.md`](ACE_LIVE_STARTUP_TIMELINE.md) — канонические startup milestones и диагностические границы.
- [`ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md`](ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md) — lifecycle reason telemetry для `connected / handshake / disconnect` evidence.
- [`ROADMAP_V4D_FIELD_2026-08-16.md`](ROADMAP_V4D_FIELD_2026-08-16.md) — исторический R2/R3 V4d field execution record до/включая PR #134; его раздел «текущий increment» после 2026-08-17 считается superseded каноническим статусом выше.
- `ACE_LIVE_FIELD_VALIDATION_*.md` и [`ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md) — датированные field-evidence и процедура повторяемой проверки; хранить как evidence, а не как текущий roadmap.
- [`ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md`](ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md) — clean-room/reference findings; внешние реализации не являются runtime dependency проекта.

## Остальная документация

- [`architecture.md`](architecture.md) — структура модулей, canonical catalog runtime и границы ответственности.
- [`features/player.md`](features/player.md) — стартовый сценарий и плеер.
- [`features/scanner-import-editor.md`](features/scanner-import-editor.md) — сканер, импорт и редактор.
- [`features/diagnostics.md`](features/diagnostics.md) — логи, экспорт и сброс ошибок.
- [`features/EPG_SOURCE_INTEGRATION_PLAN.md`](features/EPG_SOURCE_INTEGRATION_PLAN.md) — встроенный каталог EPG/пиконов, XMLTV/GZIP/JTV adapters, matching и UX-референсы.
- [`integrations/acestream.md`](integrations/acestream.md) — compatibility-интеграция Ace Stream; не является целевым Torrent TV backend/fallback.
- [`testing/device-acceptance.md`](testing/device-acceptance.md) — проверка на TV Box.
- [`testing/playback-log-analysis-2026-08-12.md`](testing/playback-log-analysis-2026-08-12.md) — разбор EPG OOM на обычном IPTV и критерии memory-safety hotfix.
- [`testing/playback-log-analysis-2026-08-11.md`](testing/playback-log-analysis-2026-08-11.md) — исторический разбор долгих переключений, timeout, состояния UI, EPG и отсутствующих buffer-метрик.
- [`testing/playback-log-analysis-2026-08-20.md`](testing/playback-log-analysis-2026-08-20.md) — свежий разбор fixed handoff race, production-lifetime DHT, stale `SEARCHING` и отдельного Media3/TS blocker.
- [`release/release-process.md`](release/release-process.md) — подготовка релиза.

## Служебное

- [`development/ai-tools.md`](development/ai-tools.md) — офлайн-инструменты индексации.
- [`archive/development-stages.md`](archive/development-stages.md) — краткая история завершённых этапов.
- [`legal/`](legal/) — уведомления сторонних компонентов.
