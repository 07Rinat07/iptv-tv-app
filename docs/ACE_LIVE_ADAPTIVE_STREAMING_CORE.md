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
6. `producing` — peer недавно дал media contribution, который прошёл authentication, MPEG-TS resync и был фактически принят live output buffer.

Для каждого producing peer нужны как минимум freshness, delivered bytes/rate, timeout/error history и usefulness текущему cursor.

PR #108 завершил базовый V2 accounting и уже находится в `main`: отдельный `AceLivePeerProductionTracker` не считает найденный endpoint producing peer, хранит `connected/handshaked`, отмечает fresh media production и строит aggregate EWMA delivery snapshot. `AceLiveTcpConnectionPool` подключает tracker к реальным connect/handshake/disconnect событиям и предоставляет immutable `peerProductionSnapshot()` для scheduler/diagnostics слоя.

PR #109 завершил V2b requestability и уже находится в `main`. `windowUseful` вычисляется не по факту наличия metadata, а относительно authoritative `nextNeededPiece()`: peer полезен только если текущий cursor находится внутри его advertised live-window. `unchoked` берётся из фактического peer-wire state. Requestability пересчитывается при metadata/window update, нормальном продвижении contiguous cursor и recovery jump. Fresh media считается `producing` только пока peer одновременно `windowUseful + unchoked`; stale-window или choke исключают его из producing snapshot немедленно, не ожидая общего stall timeout. Exact-head Android CI #497 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

PR #110 завершил V2c persistent diagnostics и уже находится в `main`. `AceLivePeerDiagnosticsReporter` публикует structured event `embedded_ace_live_peer_quality` с полями `discovered / connected / handshaked / windowUseful / unchoked / producing / aggregate_bps / aggregate_mbps / freshest_media_age_ms`. Material stage changes публикуются сразу, а стабильное состояние обновляется не чаще одного раза в 5 секунд, поэтому 200-мс scheduler tick не превращается в поток DB-записей. Existing path `AceLiveEmbeddedEngine.diagnosticsObserver → HybridEngineRepositoryImpl → SyncLogDao → DiagnosticsRepository` используется без второго логгера. Exact-head Android CI #500 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

PR #111 завершил V2d post-output semantics и уже находится в `main`. Peer provenance сохраняется из verified piece ownership через bounded reassembly до `AceLiveReassembledPiece.sourcePeerId`. Сетевой ingress и contiguous reassembly больше не могут самостоятельно отметить peer как producing. Production подтверждается только после `AceLiveMediaAuthenticator.verifyAndStrip()`, `AceLiveMpegTsResynchronizer.consume()` и успешного `AceLiveMediaBuffer.append()`.

До первого устойчивого MPEG-TS sync resynchronizer может удерживать небольшой pending tail предыдущего piece, поэтому credited contribution ограничен `min(acceptedOutputBytes, authenticatedCurrentPieceBytes)`. Закрытый live buffer возвращает `0` accepted bytes и не создаёт production evidence. Late output buffered piece не воскресает как connected/handshaked peer. Exact-head Android CI #502 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно; scheduler request depth, recovery policy, startup/no-peer/stall bounds и wire protocol не менялись.

PR #112 завершил V3a `LiveBufferController` primitive и уже находится в `main`. Pressure делится на `CRITICAL / LOW / TARGET / HIGH`, positive consumer rate включает duration-authoritative classification, без rate используется byte fallback, а все три границы защищены hysteresis. Exact-head Android CI #504 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно. V3a намеренно не подключал pressure к scheduler.

PR #113 завершил V3b confirmed consumer telemetry и уже находится в `main`. Реальный playable headroom считается относительно подтверждённого loopback HTTP consumer cursor, а не retained storage window; per-reader consumer rate строится по подтверждённым socket deliveries. Exact-head Android CI #506 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

