# V4d field execution addendum — 16 августа 2026

Этот файл уточняет текущий блок `Startup/zap latency parity (V4d)` из основного ROADMAP по результатам TV Box прогонов и сравнения с открытыми streaming reference TorrServer/webtor. Верхнеуровневый порядок проекта не меняется: V4d остаётся blocker перед broad acceptance.

## Текущий gate

- PR #127 (`weak-swarm startup peer diversity`) полностью прошёл exact-head Android CI #547 и был squash-merged в `main` как `aee83cbe76a1f7dbdbe43728290fbf308ce2415e`.
- PR #128 (`canonical Ace Live startup timeline`) полностью прошёл exact-head Android CI #549, включая real Torrent TV playback smoke без внешнего Ace Engine, core P2P и остальные unit-модули, lint, debug/instrumentation build, signed ARM TV APK и source packaging; затем был squash-merged в `main` как `dea23c65a2b6c0865f91870806a4db53e5b0d0f3`.
- PR #129 (`bounded P2P load telemetry contract`) прошёл exact-head Android CI #551 и отдельный P2P player smoke #4 с real Torrent TV playback без внешнего Ace Engine; затем был squash-merged в `main` как `aa27a73cdd4740af3e70fa903df6c7cd1f3dcb00`.

Текущий узкий инкремент V4d — **real Media3 + localhost reopen telemetry wiring**. Он остаётся observational: никаких timeout, scheduler/recovery, buffer-policy, TS-discontinuity, HTTP resume или generic IPTV/Media3 policy изменений.

## Обновлённая последовательность V4d

1. ✅ **PR #127 — peer diversity + diagnostic retention.**
   - первый startup DHT alternative освобождается сразу, без ожидания batch из четырёх;
   - максимум два bounded startup probe-round;
   - volatile `windowUseful/producing` не вытесняют lifecycle/timeline evidence;
   - никаких увеличений глобальных timeout/recovery bounds.

2. ✅ **PR #128 — canonical runtime startup timeline.**
   - один timeline на playback preparation, общий для direct/metadata race;
   - authoritative milestones: `transport_selection -> direct/metadata -> discovery_completed -> first_candidate -> connected -> handshake -> useful_window -> first_media -> buffer_ready -> http_reader_open -> http_first_read`;
   - `discovery_completed` не означает наличие candidate;
   - `first_media` фиксируется только после auth/resync и принятия bytes live-output buffer;
   - reconnect/refill/reopen не переписывают first-occurrence timestamps;
   - runtime startup clock, управляющий policy/guards, не заменён diagnostics clock.

3. 🚧 **Player/HTTP boundary evidence — только наблюдение.**

   **3a. ✅ PR #129 — P2P player load telemetry contract.**
   - типизированные `load_started`, `load_completed`, `load_error`, `load_retry`;
   - первый успешный start/completion не должен flood-ить bounded diagnostics на каждом live chunk;
   - error/retry остаются наблюдаемыми и несут counters;
   - rebuffer/READY/first-frame semantics сохраняются без изменения playback policy.

   **3b. 🚧 Реальное Media3 + localhost wiring — текущий инкремент.**
   - подключить Media3 1.5.1 AnalyticsListener load callbacks только для P2P localhost session;
   - оставить bounded `player_p2p_boundary` event и писать task/position/length/bytes/duration/retry detail в persistent `P2pBoundaryLoad`;
   - localhost request method, raw `Range`, parsed requested offset и фактический retained-floor reader offset;
   - reader open/first-read/close, delivered bytes, lifetime и close reason;
   - сохранить `BUFFERING/READY`, first rendered video frame и добавить first-audio evidence из реального audio-position advancement;
   - сопоставлять player evidence через existing session/request correlation, не подменяя core preparation clock;
   - `Range` в этом инкременте только наблюдается: сервер всё ещё не делает logical-offset resume;
   - не менять generic IPTV player path.

