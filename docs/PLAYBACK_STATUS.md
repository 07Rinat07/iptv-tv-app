# Статус воспроизведения

_Актуализирован: 25 августа 2026 после ручного теста._

Подробный полевой baseline: [`FIELD_VALIDATION_2026-08-25.md`](FIELD_VALIDATION_2026-08-25.md).

## Обычный IPTV

Статус: **частично подтверждён рабочим на реальном устройстве**.

В diagnostics есть успешные `first_video_frame` и READY для Media3:
- H.264 + AAC;
- H.264 + MP2;
- 720p/1080p варианты;
- несколько сессий без rebuffer.

На скриншотах также подтверждены реальные случаи видео через LibVLC fallback.

Вывод: ordinary IPTV playback stack не является текущей целью полного переписывания. Исправления должны быть локальными и измеримыми.

## Player UX

Статус: **P0 не принят** — issue #231.

Маршрутизация ведёт в `StablePlayerScreen`, но ручной тест не показал ожидаемый новый TV-first Player. Видимый интерфейс остаётся legacy-looking.

Следующая цель после memory/Home: новый production Player должен быть очевидно видимым и сохранять одну playback session между dashboard/fullscreen.

## Embedded Torrent TV / Ace Live

Статус: **P0 не принят** — issue #232.

Same-device comparison с Televizo + Ace Stream Engine показывает существенно худшую успешность поиска/подключения peers.

Зафиксированы классы отказа:
- tracker candidate, `connected=0`;
- DHT находит peers, TCP соединяется, но `handshaked=0`;
- peer найден, но media не приходит;
- `p2p_stalled` после начала playback;
- generic `P2P-поток не подготовлен`.

Одновременно есть несколько успешных P2P boundary sessions с READY/first audio/first video frame. Это значит, что после появления media player boundary способен работать.

### Обязательный порядок диагностики/исправления

`discovery -> connect -> handshake -> metadata/live-window -> selected -> sent -> chunk_ingress -> accepted -> piece_completed -> authenticated -> ts_resync_output -> media_appended -> player READY/frame/audio`

Исправлять нужно первую отсутствующую стадию конкретного запуска.

Запрещено:
- объявлять канал нераздающимся, если тот же канал работает в same-device Ace Stream benchmark;
- увеличивать глобальные timeouts/buffers как замену отсутствующему protocol progress;
- добавлять внешний Ace Engine как обязательный fallback/dependency.

## Current gates

1. #229 memory stability.
2. #230 Home root.
3. #231 Player UX.
4. #232 Torrent TV protocol parity.
5. #233 rapid playback request source.

Полный acceptance Torrent TV возвращается только после закрытия этих recovery blockers.