PR #114 завершил V3c authoritative active-consumer selection и уже находится в `main`. Новый HTTP reader не перехватывает ownership только фактом открытия: handoff происходит после подтверждённой доставки. Late delivery старого reader не может вернуть ownership назад, а закрытие active reader выбирает самый новый ещё открытый reader с подтверждённой доставкой. Exact-head Android CI #508 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

PR #115 завершил V3d authoritative consumer lifecycle wiring и уже находится в `main`. `LoopbackHttpLiveServer` публикует `Opened / Delivered / Closed`; `AceLiveAuthoritativeConsumerPressureTracker` объединяет selection и per-reader hysteresis; runtime persistent buffer-pressure diagnostics получают только authoritative consumer samples. Exact-head Android CI #511 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

PR #116 завершил V3e bounded adaptive request depth и уже находится в `main`: authoritative pressure выбирает `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`, до первого consumer sample сохраняется baseline `2`, а снижение depth не отменяет уже выданные piece ownership. Exact-head Android CI #513, real Torrent TV playback smoke без внешнего Ace Engine, lint, все unit tests и signed ARM TV APK прошли успешно.

PR #117 завершил V3f pressure-aware bounded peer refill и уже находится в `main`: `TARGET/HIGH` не расширяют normal pool, `LOW` разрешает +1 probe-peer, `CRITICAL` +2, всегда в пределах `maxActivePeers`; recovery и pressure demand используют максимум, а не сумму. Per-peer quality snapshots публикуют lifecycle/requestability/production/freshness/rate evidence. Exact-head Android CI #515, real Torrent TV playback smoke без внешнего Ace Engine, lint, все unit tests и signed ARM TV APK прошли успешно.

PR #118 завершил V3g bounded replacement и уже находится в `main`: replacement разрешён только при свежем sustained `CRITICAL`; producing peer никогда не кандидат, degradation должна сохраняться отдельное evidence window, после удаления обязаны оставаться минимум baseline requestable/producing peers, а cooldown разрешает максимум один replacement за цикл/окно. Android CI #517, real Torrent TV playback smoke без внешнего Ace Engine, lint, все unit tests и signed ARM TV APK прошли успешно.

PR #119 завершил V3h startup discovery lifecycle и уже находится в `main`: startup-specific bounded DHT probe/full-expansion отменяется после stable `startup_buffer_ready`, даже если startup-only work уже выполняется; cancellation не прекращает обычный long-running lightweight refill. Android CI #519 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

PR #120 завершил V3i bounded recovery selection semantics и уже находится в `main`. При evicted-gap recovery peer window, полностью находящееся позади authoritative cursor, больше не маскирует ближайшее requestable future-window. Recovery по-прежнему разрешён только после существующего request-timeout, учитывает только unchoked windows и остаётся ограничен `maxPieceAdvance`. Android CI #522 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

PR #121 завершил V4a player-boundary telemetry и уже находится в `main`: first localhost HTTP open/read отделены от confirmed delivery, Media3 публикует `BUFFERING/READY/first-frame`, rebuffer count/duration и session correlation только для актуальной P2P session. Android CI #524, playback-latency tooling и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

V4b/PR #122 завершён: отдельный bounded Media3 LoadControl применяется только к localhost P2P playback; Android CI #531 и dedicated real Torrent TV smoke #2 прошли успешно.

Текущий V4c усиливает explicit `outputDiscontinuity` boundary. После подтверждённого recovery jump runtime сначала заново получает stable MPEG-TS sync, затем bounded gate удерживает output до свежего PAT, соответствующего PMT с video PID и random-access evidence на этом PID. Evidence принимается из MPEG-TS `random_access_indicator` либо H.264 IDR / H.265 IRAP NAL. Request timeout, `maxPieceAdvance`, ownership/requeue, refill/replacement и startup/stall failure bounds не меняются.

## Buffer model

### Уже в adaptive prebuffer v1

