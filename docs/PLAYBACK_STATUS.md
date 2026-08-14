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
- PR #107 завершил adaptive prebuffer v1: first-media throughput clock, EWMA media delivery, усиленный AUTO startup reserve и bounded forced-start прошли exact-head CI и real Torrent TV smoke без внешнего Ace Engine.
- PR #108 завершил первый V2 peer-accounting слой: `AceLivePeerProductionTracker` различает discovered/connected/handshaked/producing, хранит freshness и aggregate EWMA media delivery rate; exact-head CI #495, включая real Torrent TV playback smoke без Ace Engine, завершился успешно.
- EPG OOM hotfix PR #100 остаётся в `main`: production XMLTV path bounded/streaming, тяжёлые loads сериализованы и имеют backoff/low-memory guard.

## Свежий ARM/TV Box лог — ключевые выводы

Экспорт от 14 августа показывает, что массовую проблему Torrent TV нельзя объяснить только плохими каналами или codec support.

### Discovery не равен playable peer

В нескольких сессиях initial tracker быстро возвращает один endpoint, после чего DHT находит дополнительные endpoints. При этом встречаются ситуации:

- `peers=4..5` и затем полный 60-second source timeout;
- `peers=4..7` и затем no-peer error;
- один канал получает 1 initial peer, позже DHT видит 7 endpoints, но runtime всё равно не формирует устойчивый media path.

Поэтому raw `peers=N` не является критерием здоровья swarm. После PR #108 runtime уже имеет отдельный production snapshot и не обязан трактовать найденные endpoints как media-producing peers.

Текущий V2b уточняет этот контракт ещё сильнее: отдельный peer считается реально requestable только когда его advertised live-window содержит authoritative `nextNeededPiece()` (`windowUseful`) и peer находится в `unchoked` state. Fresh media contribution входит в `producing` только пока обе эти стадии остаются истинными.

### P2P resolve и decoder startup — разные проблемы

Есть Ace Live каналы, где после `embedded_ace_live_resolved` Media3 достигает READY очень быстро: примерно 0.1–1.6 секунды. Это доказывает, что Media3/MediaCodec на устройстве в принципе способен быстро воспроизводить часть нашего локального MPEG-TS output.

Но другие уже resolved каналы показывают повторяемый `player_start → player_ready` около **66 секунд**. Поэтому требуется отдельная телеметрия loopback/player boundary: first HTTP open/read, Media3 BUFFERING/READY, first frame, producer/consumer rate и rebuffer duration.

Codec incompatibility остаётся возможной причиной для отдельных каналов, но не объясняет общий класс slow/stalling Torrent TV behavior.

### Исправленный дефект startup prebuffer — V1

До PR #107 AUTO policy оценивал media rate как retained bytes / возраст всего runtime. Discovery/handshake latency попадала в знаменатель.

Пример класса ошибки:

1. runtime ищет/проверяет peers 15–20 секунд;
2. затем здоровый peer начинает быстро отдавать media;
3. старая формула всё равно делит первые media bytes на 15–20 секунд;
4. observed rate искусственно занижается;
5. target стремится к минимальному byte floor;
6. старый forced-start мог открыть stream примерно с 512 KiB.

PR #107 исправил этот класс дефекта: throughput clock начинается с первого media sample, используется EWMA, а startup reserve усилен без увеличения абсолютных failure bounds.

## Текущий инкремент: Ace Live peer quality V2b

V1 и базовый V2 уже находятся в `main`. Текущий V2b строится поверх PR #108 и не меняет wire protocol, startup timeout или stall timeout.

Реализуемый quality funnel:

1. `discovered` — tracker/DHT только нашёл endpoint;
2. `connected` — TCP transport установлен;
3. `handshaked` — Ace handshake принят;
4. `windowUseful` — advertised window содержит authoritative `nextNeededPiece()`;
5. `unchoked` — peer разрешает requests;
6. `producing` — peer имеет fresh contiguous media contribution и одновременно остаётся `windowUseful + unchoked`.

`AceLiveTcpConnectionPool` пересчитывает requestability при:

- peer metadata/live-window update;
- продвижении contiguous output cursor;
- recovery cursor advance;
- choke/unchoke ingress state changes.

Это важно для следующего scheduler: stale peer больше не должен сохранять хороший producing status только потому, что несколько секунд назад отдавал media.

## Текущие известные проблемы

- V2b ещё должен пройти собственный exact-head Android CI + real Torrent TV smoke перед merge;
- media production пока учитывается на уровне contiguous reassembled contribution; следующий шаг должен закрепить post-authenticated/post-output semantics;
- peer-quality snapshot ещё не сохраняется в persistent diagnostics/UI;
- часть источников исчерпывает bounded startup despite нескольких найденных endpoints;
- часть потоков после успешного resolve долго остаётся в player buffering;
- sustained prefetch не имеет полноценной модели buffer seconds + consumer rate;
- request depth пока в основном статический (`MAX_IN_FLIGHT_PER_PEER = 2` baseline);
- startup DHT expansion может продолжать работу после того, как media path уже достаточно прогрессировал;
- MPEG-TS output после recovery discontinuity ещё не имеет полного PAT/PMT/random-access/IDR hardening;
- P2P и Media3 используют два независимых buffer feedback loop без полной cross-layer telemetry;
- не завершены weak-network, peer-loss, 2h/8h ARM soak tests.

## Следующие P2P задачи

1. провести exact-head CI + real Torrent TV smoke для V2b `windowUseful/unchoked` accounting;
2. после зелёного V2b вывести structured peer-quality snapshot в persistent diagnostics/UI: `discovered / connected / handshaked / windowUseful / unchoked / producing / aggregate Mbps / freshest media age`;
3. закрепить production semantics на подтверждённом post-authenticated/post-output media boundary;
4. добавить `LiveBufferController` с `critical/low/target/high` watermarks в секундах и bytes;
5. сделать request depth/in-flight адаптивным к buffer pressure и quality producing peers;
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
- peer/status diagnostics различает discovery, connection, handshake, useful window, unchoke и real media production;
- Media3 READY/first-frame и rebuffer причины измеряются отдельно от P2P resolve;
- переключение и stop не оставляют старую P2P-сессию/loopback stream;
- weak network приводит к bounded recovery либо точной ошибке;
- нет crash/ANR/OOM и неконтролируемого memory growth;
- 2h и 8h ARM soak проходят на контрольной матрице;
- exact-head Android CI, unit/instrumentation checks и real Torrent TV smoke зелёные.

Подробная архитектура этапа: [`ACE_LIVE_ADAPTIVE_STREAMING_CORE.md`](ACE_LIVE_ADAPTIVE_STREAMING_CORE.md).