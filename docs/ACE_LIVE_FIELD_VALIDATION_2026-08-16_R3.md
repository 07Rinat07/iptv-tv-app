# Ace Live field validation R3 — 16 августа 2026

## Контрольный прогон

R3 выполнен на TV Box после merge PR #131–#133. Проверялись healthy/slow/failing Torrent TV каналы и последовательные переключения.

Ключевой внешний контроль: те же каналы на том же пользовательском сценарии воспроизводятся через Televizo + Ace Stream Engine и обычно переключаются примерно за 2–5 секунд. Поэтому generic UI-текст про возможный устаревший content ID не считается доказательством stale source. Для текущего V4d основным объектом исправления остаётся собственный embedded Ace Live runtime.

## Что видно в приложении

- часть каналов запускается или уходит в резервный LibVLC;
- часть P2P-сеансов остаётся в `P2P · поиск пиров...` и доходит до существующего 60-second preparation timeout;
- Discovery HD отдельно дошёл до ошибки отсутствия доступного peer за отведённое время;
- на других failing sessions discovery находил endpoints, но они не превращались в стабильного media producer.

## Lifecycle evidence

R3 telemetry после PR #132 впервые разделила причины отказа:

- `CONNECT_FAILED` на части endpoints;
- `REMOTE_CLOSED` после TCP ownership на части endpoints;
- `HANDSHAKE_TIMEOUT` на части endpoints;
- как минимум один startup достиг `handshake_accepted` и `windowUseful=1`, но оставался `producing=0` до общего startup deadline.

Это означает, что проблема шире простой формулировки «пиров нет». Доказанный bottleneck находится в цепочке:

`discovery -> candidate freshness/diversity -> dial -> Ace handshake -> useful window -> producing`.

## Сопоставление с R3 decision matrix

Наблюдались одновременно acquisition-сигналы и qualification-сигналы:

1. candidates могут быть найдены, но часть сразу даёт `CONNECT_FAILED`;
2. часть TCP peers закрывается или не завершает Ace handshake;
3. один accepted/useful peer не гарантирует media production;
4. Media3/localhost не является первым blocker для этих failing sessions — до player boundary они не доходят.

Следовательно, следующий behavior increment относится к **discovery/acquisition**, а не к HTTP Range, Media3 LoadControl, output buffer или TS decoder tuning.

## Выбранный bounded increment

Первый R3 acquisition fix сохраняет tracker как мгновенный fast path для первого TCP start, но больше не принимает число tracker endpoints за доказательство достаточного startup diversity.

Новая политика:

- initial tracker discovery по-прежнему возвращается без ожидания DHT;
- любой tracker-only startup result с хотя бы одним endpoint планирует существующую DHT-only последовательность `probe -> probe -> full expansion` в фоне;
- startup probe bounds не меняются: `returnAfterPeers=1`, максимум 2 rounds, 7-second budget на probe;
- DHT остаётся process-wide serialized и под существующим heap-headroom guard;
- startup-specific DHT автоматически отменяется, когда media startup завершён;
- 60-second preparation deadline, no-connected-peer guard, peer pool target/max, handshake/request timeout, scheduler, recovery, TS gate, HTTP/Media3 и output buffer не меняются.

Это исправляет конкретную ошибку прежней эвристики: tracker мог вернуть 4+ адресов, после чего DHT считался ненужным, хотя R3 показал, что эти адреса могут все оказаться `CONNECT_FAILED/HANDSHAKE_TIMEOUT/REMOTE_CLOSED`.

## Следующие решения после field validation этого increment

Если startup всё ещё крутится вокруг повторных слабых endpoints, следующий отдельный PR должен рассмотреть outcome-aware **unseen endpoint preference / positive DHT cache bypass** только во время failing startup, сохранив общий DHT resource budget.

Если свежих пригодных endpoints достаточно, но qualification идёт последовательно и медленно, следующий отдельный PR — bounded competitive half-open/dial acquisition без увеличения `maxActivePeers` и глобальных timeout.

Если peer проходит handshake/useful и стабильно `producing=1`, но затем падает playable headroom, только тогда возвращаться к forward reserve/post-READY pressure.

HTTP logical-offset resume остаётся evidence-gated: последний подтверждённый successful path использовал `Range=none` и Media3 после localhost exposure дошёл до READY/first-frame за sub-second время.

## Safety invariants

Не менять в этом increment:

- абсолютный 60-second startup failure bound;
- 30-second no-connected-peer guard;
- handshake/request timeout;
- DHT query/budget caps;
- peer target/max;
- recovery `maxPieceAdvance`;
- TS auth/resync/discontinuity gate;
- generic IPTV и Media3 policy;
- output buffer/cache capacity;
- HTTP Range/resume semantics;
- внешний Ace Stream Engine как runtime dependency.
