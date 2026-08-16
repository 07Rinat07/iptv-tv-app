# V4d field execution addendum — 16 августа 2026

Этот файл уточняет текущий блок `Startup/zap latency parity (V4d)` по результатам реальных TV/Android прогонов и сравнения с TorrServer/webtor. Верхнеуровневый порядок проекта не меняется: V4d остаётся blocker перед broad acceptance.

## Текущий gate

- ✅ PR #127 (`weak-swarm startup peer diversity`) — exact-head Android CI #547, merged.
- ✅ PR #128 (`canonical Ace Live startup timeline`) — exact-head Android CI #549, включая real Torrent TV smoke без внешнего Ace Engine, merged.
- ✅ PR #129 (`bounded P2P load telemetry contract`) — Android CI #551 + P2P player smoke #4, merged.
- ✅ PR #130 (`Media3 + localhost reopen telemetry`) — Android CI #553 + P2P player smoke #6, включая real Torrent TV playback без внешнего Ace Engine, merged в `main` как `f3a76bd32edc80cd522e4fca26a78a2587714db8`.
- ✅ PR #131 (`bounded pre-handshake peer qualification`) — exact-head Android CI #557, включая real Torrent TV playback smoke без внешнего Ace Engine, core P2P/unit tests, lint, debug/instrumentation build, signed ARM TV APK/source packaging; squash-merged в `main` как `41a883d9ba6c26321d673c9e636a052580201076`.
- ✅ PR #132 (`persistent Ace peer lifecycle reasons`) — exact-head Android CI #559, включая real Torrent TV playback smoke без внешнего Ace Engine, core P2P/unit tests, lint, debug/instrumentation build, signed ARM TV APK/source packaging; squash-merged в `main` как `ca9f09bb491405b9a9c4397a38292c9a0888c550`.
- ✅ PR #133 (`R3 field gate/docs`) — applicable exact-head Android CI #561, merged в `main` как `a5a1424bbeadf676cf4878a68cd48ceb99dddfaa`.
- ✅ R3 TV Box field validation — выполнен на `main` после #133; lifecycle evidence выбрал ветку **discovery/acquisition**.
- 🚧 Текущий code increment — tracker fast path сохраняет мгновенный первый TCP start, но tracker-only startup больше не считается достаточным diversity только по количеству адресов; существующий bounded DHT `probe -> probe -> full expansion` планируется в фоне и отменяется при media-ready.

R2 подтвердил, что текущий критический путь находится **до Media3/localhost consumption**. R3 дополнительно разделил причины peer failure и показал `CONNECT_FAILED`, `REMOTE_CLOSED`, `HANDSHAKE_TIMEOUT`, а также случай `handshake_accepted + windowUseful=1`, но `producing=0`. Следующий приоритет — улучшать `discovery -> candidate diversity -> dial/handshake -> producing`, не трогая HTTP/Media3/buffer policy без соответствующего evidence.

Ключевой контроль R3: те же каналы в связке **Televizo + Ace Stream Engine** у пользователя обычно стартуют/переключаются примерно за 2–5 секунд. Поэтому source/content ID и наличие живой раздачи нельзя считать первичной причиной только на основании generic 60-second UI error; текущий blocker находится в собственной embedded Ace Live реализации.

## Что подтвердил field-run R2

Успешный retained session (`channelId=3300`) дал канонический startup timeline:

- `transport/direct` — 0 ms;
- `first_candidate/discovery_completed` — 2472 ms;
- `connected` — 5376 ms;
- `handshake` — 5568 ms;
- `useful_window` — 5684 ms;
- `first_media` — 6740 ms;
- `buffer_ready` — 10298 ms;
- `http_reader_open/http_first_read` — 10364 ms.

После выдачи localhost URL Media3 был быстрым:

- `load_started` — 101 ms;
- `READY` — 135 ms;
- `first_video_frame` — 223 ms;
- `first_audio` — 361 ms.

HTTP request был `GET`, `Range=none`, `requested_start=none`. В retained successful session не было доказанного Range/reopen сценария.

На неуспешных каналах наблюдалось другое:

- discovery находил 1–5 candidates;
- TCP периодически достигал `connected=1`;
- `handshaked=0`, `windowUseful=0`, `producing=0` сохранялись;
- до `first_media`, `buffer_ready` и player boundary дело не доходило;
- затем срабатывал существующий абсолютный 60-second preparation timeout.

Следовательно, UI-текст «возможно content ID устарел» по-прежнему не является доказательством stale content ID. Доказанный R2 blocker — отсутствие квалифицированного Ace peer до startup deadline.

## Что подтвердил field-run R3

