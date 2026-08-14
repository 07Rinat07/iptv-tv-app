# Статус воспроизведения

Актуальный срез: **14 августа 2026 года**.

## Главный приоритет

Собственный автономный P2P/Ace Live runtime является текущим главным техническим приоритетом проекта. Torrent TV `content_id` и live identities должны воспроизводиться без внешнего Ace Stream Engine. Внешние продукты могут использоваться только как поведенческий A/B benchmark на том же канале/устройстве, но не как fallback или условие успешной работы приложения.

Цель текущего этапа — не увеличивать timeout, а улучшить цепочку:

`peer discovery → useful/media-producing peers → throughput → adaptive live buffer → loopback → Media3`.

## Что подтверждено

- Обычные IPTV-потоки открываются через Media3; LibVLC используется только для ошибок container/demux/codec класса.
- PR #99 убрал полное пересоздание Media3/MediaCodec на каждом обычном IPTV zap при совместимых transport settings.
- `magnet:`, BitTorrent infohash и `.torrent` обслуживаются встроенным libtorrent backend.
- Torrent TV `content_id` и live identities обслуживаются встроенным Ace Live runtime без автоматического запуска внешнего Ace Engine.
- PR #98 ввёл first-success race direct Ace Live / metadata.
- PR #101 закрепил bounded startup discovery, persistent diagnostics и каталог из 279 Torrent TV каналов.
- PR #102 добавил latency analyzer.
- PR #103 добавил cancellable rapid-zap coalescing.
- PR #104 сохранил direct-Ace progress внутри существующего soft window при конкурентном metadata resolution.
- PR #105 закрыл reuse sessionId/zombie retry/stale callback takeover и regression `A → B → C → late retry A`.
- PR #106 убрал availability-filter из Torrent TV каталога: весь выбор остаётся видимым, статус является информационным.
- EPG OOM hotfix PR #100 остаётся в `main`: production XMLTV path bounded/streaming, тяжёлые loads сериализованы и имеют backoff/low-memory guard.

## Свежий ARM/TV Box лог — ключевые выводы

Экспорт от 14 августа показывает, что массовую проблему Torrent TV нельзя объяснить только плохими каналами или codec support.

### Discovery не равен playable peer

В нескольких сессиях initial tracker быстро возвращает один endpoint, после чего DHT находит дополнительные endpoints. При этом встречаются ситуации:

- `peers=4..5` и затем полный 60-second source timeout;
- `peers=4..7` и затем no-peer error;
- один канал получает 1 initial peer, позже DHT видит 7 endpoints, но runtime всё равно не формирует устойчивый media path.

Следовательно текущий `peers=N` означает прежде всего **discovered endpoints**, а не обязательно `connected/handshaked/unchoked/media-producing peers`.

Следующий peer-accounting слой обязан различать эти стадии и измерять media freshness/rate каждого действительно полезного peer.

### P2P resolve и decoder startup — разные проблемы

Есть Ace Live каналы, где после `embedded_ace_live_resolved` Media3 достигает READY очень быстро: примерно 0.1–1.6 секунды. Это доказывает, что Media3/MediaCodec на устройстве в принципе способен быстро воспроизводить часть нашего локального MPEG-TS output.

Но другие уже resolved каналы показывают повторяемый `player_start → player_ready` около **66 секунд**. Поэтому требуется отдельная телеметрия loopback/player boundary: first HTTP open/read, Media3 BUFFERING/READY, first frame, producer/consumer rate и rebuffer duration.

Codec incompatibility остаётся возможной причиной для отдельных каналов, но не объясняет общий класс slow/stalling Torrent TV behavior.

### Найден конкретный дефект startup prebuffer

До текущего adaptive-streaming инкремента AUTO policy оценивал media rate как retained bytes / возраст всего runtime. Discovery/handshake latency попадала в знаменатель.

Пример класса ошибки:

1. runtime ищет/проверяет peers 15–20 секунд;
2. затем здоровый peer начинает быстро отдавать media;
3. формула всё равно делит первые media bytes на 15–20 секунд;
4. observed rate искусственно занижается;
5. target стремится к минимальному byte floor;
6. старый forced-start мог открыть stream примерно с 512 KiB.

