# Ace Live adaptive streaming core

## Цель

Собственный Ace Live runtime является главным P2P backend проекта. Цель — не зависеть от внешнего Ace Stream Engine и довести автономное воспроизведение до измеримо быстрого и стабильного поведения на реальных Android TV / TV Box.

Сторонние приложения и движки используются только как A/B benchmark на тех же каналах и устройстве. Их наличие не является prerequisite, fallback или критерием успешной работы Rinat IPTV.

## Почему этот этап приоритетный

Полевой лог 14 августа показал сразу несколько разных проблем одного pipeline:

- tracker/DHT иногда возвращает несколько endpoints, но это не гарантирует media-producing peer;
- встречаются `peers=4..7` и последующий no-peer/absolute startup failure;
- отдельные Ace streams успешно resolve, после чего Media3 достигает READY примерно через 66 секунд;
- другие resolved streams достигают READY за сотни миллисекунд, поэтому массовую проблему нельзя объяснить только отсутствующим codec;
- старая AUTO startup-buffer формула включала discovery/handshake latency в media-throughput estimate и могла открыть HD stream с недостаточным запасом.

Следовательно оптимизировать нужно всю feedback-цепочку, а не один таймаут:

`discovery → useful peers → media throughput → live buffer → loopback consumer → Media3`.

## Архитектурная цель

```text
Ace content/live identity
        ↓
bounded tracker + DHT discovery
        ↓
PeerPool
        ↓
MediaProducingPeerTracker + PeerQualityScorer
        ↓
AdaptiveLiveScheduler
        ↓
authenticated chunk/piece reassembly
        ↓
LiveBufferController
        ↓
MPEG-TS resync / discontinuity gate
        ↓
127.0.0.1/live.ts
        ↓
P2P-specific Media3 LoadControl
```

### Peer lifecycle

`peer count` больше не должен быть одной неоднозначной цифрой. Runtime должен различать:

1. `discovered` — endpoint найден tracker/DHT;
2. `connected` — TCP transport установлен;
3. `handshaked` — Ace handshake принят;
4. `windowUseful` — peer рекламирует live-window, полезный для authoritative cursor;
5. `unchoked` — peer разрешает запросы;
6. `producing` — peer недавно реально дал accepted/contiguous media bytes.

Для каждого producing peer нужны как минимум freshness, delivered bytes/rate, timeout/error history и usefulness текущему cursor.

## Buffer model

### Уже в adaptive prebuffer v1

- throughput clock начинается с первого media-byte, а не с runtime/discovery start;
- rate обновляется EWMA по реальному приросту retained media;
- AUTO целится в bounded playable duration, а не в случайный byte threshold;
- discovery latency не может искусственно занизить оценку скорости;
- старый 512-KiB startup floor удалён;
- AUTO forced-start считается от first-media и требует более сильный reserve;
- MANUAL threshold не обходится AUTO forced-start.

### Следующий LiveBufferController

Должен оперировать одновременно `bytes` и `seconds` и иметь hysteresis:

- `critical` — playback скоро исчерпает запас;
- `low` — усилить scheduling/refill;
- `target` — нормальный рабочий запас;
- `high` — не расширять pipeline без необходимости.

Порог в секундах рассчитывается из измеренного media delivery/consumption, а не из непроверенного raw descriptor bitrate.

## Adaptive scheduling

Текущий фиксированный request depth является только baseline. Следующий scheduler должен учитывать:

- buffer pressure;
- число producing peers;
- aggregate delivery ratio к consumer rate;
- peer freshness/timeout history;
- live-window usefulness;
- memory/reassembly bounds.

Ожидаемое поведение:

- healthy/high buffer → ограниченный стабильный pipeline без churn;
- low buffer → больше полезных requests и bounded peer refill;
- peer перестал производить media → его score падает, work requeue/replace выполняется без ожидания общего stall timeout;
- critical buffer → recovery получает приоритет, но не нарушает memory/in-flight caps;
- startup достиг устойчивого media-ready → startup-specific DHT expansion прекращается, остаётся lightweight refill.