- throughput clock начинается с первого media-byte, а не с runtime/discovery start;
- rate обновляется EWMA по реальному приросту retained media;
- AUTO целится в bounded playable duration, а не в случайный byte threshold;
- discovery latency не может искусственно занизить оценку скорости;
- старый 512-KiB startup floor удалён;
- AUTO forced-start считается от first-media и требует более сильный reserve;
- MANUAL threshold не обходится AUTO forced-start.

### V3a — LiveBufferController primitive

`AceLiveBufferController` классифицирует явный playable headroom в четыре стабильные зоны:

- `CRITICAL` — запас ниже critical boundary;
- `LOW` — запас выше critical, но ниже target;
- `TARGET` — рабочий запас между target и high;
- `HIGH` — запас выше high boundary.

Если известен положительный consumer rate, authoritative signal — `playableDurationMillis`; без надёжной consumer-rate оценки используется bounded byte fallback. Все три границы имеют симметричный hysteresis, поэтому небольшие колебания около threshold не переключают scheduler-facing state на каждом sample. Смена signal `BYTES ↔ DURATION` выполняет немедленную reclassification, поскольку это разные модели измерения, а не соседние samples одной шкалы.

Критически важно: `AceLiveMediaBuffer.retainedBytes()` — размер retained sliding storage window, а не непрочитанный запас конкретного Media3 reader. Поэтому controller принимает явный `playableBytes`.

### V3b — confirmed consumer telemetry

`AceLiveMediaBuffer.Reader` теперь разделяет `readCursor` и подтверждённый `deliveredCursor`. `read()` только копирует pending chunk. Consumer cursor продвигается через `confirmDelivered()` исключительно после успешных `output.write()` и `output.flush()` в `LoopbackHttpLiveServer`; socket failure не создаёт ложное потребление.

Per-reader snapshot содержит:

- `readerId`;
- confirmed/effective `consumerOffset`;
- `liveEdgeOffset`;
- `playableBytes = liveEdge - effective consumer offset`;
- EWMA `consumerBytesPerSecond` по подтверждённой доставке;
- `totalDeliveredBytes`;
- `fellBehind`, если retained live floor обогнал reader и старые bytes были эвиктированы.

Media3 reconnect/retry может кратковременно создать несколько HTTP readers, поэтому stateful pressure hysteresis нельзя делить между ними. `AceLiveConsumerBufferPressureTracker` хранит отдельный `AceLiveBufferController` на reader и bounded LRU state.

`AceLiveBufferDiagnosticsReporter` сохраняет через уже существующий `diagnosticsObserver` structured event `embedded_ace_live_buffer_pressure`: reader, pressure/signal, playable bytes/ms, consumer B/s/Mbps, consumer/live-edge offsets, total delivered и fall-behind status. Material pressure/signal/fall-behind changes публикуются сразу, стабильные rate/headroom обновляются не чаще одного раза в 5 секунд. Reporter также bounded per reader.

PR #113 подтвердил этот telemetry boundary exact-head CI #506 и real Torrent TV playback smoke без внешнего Ace Engine; scheduler behavior тогда не менялся.

### V3c/V3d — authoritative consumer ownership and lifecycle

PR #114 добавил `AceLiveActiveConsumerSelector`: `Opened` не делает reader authoritative, первое подтверждённое `Delivered` нового reader выполняет monotonic handoff, late delivery старого reader игнорируется, а `Closed` active reader может вернуть ownership самому новому подтверждённому fallback reader.

V3d проводит эти lifecycle-сигналы через реальный `LoopbackHttpLiveServer`. `Delivered` возникает только после socket `write + flush + confirmDelivered`; `Closed` публикуется из `finally`, поэтому abrupt client/session shutdown не оставляет selector с вечным активным reader. `AceLiveAuthoritativeConsumerPressureTracker` выдаёт pressure sample только для выбранного reader или для реального ownership fallback.

