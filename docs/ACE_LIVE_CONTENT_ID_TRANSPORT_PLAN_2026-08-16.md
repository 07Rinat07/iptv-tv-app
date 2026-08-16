# V4d Content-ID transport resolution gate — 16 августа 2026

## Почему приоритет изменён

После PR #134 был выполнен отдельный real Torrent TV sweep на свежем `main` (`0baa1939dbe1782fbacbd28a91876db5027b8dfb`) без внешнего Ace Stream Engine.

Результат sweep:

- 8 реальных источников;
- 3 источника дошли до Media3 READY + first frame;
- 5 источников завершились существующим 60-second preparation timeout;
- единственный явно заданный `infohash` sample успешно воспроизвёлся;
- все 7 `content_id` samples дали `content_metadata_result success=false`;
- два из этих семи `content_id` всё же воспроизвелись только через speculative direct-live race.

Следовательно, после #134 нельзя считать следующий основной blocker только проблемой количества live peers. Для общего `content_id` path сначала должен надёжно работать переход:

`content_id -> transport descriptor -> derived live swarm key -> live peer acquisition`.

`content_id` и live swarm key не должны считаться взаимозаменяемыми по умолчанию. Direct use `content_id` как live swarm key остаётся только speculative fast path, а не authoritative resolution.

## Найденная protocol mismatch

Текущий `AceContentMetadataPeerResolver` описывает обмен как BEP-9 `ut_metadata`, но до #136 открывал metadata peer соединение через live-media outer handshake `AceStreamProtocol`.

BEP-9 использует BEP-10 extension protocol, а BEP-10 договаривается после стандартного BitTorrent peer handshake. Поэтому metadata swarm и Ace live-media swarm должны иметь разные outer-handshake codecs.

## Текущий bounded fix — PR #136

PR #136 меняет только Content-ID metadata peer wire boundary:

- metadata peer outer handshake: `BitTorrent protocol`;
- выставляется BEP-10 reserved bit `reserved[5] & 0x10`;
- проверяются protocol name, Content-ID/info-hash bytes и support extension protocol у remote peer;
- существующий signed extended handshake и `ut_metadata` transfer остаются без изменения;
- `AceLivePeerHandshakeCodec` для live-media peers остаётся `AceStreamProtocol`;
- DHT/tracker budgets, peer target/max, startup/no-peer timeout, request/handshake timeout, scheduler, HTTP/Media3, TS recovery и output buffer не меняются.

## Field gate для #136

Решение считается подтверждённым только после exact-head CI и повторного real Torrent TV sweep на том же 8-channel классе выборки.

Обязательные проверки:

1. core P2P/unit tests + lint/build gates green;
2. real Torrent TV playback smoke без внешнего Ace Stream Engine green;
3. multi-channel sweep не регрессирует ранее успешные live paths;
4. `content_metadata_result success=true` появляется хотя бы на реальном `content_id` sample;
5. при metadata success transport descriptor должен дать derived live swarm key, после чего startup идёт по обычному live path.

Если пункт 4 остаётся false для всех samples, #136 не считается достаточным исправлением только потому, что unit tests зелёные.

## Следующий шаг, только если #136 не даёт real metadata success

Не менять live peer pool вслепую. Следующий отдельный observational increment должен разделить Content-ID resolver по стадиям:

`metadata discovery -> TCP connect -> outer BitTorrent handshake -> BEP-10 extended handshake -> ut_metadata blocks -> transport decode`.

Отдельно фиксировать catalog outcome и metadata-swarm outcome. После этого менять только доказанную сломанную стадию.

Возможные решения после такого evidence:

- нет metadata candidates -> исправлять metadata discovery/tracker/DHT path;
- TCP есть, outer handshake отклоняется -> проверять metadata peer protocol/identity;
- outer handshake проходит, BEP-10 не проходит -> исправлять extended-handshake negotiation;
- `ut_metadata` reject/partial -> исправлять extension-id/framing/block exchange;
- transport bytes получены, decode падает -> исправлять descriptor compatibility;
- transport resolution стабилен, но live startup всё ещё падает -> возвращаться к outcome-aware unseen endpoint acquisition.

## Обновлённый порядок V4d

1. ✅ #131 — bounded pre-handshake live-peer qualification.
2. ✅ #132 — persistent live-peer lifecycle reasons.
3. ✅ #133 — R3 field gate.
4. ✅ #134 — tracker fast path + bounded DHT startup diversity.
5. 🚧 **#136 — Content-ID transport metadata peer-wire correctness.**
6. ⏳ Повторный exact-head 8-channel sweep и сравнение с baseline 3/8 + metadata 0/7.
7. ⏳ Если metadata path всё ещё не работает — stage diagnostics, затем один узкий resolver fix.
8. ⏳ Только после transport-resolution parity — outcome-aware unseen DHT endpoint acquisition, если live lifecycle всё ещё показывает repeated failed endpoints.
9. ⏳ Competitive live-peer acquisition — только если свежих endpoints достаточно, но они реально квалифицируются недостаточно конкурентно.
10. ⏳ Forward reserve / pre-READY pressure / decoder warmup — только после доказанного producing peer и соответствующего buffer/player evidence.

## Инварианты

Не увеличивать как средство обхода проблемы:

- 60-second startup failure bound;
- 30-second no-connected-peer guard;
- live peer target/max;
- DHT query/time budgets;
- handshake/request timeout;
- recovery `maxPieceAdvance`;
- output buffer/cache capacity.

Не вводить HTTP logical-offset resume без реального Range/reopen mismatch. Не добавлять внешний Ace Stream Engine как runtime dependency.