- часть startup attempts получает `CONNECT_FAILED` на найденных endpoints;
- часть transport sessions заканчивается `REMOTE_CLOSED`;
- часть TCP-connected endpoints не завершает Ace handshake и доходит до `HANDSHAKE_TIMEOUT`;
- минимум один startup достиг `handshake_accepted` и `windowUseful=1`, но не достиг стабильного `producing=1` до общего startup deadline;
- некоторые каналы остаются в `P2P · поиск пиров...`, а Discovery HD отдельно показал отсутствие доступного peer за отведённое время;
- успешные/fallback каналы доказывают, что player/render path сам по себе не является универсальным blocker;
- внешний same-channel контроль через Televizo + Ace Stream Engine подтверждает, что нужно сокращать разрыв именно в embedded acquisition/production path.

R3 decision: сначала исправлять **discovery/acquisition diversity**, затем повторить field-run. HTTP resume, forward reserve и decoder tuning остаются evidence-gated.

## Обновлённая последовательность V4d

1. ✅ **Peer diversity + diagnostic retention (#127).**
   - первый DHT alternative отдаётся сразу;
   - максимум два bounded startup probe-round;
   - volatile peer-quality не вытесняет canonical evidence.

2. ✅ **Canonical runtime startup timeline (#128).**
   - один timeline на preparation;
   - direct/metadata race разделён по milestones;
   - discovery/candidate/connect/handshake/useful/media/buffer/http boundaries не смешиваются.

3. ✅ **Player/HTTP observability (#129 + #130).**
   - bounded Media3 load/error/retry counters;
   - READY, first video frame, first audio;
   - localhost method/Range/requested offset/actual reader start;
   - reader open/first-read/close reason/delivered bytes/lifetime;
   - observational only: playback policy не менялась.

4. ⏸️ **HTTP logical-offset reopen/resume — evidence-gated, сейчас отложен.**
   - latest successful run: `Range=none`;
   - Media3 READY/first-frame после localhost exposure — sub-second;
   - не вводить resume без реального Range/reopen evidence;
   - если future field-run покажет Range/reopen mismatch, вернуться к bounded logical-offset semantics отдельным PR.

5. ✅ **Bounded pre-handshake peer qualification (#131).**
   - measured successful peer прошёл handshake примерно за 192 ms после TCP connect;
   - failed sessions находили candidates и TCP connect, но не достигали handshake;
   - endpoint, который ещё ни разу не прошёл Ace handshake, больше не расходует обычный established-peer reconnect budget;
   - runtime default: `maxPreHandshakeReconnectAttempts=0`;
   - после первого успешного handshake сохранён существующий `maxReconnectAttempts=2`;
   - final pre-handshake failure освобождает ownership в pool/refill path, где уже действует bounded exponential backoff;
   - permanent ban не вводился;
   - absolute 60-second startup bound не увеличен.

6. ✅ **Persistent peer lifecycle reason telemetry (#132).**
   - sparse `connected / connect_failed / handshake_accepted / handshake_rejected / disconnected` экспортируются как `embedded_ace_live_peer_lifecycle`;
   - `connected`: peer id + reconnect attempt;
   - `connect_failed`: peer id + retrying;
   - `handshake_rejected`: peer id + exact reject reason;
   - `disconnected`: exact reason (`HANDSHAKE_TIMEOUT`, `HANDSHAKE_REJECTED`, `REMOTE_CLOSED`, `IO_ERROR`, `PROTOCOL_REJECTED`), retrying, requeued-piece count;
   - lifecycle rows коррелируются через `startup_id` и `elapsed_ms` с canonical startup clock;
   - retry/playback policy не изменена.

7. ✅ **R3 field gate.**
   - выполнен на TV Box после #133;
   - получены lifecycle reasons вместо generic failure;
   - подтверждены одновременно discovery/dial/qualification проблемы;
   - same-channel Televizo + Ace Stream Engine control показывает ориентир порядка 2–5 s и подтверждает жизнеспособность раздач;
   - выбран следующий behavior branch: discovery/acquisition diversity.

8. 🚧 **Tracker-fast-path + bounded DHT startup diversity — текущий increment.**
   - tracker остаётся неблокирующим fast path: initial TCP attempts не ждут DHT;
   - любой tracker-only startup result с хотя бы одним endpoint планирует существующую DHT-only startup sequence в фоне, даже если tracker вернул 4+ addresses;
   - это устраняет ошибочную эвристику `peer count >= 4 == достаточная qualification diversity`;
   - существующие bounds сохраняются: `returnAfterPeers=1`, максимум два 7-second probe rounds, затем bounded full expansion;
   - DHT execution остаётся serialized и heap-guarded;
   - startup-specific DHT отменяется при startup completion;
   - `maxActivePeers`, target peers, global startup/no-peer bounds и DHT query caps не увеличиваются.

9. **Outcome-aware unseen endpoint acquisition — только если следующий field-run снова показывает повтор одних и тех же failed endpoints.**
   - короткий DHT probe не должен считать уже известный failed endpoint достаточным новым diversity evidence;
   - возможный отдельный PR: unseen-endpoint preference / bounded positive-cache bypass во время failing startup;
   - global positive DHT cache не удалять: он защищает от duplicate heavy walks direct/metadata paths;
   - сохранять общий DHT resource budget и heap gate.

10. **Competitive useful-peer qualification — только если свежих endpoints достаточно, но qualification serializes/underutilizes их.**
    - небольшой bounded half-open/alternative dial budget без увеличения `maxActivePeers` и глобальных timeout;
    - diversity считать по handshaked/useful/producing, а не по discovered count;
    - не превращать acquisition в бесконтрольный connect storm.

11. **Forward playback reserve — только если следующий field-run показывает стабильный handshake/useful producer, но playable headroom остаётся недостаточным после player start.**
    - storage capacity и playable bytes ahead — разные величины;
    - поддерживать bounded reserve впереди authoritative consumer cursor;
    - priority gradient: `NOW -> NEXT -> READAHEAD -> PROBE` как собственная Ace Live реализация;
    - чужие TorrServer/webtor constants не копировать как magic values.

12. **Pre-READY pressure authority — только если field telemetry снова показывает parser/socket burst как ложный consumer bitrate.**
    - socket/parser burst до trustworthy READY не считать playback bitrate;
    - использовать producer/media-growth estimate и byte/time floors;
    - consumer-rate становится authoritative только после явного trust transition.

13. **Decoder-safe startup warmup — после стабилизации peer qualification/reserve.**
    - contiguous MPEG-TS, а не только byte-count;
    - использовать существующие V4c guarantees: TS sync, PAT, matching PMT, video PID, random-access/IDR/IRAP evidence;
    - не ослаблять startup failure contract.

14. **Проверить direct soft-window → metadata serialization.**
    - 8-second soft-window остаётся измеряемой гипотезой;
    - не менять его, пока canonical timeline не покажет повторную оплату одинаковых discovery/qualification задержек direct и metadata paths.

15. **Acceptance после V4d.**
    - fixed same-device A/B matrix;
    - p50/p95 startup + zap;
    - success rate + rebuffer evidence;
    - 20 rapid switches;
    - weak network + peer loss;
    - 2h/8h ARM TV Box soak;
    - healthy-swarm target — стремиться к same-device ориентиру порядка 2–4 s без внешнего Ace Engine как runtime dependency.

## R3 decision matrix

- `candidate=0` / DHT+tracker не дают endpoints → **discovery/acquisition**.
- `connected=0` при наличии candidates, lifecycle даёт `CONNECT_FAILED` → **dial/connect acquisition**.
- `connected=1`, затем `HANDSHAKE_TIMEOUT`/`HANDSHAKE_REJECTED` на нескольких endpoints → **peer qualification/diversity**, не HTTP/Media3.
- handshake проходит, но `useful_window=0` → **window/usefulness selection**.
- useful есть, но `producing=0` → **producer acquisition/selection**, не считать handshake конечным успехом startup.
- useful/producing есть, но `first_media` или `buffer_ready` долго не наступают → **scheduler/reserve/output path**.
- localhost exposed быстро, но Media3 делает `Range` reopen mismatch/долгий BUFFERING → **HTTP logical-offset/player boundary**.
- READY быстрый, затем headroom падает до ~1 s/CRITICAL → **forward reserve + post-READY pressure**.

## Не менять вслепую

- 60-second startup failure bound;
- 30-second no-connected-peer guard;
- handshake/request timeout и recovery `maxPieceAdvance`;
- TS discontinuity gate;
- generic IPTV/Media3 policy;
- output buffer capacity/cache size как самостоятельное «лечение»;
- HTTP Range/resume без подтверждённого Range/reopen;
- peer pool max/target или DHT budget без новых измерений.

## References

- [`ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md`](ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md) — clean-room TorrServer/webtor analysis.
- [`ACE_LIVE_FIELD_VALIDATION_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16.md) — first field pass.
- [`ACE_LIVE_FIELD_VALIDATION_2026-08-16_R2.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16_R2.md) — second field pass after #130 and decision to prioritize pre-handshake peer qualification.
- [`ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md`](ACE_LIVE_PEER_LIFECYCLE_TELEMETRY_2026-08-16.md) — exact peer lifecycle diagnostics contract implemented by #132.
- [`ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_R3_GUIDE_2026-08-16.md) — reproducible R3 field checklist and decision gate.
- [`ACE_LIVE_FIELD_VALIDATION_2026-08-16_R3.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16_R3.md) — R3 evidence, Televizo/Ace Engine control and selected acquisition increment.