V3d завершён как behavior-neutral lifecycle boundary. PR #116/V3e добавил bounded request-depth feedback, PR #117/V3f — additive refill `LOW +1 / CRITICAL +2`, PR #118/V3g — консервативный replacement только при sustained `CRITICAL` и подтверждённой degradation. Сам stop проходит через существующий TCP/session disconnect cleanup, поэтому piece ownership requeue остаётся в прежнем recovery boundary. V3h отдельно завершает startup discovery lifecycle: после stable-ready startup-only DHT expansion прекращается, но normal lightweight refill продолжает поддерживать peer pool.

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
- playable bytes/seconds относительно confirmed consumer cursor;
- Media3 `BUFFERING`;
- Media3 `READY`;
- `first_video_frame`;
- rebuffer count/duration;
- backend Media3/LibVLC и точную fallback-причину.

V3d уже даёт фактические loopback lifecycle-сигналы и authoritative confirmed delivery rate/headroom, но ещё не экспортирует отдельные first-open/first-read timestamps в player analytics. После стабилизации scheduler feedback вводится отдельный P2P Media3 LoadControl для localhost live stream. Таймауты upstream discovery не используются как способ скрыть плохое player-buffer взаимодействие.

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
- [x] exact-head CI + real Torrent TV smoke без внешнего Ace Engine (PR #107).

### V2 — producing peer accounting

- [x] lifecycle/production accounting primitive;
- [x] per-peer media freshness/rate primitive;
- [x] aggregate producing-peer snapshot primitive;
- [x] wire tracker to TCP lifecycle + contiguous reassembled media events (PR #108);
- [x] include `windowUseful/unchoked` requestability state in quality snapshot (PR #109);
- [x] recalculate `windowUseful` against authoritative cursor after metadata, contiguous output and recovery advance (PR #109);
- [x] exact-head CI + real Torrent TV smoke for V2b (Android CI #497 / PR #109);
- [x] persistent structured peer-quality diagnostics source (PR #110);
- [x] exact-head CI + real Torrent TV smoke for V2c (Android CI #500 / PR #110);
- [x] post-authenticated/post-output production accounting with peer provenance (PR #111);
- [x] exact-head CI + real Torrent TV smoke for V2d (Android CI #502 / PR #111).

### V3 — buffer-pressure scheduler

- [x] pure `critical/low/target/high` pressure primitive with bytes/duration and hysteresis (PR #112);
- [x] exact-head CI + real Torrent TV smoke for V3a (Android CI #504 / PR #112);
- [x] confirmed consumer-cursor/playable-headroom telemetry and persistent pressure diagnostics (PR #113);
- [x] exact-head CI + real Torrent TV smoke for V3b (Android CI #506 / PR #113);
- [x] authoritative active-consumer lifecycle/selection primitive (PR #114);
- [x] exact-head CI + real Torrent TV smoke for V3c (Android CI #508 / PR #114);
- [x] wire loopback `Opened / Delivered / Closed` into authoritative pressure path (PR #115);
- [x] exact-head CI + real Torrent TV smoke for V3d (Android CI #511 / PR #115);
- [x] adaptive request depth/in-flight (PR #116);
- [x] pressure-aware bounded peer refill (PR #117);
- [x] peer replacement based on producing quality (PR #118);
- [x] startup discovery shutdown after stable-ready (PR #119);
- [x] bounded recovery regression matrix (PR #120).

### V4 — player/TS boundary

- [x] first-open/first-read and Media3-boundary telemetry beyond V3 confirmed-delivery input (PR #121);
- [ ] P2P-specific Media3 LoadControl (current V4b);
- [x] BUFFERING/READY/first-frame telemetry (PR #121);
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
- трактовать retained sliding-window bytes как непрочитанный consumer headroom;
- считать `Reader.read()` фактом consumer delivery до успешного socket flush;
- делить один stateful hysteresis controller между независимыми/reconnect readers;
- подавать pressure в scheduler до определения authoritative active consumer;
- перекладывать upstream stall на LibVLC;
- использовать внешний Ace Engine как fallback успешности собственного runtime;
- создавать массовый background probe всего Torrent TV каталога;
- копировать закрытый/декомпилированный код аналогов.