4. **Bounded live HTTP reopen/resume semantics.**
   - сначала подтвердить фактическое Media3 поведение логами после 3b;
   - если player делает Range/reopen, продолжать логический stream с подтверждённого offset, а не молча открывать новый reader с текущего retained floor;
   - expired live range должен иметь явный bounded outcome/recovery path;
   - обычный generic IPTV HTTP path не затрагивать.

5. **Forward playback reserve как отдельный invariant.**
   - не путать размер sliding storage с реально playable bytes ahead;
   - поддерживать bounded запас впереди authoritative consumer cursor;
   - ориентироваться на bytes, estimated duration и фактическую live piece geometry;
   - рабочее направление priority gradient: `NOW -> NEXT -> READAHEAD -> PROBE`, реализованный самостоятельно под Ace Live.

6. **Pre-READY pressure authority.**
   - до trustworthy player-ready transition socket/parser read rate не является authoritative playback bitrate;
   - pre-READY использовать producer/media-growth estimate и byte/time floors;
   - после READY consumer-rate подключается к pressure model через явный trust transition, а не автоматически после первого burst-read.

7. **Competitive useful-peer acquisition.**
   - измерять `candidate -> dial -> connected -> handshake -> useful -> producing`;
   - держать небольшой bounded half-open/alternative candidate budget;
   - принимать решение о diversity по handshaked/useful/producing evidence, не по `discovered` count;
   - если после PR #127 поле всё ещё показывает один useful producer, расширять конкурентный dial отдельным узким инкрементом.

8. **Decoder-safe startup warmup.**
   - накопление не только byte-count, но contiguous MPEG-TS;
   - использовать существующие V4c guarantees: TS sync, PAT, matching PMT, video PID и random-access/IDR/IRAP evidence;
   - localhost/player start остаётся bounded существующим startup failure contract.

9. **Проверить direct soft-window -> metadata serialization.**
   - сохраняется как измеряемая гипотеза;
   - 8-second soft-window не менять до полного field timeline;
   - если подтверждено, metadata/direct startup должны конкурировать за полезный runtime progress, а не последовательно платить одинаковые discovery delays.

10. **Acceptance после закрытия V4d.**
    - fixed same-device A/B matrix на одних и тех же Torrent TV каналах;
    - p50/p95 startup и zap;
    - success rate и rebuffer evidence;
    - 20 rapid switches;
    - weak network и peer loss;
    - 2h/8h ARM TV Box soak;
    - healthy-swarm target — стремиться к наблюдаемому same-device ориентиру порядка 2–4 s без внешнего Ace Engine как runtime dependency.

## Подтверждённые field invariants

- `discovered=4` не означает четыре полезных peers: в последнем окне устойчиво был только один `handshaked` producer;
- authoritative playable headroom держался около `458656` bytes, примерно около одной секунды media, и оставался `CRITICAL`;
- consumer cursor продолжал двигаться и `fell_behind=false`, поэтому симптом нельзя объяснить только отвалом HTTP reader;
- UI timeout-текст про «возможно устаревший content ID» не является доказательством stale metadata;
- pre-READY parser/extractor burst может давать ложные consumer-rate оценки в десятки/сотни Mbit/s;
- прошлый pattern `reader #1 долго BUFFERING -> reader #2 -> почти сразу READY` делает reopen/resume semantics обязательным объектом измерения.

## Не менять вслепую

- 60-second startup failure bound;
- 30-second no-connected-peer guard;
- request timeout и recovery `maxPieceAdvance`;
- TS discontinuity gate;
- generic IPTV/Media3 policy;
- output buffer capacity или cache size как самостоятельное «лечение» starvation;
- чужие TorrServer/webtor buffer/readahead значения как magic constants.

## Внешние reference и clean-room правило

TorrServer GPLv3 используется только как архитектурный reference; его код в проект не переносится. Webtor MIT используется как технический reference, но Ace Live решения всё равно проектируются под собственный Kotlin runtime. Подробный разбор: [`ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md`](ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md).

Полевые измерения: [`ACE_LIVE_FIELD_VALIDATION_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16.md).