Для HD live этого запаса может быть недостаточно, что согласуется с полевым поведением «долго запускается → начинает показывать → тормозит/буферизуется».

## Текущий инкремент: Ace Live adaptive streaming core v1

Первая часть уже реализуется в `core:p2p`:

1. throughput clock устанавливается по **первому media sample**, а не по runtime start;
2. discovery/handshake latency больше не занижает rate;
3. rate обновляется EWMA по реальному приросту media bytes;
4. AUTO target теперь по умолчанию ориентирован на 4 секунды media;
5. minimum AUTO startup reserve повышен с 512 KiB до 1 MiB;
6. maximum AUTO startup reserve увеличен до 6 MiB внутри существующего bounded output buffer;
7. forced-start budget считается от first-media и требует минимум 2 MiB;
8. MANUAL threshold не обходится AUTO forced-start;
9. абсолютный 60-second startup timeout и narrow 30-second no-connected-peer guard **не увеличиваются**.

Это не считается окончательным решением sustained playback. Следующие V2/V3 должны добавить media-producing peer accounting, buffer watermarks и feedback-driven request depth.

## Текущие известные проблемы

- discovered peer ещё не означает accepted handshake/useful live-window/media delivery;
- часть источников исчерпывает bounded startup despite нескольких найденных endpoints;
- часть потоков после успешного resolve долго остаётся в player buffering;
- sustained prefetch не имеет полноценной модели buffer seconds + consumer rate;
- request depth пока в основном статический (`MAX_IN_FLIGHT_PER_PEER = 2` baseline);
- startup DHT expansion может продолжать работу после того, как media path уже достаточно прогрессировал;
- MPEG-TS output после recovery discontinuity ещё не имеет полного PAT/PMT/random-access/IDR hardening;
- P2P и Media3 используют два независимых buffer feedback loop без полной cross-layer telemetry;
- не завершены weak-network, peer-loss, 2h/8h ARM soak tests.

## Следующие P2P задачи

1. завершить exact-head CI + real Torrent TV smoke для adaptive prebuffer v1;
2. добавить `MediaProducingPeerTracker`: discovered/connected/handshaked/windowUseful/unchoked/producing;
3. добавить per-peer media bytes/rate/freshness и aggregate producing rate;
4. добавить `LiveBufferController` с `critical/low/target/high` watermarks в секундах и bytes;
5. сделать request depth/in-flight адаптивным к buffer pressure и quality peers;
6. прекращать startup-specific discovery после устойчивого media-ready и оставлять lightweight refill;
7. добавить loopback producer/consumer telemetry и Media3 BUFFERING/READY/first-frame/rebuffer metrics;
8. выделить P2P-specific Media3 LoadControl после измерений;
9. wire typed recovery discontinuity в TS/decoder recovery;
10. повторить fixed channel matrix и 20 rapid switches на TV Box.

## Критерий завершения playback/P2P hardening

Playback hardening можно считать завершённым только когда:

- рабочие контрольные IPTV и Torrent TV источники стартуют повторяемо без внешнего Ace Engine;
- healthy swarm имеет стабильный измеримый startup/zap budget;
- недоступный swarm возвращает bounded точную ошибку, а не бесконечный retry;
- live buffer непрерывно пополняется и удерживает измеримый запас;
- кратковременная потеря одного producing peer не обязана останавливать playback, если остаются/находятся альтернативные полезные peers;
- peer/status diagnostics различает discovery, connection, handshake, useful window и real media production;
- Media3 READY/first-frame и rebuffer причины измеряются отдельно от P2P resolve;
- переключение и stop не оставляют старую P2P-сессию/loopback stream;
- weak network приводит к bounded recovery либо точной ошибке;
- нет crash/ANR/OOM и неконтролируемого memory growth;
- 2h и 8h ARM soak проходят на контрольной матрице;
- exact-head Android CI, unit/instrumentation checks и real Torrent TV smoke зелёные.

Подробная архитектура этапа: [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md).