## Player boundary

P2P runtime и Media3 сейчас имеют независимые buffer loops. Чтобы исключить повторные десятки секунд ожидания после уже готового P2P stream, необходимо измерять:

- `first_media_byte`;
- `startup_buffer_ready`;
- `loopback_http_open`;
- `loopback_first_read`;
- producer bytes/s;
- consumer bytes/s;
- retained bytes/seconds;
- Media3 `BUFFERING`;
- Media3 `READY`;
- `first_video_frame`;
- rebuffer count/duration;
- backend Media3/LibVLC и точную fallback-причину.

После этого вводится отдельный P2P Media3 LoadControl для localhost live stream. Таймауты upstream discovery не используются как способ скрыть плохое player-buffer взаимодействие.

## MPEG-TS / discontinuity

При recovery jump P2P слой уже выдаёт явный discontinuity. Media-output слой должен отдельно решить:

- сброс TS resync state;
- PAT/PMT reacquisition;
- random-access/IDR gating там, где это можно подтвердить;
- decoder flush/reprepare только при необходимости.

Media-format логика не переносится внутрь peer scheduler.

## Метрики качества

Цели фиксируются по контрольной матрице, а не по единичному удачному каналу.

### Startup/zap

- p50/p90 `play_request → first_media_byte`;
- p50/p90 `first_media_byte → buffer_ready`;
- p50/p90 `buffer_ready → first_video_frame`;
- количество superseded requests, успевших начать network/runtime work;
- недоступный swarm завершается bounded error, а не бесконечным ожиданием.

### Sustained playback

- rebuffer count/hour;
- total rebuffer seconds/hour;
- minimum/median buffer seconds;
- producing peers over time;
- aggregate producer/consumer delivery ratio;
- recovery success after one producing peer disappears;
- memory high-water and absence unbounded growth.

## Этапы реализации

### V1 — adaptive startup

- [x] first-media throughput clock;
- [x] EWMA media delivery rate;
- [x] stronger AUTO minimum reserve;
- [x] forced-start anchored to first-media;
- [x] regression tests for long discovery + fast media;
- [ ] exact-head CI + real Torrent TV smoke.

### V2 — producing peer accounting

- [ ] peer lifecycle counters;
- [ ] per-peer media freshness/rate;
- [ ] aggregate producing-peer snapshot;
- [ ] structured diagnostics/UI source for real peer status.

### V3 — buffer-pressure scheduler

- [ ] critical/low/target/high watermarks;
- [ ] adaptive request depth/in-flight;
- [ ] peer replacement based on producing quality;
- [ ] startup discovery shutdown after stable-ready;
- [ ] bounded recovery regression matrix.

### V4 — player/TS boundary

- [ ] loopback producer/consumer telemetry;
- [ ] P2P-specific Media3 LoadControl;
- [ ] BUFFERING/READY/first-frame telemetry;
- [ ] TS discontinuity/PAT/PMT/random-access recovery.

### V5 — hardware acceptance

- [ ] fixed working/unavailable Torrent TV matrix;
- [ ] 20-channel rapid-zap sequence;
- [ ] repeated cold/warm starts;
- [ ] weak network and peer-loss injection;
- [ ] 2-hour ARM playback;
- [ ] 8-hour ARM soak;
- [ ] no external Ace Engine installed/required.

## Неподходящие «исправления»

- увеличивать 30/60-second bounds, чтобы скрыть плохой startup;
- считать найденный tracker/DHT endpoint доказательством playable peer;
- запускать player с крошечным byte buffer без оценки реальной media rate;
- перекладывать upstream stall на LibVLC;
- использовать внешний Ace Engine как fallback успешности собственного runtime;
- создавать массовый background probe всего Torrent TV каталога;
- копировать закрытый/декомпилированный код аналогов.
