# Changelog

Все значимые изменения проекта фиксируются в этом файле.

## Unreleased

### Added
- Стартовый экран просмотра с сохранёнными и готовыми списками каналов.
- Быстрый переход из готового списка непосредственно в плеер.
- Сброс старых ошибок и начало нового диагностического журнала.
- Автономный Ace Live маршрут для Torrent TV `content_id` и live identities без автоматического запуска внешнего Ace Engine.
- Поддержка standard/Ace live peer-wire variants, compact live status и скользящего live-window во встроенном runtime.
- Готовый список `📡 Ace Stream TV-Торрент ТВ` с 279 уникальными каналами; временная P2P-доступность больше не скрывает канал.
- Документирован adaptive streaming core: media-producing peer accounting, buffer watermarks, scheduler feedback и player-boundary telemetry.

### Changed
- Главный сценарий приложения начинается с просмотра ТВ, а сканер остаётся дополнительным разделом.
- P2P retry полностью пересоздаёт сессию, stall ограничен watchdog, а переключение канала освобождает предыдущий локальный stream.
- Playback ownership использует раздельные monotonic request/session IDs и отменяемый retry, поэтому старый `A → B → C → late retry A` не может захватить новый канал.
- LibVLC fallback ограничен ошибками container/demux/codec; сетевые/source ошибки не запускают второй заведомо бесполезный decoder.
- Снято искусственное ограничение разрешения Media3, из-за которого поддерживаемый 1080p мог отбрасываться на устройствах с небольшим Java heap.
- Ace Live discovery использует bounded startup paths и prompt cancellation; timeout bounds не увеличиваются для маскировки медленного startup.
- **Adaptive prebuffer v1:** media throughput теперь измеряется от первого media sample, а не от начала discovery; используется EWMA реального byte growth, AUTO target ориентирован на playable duration, прежний 512-KiB startup floor удалён, forced-start привязан к first-media и требует более сильный reserve.
- Диагностический экспорт включает persistent log и маркеры старта процесса; oversized EPG получает source-level backoff вместо повторной тяжёлой загрузки на каждом канале.
- Документация и roadmap закрепляют собственный Ace Live engine как главный P2P-приоритет; внешний Ace Engine не является Torrent TV fallback или release requirement.

### Known issues
- Переключение Torrent TV каналов всё ещё бывает долгим и иногда завершается bounded ошибкой.
- Discovered tracker/DHT peers ещё не равны media-producing peers; требуется отдельное peer-quality accounting.
- Некоторые resolved Ace streams долго остаются в Media3 buffering; loopback producer/consumer и first-frame telemetry ещё предстоит добавить.
- Sustained live buffer ещё не имеет полного `critical/low/target/high` feedback controller и adaptive request depth.
- MPEG-TS discontinuity/PAT/PMT/random-access recovery ещё не завершён.
- Стабильный релиз заблокирован до fixed channel matrix, weak-network/peer-loss tests и 2h/8h ARM soak.

### Removed
- Кнопка внешней отправки письма из раздела «О приложении».
- Временные CI workflow завершённых этапов.
- Дублирующие каталоги с debug APK.
- Torrent TV availability-filter/кнопка скрытия недоступных каналов.

## 0.1.0 — 2026-08-06

- Ace Stream Android Service/AIDL compatibility integration.
- Media3 с LibVLC fallback.
- Адаптивный TV-интерфейс и полноэкранный плеер.
- Навигация каналов, групп и подгрупп с фокусом и Page Up/Page Down.
- Адаптивная буферизация для слабых TV Box.
- Автоматические lint, unit, debug и release проверки.
