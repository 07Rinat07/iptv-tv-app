# Embedded P2P runtime notes

## Project boundary

Torrent TV / Ace Live playback принадлежит in-process runtime приложения. Установленный внешний Ace Stream Engine не является normal backend, автоматическим fallback или release requirement. Внешний движок допустим как same-device A/B benchmark и как явно ограниченный compatibility path для поддерживаемых descriptor cases, но не должен скрывать отказ embedded runtime.

## Stage model

Peer и playback progress всегда трактуются как последовательность независимых стадий:

`discovered -> connected -> handshaked -> windowUseful -> unchoked -> producing -> TS -> loopback -> Media3 -> first frame/audio`

Значения не взаимозаменяемы:

- `discovered` — endpoint получен tracker/DHT;
- `connected` — установлен TCP transport;
- `handshaked` — принят protocol handshake;
- `windowUseful` — advertised live window покрывает authoritative playback cursor;
- `unchoked` — peer разрешает запросы;
- `producing` — peer дал media contribution, прошедший authentication/resync и принятый output buffer.

Высокий discovered count не является доказательством usable swarm. Аналогично успешный loopback transfer не доказывает, что Media3 уже получил валидные tracks/first frame.

## Discovery and routing

Tracker fast path может начать TCP probing до завершения DHT, но малый или неqualifying tracker candidate set не должен навсегда подавлять bounded alternative discovery.

Mainline DHT walker обязан сохранять:

- bounded concurrency;
- finite per-query timeout;
- absolute walk/query budgets;
- cancellation закрывает outstanding sockets;
- verified routing memory может использоваться повторно, но empty result не кэшируется как доказательство пустого swarm;
- bootstrap DNS, warm routing contacts и live queries остаются отдельными наблюдаемыми границами.

Изменение DHT branching/query limits/bootstrap set допускается только по evidence конкретного discovery failure.

## Playback ownership and cancellation

Каждый новый playback request/generation инвалидирует ownership более старой подготовки. Поздний callback, retry, metadata result, loopback server или decoder session старой generation не может:

- опубликовать READY/error поверх новой generation;
- вернуть старый localhost URL;
- закрыть runtime, принадлежащий новой generation;
- запустить decoder после смены канала.

При переключении старый active stream должен освобождать loopback server, peer pool и transport ownership детерминированно. Если obsolete preparation ещё не опубликовала stream, cancellation не должна блокировать новый ordinary IPTV/P2P request ненужным ожиданием старого mutex/network work.

## Scheduler, recovery and buffer

Authoritative progress — фактически принятый contiguous media cursor, а не requested frontier. Scheduler обязан:

- назначать work только requestable peers;
- ограничивать in-flight ownership и reassembly window;
- requeue unfinished work при timeout/drop/window loss;
- не перескакивать evicted gap неявно;
- выполнять recovery jump только через отдельное bounded policy decision;
- отличать stale-but-reachable pool от transport failure;
- не считать ingress bytes media progress до завершения required validation/output stages.

Buffer pressure измеряется относительно authoritative consumer cursor. Retained storage bytes сами по себе не равны playable headroom конкретного Media3 reader. Любая адаптация request depth/refill/replacement должна оставаться bounded и не отменять уже выданную ownership без явной причины.

## Loopback / player boundary

Pipeline:

`Ace peers -> scheduler/reassembly -> media validation -> MPEG-TS resync -> bounded media buffer -> localhost HTTP -> Media3`

P2P acquisition и Media3 demux/rendering — разные failure domains. Если upstream уже доказанно выдаёт чистый TS, отсутствие first frame исследуется на TS/Media3 boundary, а не лечится увеличением peer/timeouts. Если producing/TS отсутствуют, Player fallback/buffer change не исправляет upstream swarm.

Dashboard/fullscreen UI обязаны использовать одну playback session и не создавать второй P2P runtime.

## Diagnostics contract

Для failed runtime фиксируется первая отсутствующая стадия и correlation fields (`startup/session/runtime/generation` в доступной реализации). Полезные диагностические классы:

- discovery/bootstrap/DHT result;
- peer lifecycle and qualification;
- request selection/send/timeout;
- chunk/piece validation and media append;
- MPEG-TS sync/PAT/PMT/random-access evidence;
- loopback reader lifecycle/delivery;
- Media3 load/READY/first audio/first video/rebuffer.

Diagnostics не должна изменять scheduler, retry, timeout, buffer или ownership semantics. Raw content id, credential-bearing URL, token и payload не должны попадать в обычные логи/экспорт.

## Safety invariants

Не использовать как универсальный workaround:

- увеличение global startup/no-peer/stall timeout;
- увеличение DHT query/branching budget;
- увеличение active peer/request-depth limits;
- увеличение player/P2P buffers или heap;
- автоматический внешний Ace Engine fallback.

Лимит меняется только когда exact failure evidence показывает, что корректный поток упирается именно в этот лимит.

## Acceptance

Runtime-changing PR требует deterministic unit/regression coverage там, где это возможно, exact-head relevant CI и integrated-main verification. Discovery/peer/network behavior считается принятым только после fixed TV Box field matrix на exact integrated build. CI подтверждает regression safety, но не доказывает доступность реального swarm.