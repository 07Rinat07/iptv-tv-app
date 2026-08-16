# V4d field execution addendum — 16 августа 2026

Этот файл уточняет текущий блок `Startup/zap latency parity (V4d)` из основного ROADMAP по результатам второго TV Box прогона и последующего сравнения с открытыми streaming reference TorrServer/webtor. Верхнеуровневый порядок проекта не меняется: V4d остаётся blocker перед broad acceptance.

## Текущий gate

PR #127 (`weak-swarm startup peer diversity`) остаётся первым незавершённым шагом. Первый exact-head Android CI run прошёл real Torrent TV playback smoke без внешнего Ace Engine, lint и предыдущие unit-модули, но остановился на `Unit tests core P2P`: legacy assertion в `AceLivePeerDiscoveryFastPathTest` всё ещё ожидал старое значение startup DHT probe batch `4`, хотя новый контракт намеренно освобождает первый альтернативный DHT peer при `1`. Тест синхронизирован с новым bounded контрактом; merge разрешён только после нового полного exact-head green gate.

## Обновлённая последовательность V4d

1. **Закрыть PR #127 и peer-quality diagnostics retention.**
   - первый startup DHT alternative освобождается сразу, без ожидания batch из четырёх;
   - максимум два bounded startup probe-round;
   - volatile `windowUseful/producing` не вытесняют lifecycle/timeline evidence;
   - никаких увеличений глобальных timeout/recovery bounds.

2. **Canonical runtime startup timeline.**
   - один timeline object на playback preparation;
   - timestamps: `play_request -> direct/metadata -> discovery -> first candidate -> dial/TCP connected -> handshake -> useful window -> first media -> startup buffer ready -> localhost exposed/open -> first HTTP read`;
   - reconnect/reopen не переписывает first-occurrence timestamps.

3. **Отдельный player-layer timeline.**
   - Media3 load start/error/retry;
   - localhost request method, `Range`, logical start offset;
   - reader open/first-read/close и close reason;
   - `BUFFERING/READY`, first rendered video frame и first audio evidence, если API даёт надёжный signal;
   - этот инкремент не меняет P2P scheduler.

4. **Bounded live HTTP reopen/resume semantics.**
   - сначала подтвердить фактическое Media3 поведение логами;
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
   - использовать уже существующие V4c guarantees: TS sync, PAT, matching PMT, video PID и random-access/IDR/IRAP evidence;
   - localhost/player start остаётся bounded существующим startup failure contract.

9. **Проверить direct soft-window -> metadata serialization.**
   - сохраняется как измеряемая гипотеза;
   - 8-second soft-window не менять до появления полного timeline, доказывающего сериализованный penalty;
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
