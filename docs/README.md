# Документация Rinat IPTV

Этот каталог содержит долговечную документацию проекта: архитектурные контракты, пользовательские инструкции, правила диагностики, тестирования и релиза.

Актуальный execution-state не дублируется в датированных Markdown-снимках. Точные `main`/branch SHA, открытые задачи, field evidence, CI и решение о следующем инкременте фиксируются в GitHub Issues, Pull Requests и Actions. Это предотвращает расхождение документации с фактическим состоянием репозитория.

## Основное

- [`USER_GUIDE.md`](USER_GUIDE.md) — пользовательские сценарии и управление приложением.
- [`REMOTE_AND_MOUSE.md`](REMOTE_AND_MOUSE.md) — D-pad, клавиатура, мышь/тачпад и focus boundaries.
- [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — диагностика импорта, Scanner, Player, EPG, сети и P2P.
- [`architecture.md`](architecture.md) — модули, границы ответственности, каталог и навигация.
- [`ROADMAP.md`](ROADMAP.md) — устойчивые приоритеты, release gates и workflow разработки.
- [`tv-apk-installation.md`](tv-apk-installation.md) — установка APK на TV/TV Box.

## Playback / P2P

- [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md) — runtime invariants, lifecycle, scheduling, ownership и acceptance rules.
- [`P2P_CONTENT_TRANSPORT.md`](P2P_CONTENT_TRANSPORT.md) — границы `content_id`, BitTorrent transport, Ace Live и compatibility routing.
- [`ACE_LIVE_STARTUP_TIMELINE.md`](ACE_LIVE_STARTUP_TIMELINE.md) — канонические диагностические startup milestones и их интерпретация.
- [`PLAYBACK_COMPATIBILITY_MATRIX.md`](PLAYBACK_COMPATIBILITY_MATRIX.md) — проверяемая совместимость контейнеров, кодеков и playback backends.
- [`integrations/acestream.md`](integrations/acestream.md) — compatibility-интеграция Ace Stream.

## EPG / archive

- [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md) — bounded persistent EPG cache contract для plain XMLTV и raw GZIP XMLTV.
- [`CATCHUP_ARCHIVE_CONTRACT.md`](CATCHUP_ARCHIVE_CONTRACT.md) — правила реального catch-up/archive playback.
- Актуальный функциональный EPG/catch-up scope ведётся в GitHub Issue #47, а не в отдельном датированном source-plan файле.

## Feature, testing и release docs

- [`features/player.md`](features/player.md) — Player behavior и пользовательский сценарий.
- [`features/scanner-import-editor.md`](features/scanner-import-editor.md) — Scanner, импорт и редактор.
- [`features/diagnostics.md`](features/diagnostics.md) — diagnostics/export/reset.
- [`testing/device-acceptance.md`](testing/device-acceptance.md) — real-device acceptance.
- [`testing/p2p-channel-availability.md`](testing/p2p-channel-availability.md) — правила отображения P2P availability.
- [`testing/playback-latency-baseline.md`](testing/playback-latency-baseline.md) — инструкция к воспроизводимому latency analyzer.
- [`release/release-process.md`](release/release-process.md) — release workflow.
- [`release/device-acceptance-report-template.md`](release/device-acceptance-report-template.md) — шаблон device acceptance.
- [`development/ai-tools.md`](development/ai-tools.md) — вспомогательные инструменты разработки.
- [`legal/`](legal/) — уведомления и лицензии сторонних компонентов.

## Правило для новых документов

Не добавлять в `docs/` одноразовые handoff/status/field-report файлы с точным SHA, датой прогона или активной веткой. Field evidence прикладывать к соответствующему Issue/PR либо сохранять как Actions artifact; в документации оставлять только повторяемый контракт, процедуру или архитектурное решение.