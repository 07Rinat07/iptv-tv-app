# V4d Content-ID transport resolution gate — 16–17 августа 2026

## Почему приоритет изменён

После PR #134 был выполнен отдельный real Torrent TV sweep на свежем `main` (`0baa1939dbe1782fbacbd28a91876db5027b8dfb`) без внешнего Ace Stream Engine.

Первичный post-#134 baseline:

- 8 реальных источников;
- 3 источника дошли до Media3 READY + first frame;
- 5 источников завершились существующим 60-second preparation timeout;
- единственный явно заданный `infohash` sample успешно воспроизвёлся;
- все 7 `content_id` samples дали `content_metadata_result success=false`;
- два из этих семи `content_id` всё же воспроизвелись только через speculative direct-live race.

Следовательно, после #134 нельзя считать следующий основной blocker только проблемой количества live peers. Для общего `content_id` path сначала должен надёжно работать переход:

`content_id -> transport descriptor -> derived live swarm key -> live peer acquisition`.

`content_id` и live swarm key не считаются взаимозаменяемыми по умолчанию. Direct use `content_id` как live swarm key остаётся speculative fast path, а не authoritative transport resolution.

## Первый protocol mismatch, закрытый в #136

До #136 `AceContentMetadataPeerResolver` описывал обмен как BEP-9 `ut_metadata`, но открывал metadata peer соединение через live-media outer handshake `AceStreamProtocol`.

Metadata swarm и Ace live-media swarm теперь имеют разные outer-handshake boundaries:

- metadata peer outer handshake: стандартный `BitTorrent protocol`;
- выставляется BEP-10 extension bit;
- remote handshake проверяется по protocol name, ожидаемому 20-byte content identity/info-hash и extension support;
- live-media peer path продолжает использовать `AceStreamProtocol` и не изменён.

Exact-head Android CI #581 для первой версии #136 был зелёным, включая real Torrent TV playback smoke без внешнего Ace Engine.

## Что показал следующий diagnostic sweep

Повторный multi-channel sweep после исправления outer handshake подтвердил прогресс, но не прошёл production field gate:

- known-infohash control снова дошёл до playback;
- один `content_id` sample также дошёл до playback через speculative direct-live path;
- остальные content-id samples завершились bounded timeout;
- metadata peer теперь доходил до `peer_accepted` и BEP-10 extended handshake;
- реальная ошибка сместилась с outer-handshake mismatch на `Peer metadata handshake did not advertise metadata_size`;
- genuine `content_metadata_result success=true` по-прежнему не появился.

Это важно: визуально воспроизведённый `content_id` sample сам по себе не доказывает исправление metadata resolver, потому что direct-live race может выиграть независимо от metadata transport resolution.

## Текущий bounded fix

Полевые peers рекламируют `m.ut_metadata`, но часть из них не включает `metadata_size` в extended handshake. BEP-9 data message для metadata piece содержит `total_size` с теми же size semantics, поэтому resolver теперь поддерживает совместимый bounded fallback:

1. если handshake содержит валидный `metadata_size`, сохраняется прежний bounded multi-piece flow;
2. если `ut_metadata` есть, но `metadata_size` отсутствует, resolver запрашивает только piece 0;
3. первый `ut_metadata` data response обязан сообщить `total_size`;
4. `total_size` проверяется тем же upper bound, после чего выделяется точный буфер;
5. piece 0 валидируется и копируется;
6. только после этого запрашиваются оставшиеся pieces;
7. missing/invalid `total_size`, reject, partial piece или изменение размера остаются hard failure этого peer.

Regression test покрывает реальный compatibility case: extended handshake без `metadata_size`, затем piece 0 с `total_size`, после чего descriptor успешно декодируется.

## Что намеренно не меняется

- live Ace peer wire и live handshake;
- live peer target/max и refill policy;
- DHT query/time budgets;
- 60-second preparation deadline и no-connected-peer guard;
- handshake/request timeout live runtime;
- scheduler/request depth/replacement/recovery;
- TS auth/resync/discontinuity gate;
- HTTP Range/resume и Media3 policy;
- output buffer/cache capacity;
- ordinary IPTV и ordinary BitTorrent;
- внешний Ace Stream Engine не становится runtime dependency или fallback.

## Merge gate для #136

#136 разрешено merge только при одновременном выполнении двух условий:

1. exact-head Android CI полностью зелёный: real Torrent TV smoke без Ace Engine, lint, core P2P/unit tests, debug/instrumentation build, signed ARM TV APK/source packaging;
2. свежий diagnostic multi-channel sweep показывает хотя бы один **genuine metadata/catalog success** для реального `content_id`, а known-infohash control не регрессирует.

Diagnostic sweep branch не является production-кодом и не должен merge в `main`.

## Следующее решение после field gate

- Если metadata resolution начинает успешно возвращать transport descriptor, #136 закрывает текущий Content-ID transport blocker. Затем live startup снова оценивается по derived live swarm key и lifecycle evidence.
- Если metadata peers теперь отвечают data frames, но descriptor decode падает, следующий PR должен быть только descriptor-compatibility fix.
- Если peers рекламируют `ut_metadata`, но не отвечают даже на bounded piece-0 request, следующий PR должен разбирать extension-id/framing/request compatibility, не live peer pool.
- Только после transport-resolution parity разрешено возвращаться к outcome-aware unseen endpoint acquisition / competitive live-peer qualification, если свежий live lifecycle по-прежнему показывает repeated failed endpoints.
- Forward reserve, pre-READY pressure и decoder warmup остаются gated на наличие стабильного useful/producing peer evidence.

## Обновлённый порядок V4d

1. ✅ #131 — bounded pre-handshake live-peer qualification.
2. ✅ #132 — persistent live-peer lifecycle reasons.
3. ✅ #133 — R3 field gate.
4. ✅ #134 — tracker fast path + bounded DHT startup diversity.
5. 🚧 #136 — Content-ID metadata transport correctness:
   - ✅ standard BitTorrent/BEP-10 outer handshake;
   - ✅ field evidence дошёл до extended handshake;
   - ✅ bounded BEP-9 `total_size` fallback добавлен;
   - ⏳ fresh exact-head CI;
   - ⏳ fresh multi-channel field sweep with genuine metadata success.
6. ⏳ Если transport resolution всё ещё не проходит — один узкий fix по фактической stage evidence.
7. ⏳ После transport-resolution parity — outcome-aware unseen endpoint acquisition, только если live lifecycle требует этого.
8. ⏳ Competitive live-peer acquisition — только если свежих endpoints достаточно, но qualification underutilizes их.
9. ⏳ Forward reserve / pre-READY pressure / decoder warmup — только по соответствующей producer/buffer/player telemetry.
10. ⏳ Broad acceptance — fixed TV Box matrix, rapid-zap, weak-network/peer-loss и 2h/8h ARM soak.

## Инварианты

Не увеличивать как средство обхода проблемы:

- 60-second startup failure bound;
- 30-second no-connected-peer guard;
- live peer target/max;
- DHT query/time budgets;
- handshake/request timeout;
- recovery `maxPieceAdvance`;
- output buffer/cache capacity.

Не вводить HTTP logical-offset resume без реального Range/reopen mismatch. Не считать generic UI-текст о stale content ID доказательством stale source. Не добавлять внешний Ace Stream Engine как runtime dependency.
