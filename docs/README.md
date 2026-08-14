# Документация Rinat IPTV

Здесь находятся только актуальные документы проекта.

## Основное

- [`ROADMAP.md`](ROADMAP.md) — выполненные и следующие этапы; автономный Ace Live engine является текущим главным playback-приоритетом.
- [`PLAYBACK_STATUS.md`](PLAYBACK_STATUS.md) — подтверждённый статус IPTV/Torrent TV, известные проблемы и критерии готовности.
- [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md) — текущая архитектурная цель: media-producing peers, adaptive prebuffer/scheduler, player boundary и метрики качества.
- [`ACE_LIVE_IMPLEMENTATION_PLAN.md`](ACE_LIVE_IMPLEMENTATION_PLAN.md) — подробная история clean-room Ace Live protocol/runtime инкрементов и оставшиеся автономные задачи.
- [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md) — runtime invariants, timeout ownership, buffering и acceptance rules.
- [`P2P_CONTENT_TRANSPORT.md`](P2P_CONTENT_TRANSPORT.md) — границы content-id, BitTorrent transport и Ace Live.
- [`architecture.md`](architecture.md) — структура модулей и границы ответственности.
- [`features/player.md`](features/player.md) — стартовый сценарий и плеер.
- [`features/scanner-import-editor.md`](features/scanner-import-editor.md) — сканер, импорт и редактор.
- [`features/diagnostics.md`](features/diagnostics.md) — логи, экспорт и сброс ошибок.
- [`features/EPG_SOURCE_INTEGRATION_PLAN.md`](features/EPG_SOURCE_INTEGRATION_PLAN.md) — встроенный каталог EPG/пиконов, XMLTV/GZIP/JTV adapters, matching и UX-референсы.
- [`integrations/acestream.md`](integrations/acestream.md) — compatibility-интеграция Ace Stream; не является целевым Torrent TV backend/fallback.
- [`testing/device-acceptance.md`](testing/device-acceptance.md) — проверка на TV Box.
- [`testing/playback-log-analysis-2026-08-12.md`](testing/playback-log-analysis-2026-08-12.md) — разбор EPG OOM на обычном IPTV и критерии memory-safety hotfix.
- [`testing/playback-log-analysis-2026-08-11.md`](testing/playback-log-analysis-2026-08-11.md) — исторический разбор долгих переключений, timeout, состояния UI, EPG и отсутствующих buffer-метрик.
- [`release/release-process.md`](release/release-process.md) — подготовка релиза.

## Служебное

- [`development/ai-tools.md`](development/ai-tools.md) — офлайн-инструменты индексации.
- [`archive/development-stages.md`](archive/development-stages.md) — краткая история завершённых этапов.
- [`legal/`](legal/) — уведомления сторонних компонентов.
