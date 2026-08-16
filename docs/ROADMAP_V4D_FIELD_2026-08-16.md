# V4d field execution addendum — 16 августа 2026

Этот файл уточняет текущий блок `Startup/zap latency parity (V4d)` по результатам реальных TV/Android прогонов и сравнения с TorrServer/webtor. Верхнеуровневый порядок проекта не меняется: V4d остаётся blocker перед broad acceptance.

## Текущий gate

- ✅ PR #127 (`weak-swarm startup peer diversity`) — exact-head Android CI #547, merged.
- ✅ PR #128 (`canonical Ace Live startup timeline`) — exact-head Android CI #549, включая real Torrent TV smoke без внешнего Ace Engine, merged.
- ✅ PR #129 (`bounded P2P load telemetry contract`) — Android CI #551 + P2P player smoke #4, merged.
- ✅ PR #130 (`Media3 + localhost reopen telemetry`) — Android CI #553 + P2P player smoke #6, включая real Torrent TV playback без внешнего Ace Engine, merged в `main` как `f3a76bd32edc80cd522e4fca26a78a2587714db8`.

Второй field-run после PR #130 показал, что текущий критический путь находится **до Media3/localhost consumption**. Поэтому следующий узкий V4d-инкремент — **bounded pre-handshake peer qualification**.

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

Следовательно, UI-текст «возможно content ID устарел» по-прежнему не является доказательством stale content ID. Текущий доказанный blocker — отсутствие квалифицированного Ace peer до startup deadline.

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

5. 🚧 **Текущий инкремент: bounded pre-handshake peer qualification.**
   - измеренный successful peer прошёл handshake примерно за 192 ms после TCP connect;
   - failed sessions находили candidates и TCP connect, но не достигали handshake;
   - endpoint, который ещё ни разу не прошёл Ace handshake, не должен расходовать обычный established-peer reconnect budget;
   - runtime default: `maxPreHandshakeReconnectAttempts=0`;
   - после первого успешного handshake сохранить существующий `maxReconnectAttempts=2`;
   - final pre-handshake failure немедленно освобождает ownership в pool/refill path, где уже действует bounded exponential backoff;
   - permanent ban не вводить;
   - absolute 60-second startup bound не увеличивать.

6. **Peer lifecycle reason telemetry.**
   - отдельным observational increment экспортировать sparse `connected / connect_failed / handshake accepted/rejected / disconnected`;
   - фиксировать `HANDSHAKE_TIMEOUT`, reject reason, remote close, retrying, requeued pieces и startup elapsed;
   - не превращать diagnostics в новый retry policy.

7. **Competitive useful-peer acquisition — только если R2 qualification недостаточно.**
   - оценивать `candidate -> dial -> connected -> handshake -> useful -> producing`;
   - при необходимости добавить небольшой bounded half-open/alternative budget;
   - diversity считать по handshaked/useful/producing, а не по discovered count;
   - не увеличивать max peer pool и глобальные timeout вслепую.

8. **Forward playback reserve — после стабилизации peer qualification.**
   - storage capacity и playable bytes ahead — разные величины;
   - поддерживать bounded reserve впереди authoritative consumer cursor;
   - priority gradient: `NOW -> NEXT -> READAHEAD -> PROBE` как собственная Ace Live реализация;
   - чужие TorrServer/webtor constants не копировать как magic values.

9. **Pre-READY pressure authority.**
   - socket/parser burst до trustworthy READY не считать playback bitrate;
   - использовать producer/media-growth estimate и byte/time floors;
   - consumer-rate становится authoritative только после явного trust transition.

10. **Decoder-safe startup warmup.**
    - contiguous MPEG-TS, а не только byte-count;
    - использовать существующие V4c guarantees: TS sync, PAT, matching PMT, video PID, random-access/IDR/IRAP evidence;
    - не ослаблять startup failure contract.

11. **Проверить direct soft-window -> metadata serialization.**
    - 8-second soft-window остаётся измеряемой гипотезой;
    - не менять его, пока peer-qualification blocker не устранён и новый canonical timeline не покажет необходимость.

12. **Acceptance после V4d.**
    - fixed same-device A/B matrix;
    - p50/p95 startup + zap;
    - success rate + rebuffer evidence;
    - 20 rapid switches;
    - weak network + peer loss;
    - 2h/8h ARM TV Box soak;
    - healthy-swarm target — стремиться к same-device ориентиру порядка 2–4 s без внешнего Ace Engine как runtime dependency.

## Не менять вслепую

- 60-second startup failure bound;
- 30-second no-connected-peer guard;
- request timeout и recovery `maxPieceAdvance`;
- TS discontinuity gate;
- generic IPTV/Media3 policy;
- output buffer capacity/cache size как самостоятельное «лечение»;
- HTTP Range/resume без подтверждённого Range/reopen;
- peer pool max/target или DHT budget без новых измерений.

## References

- [`ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md`](ACE_LIVE_EXTERNAL_STREAMING_REFERENCES_2026-08-16.md) — clean-room TorrServer/webtor analysis.
- [`ACE_LIVE_FIELD_VALIDATION_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16.md) — first field pass.
- [`ACE_LIVE_FIELD_VALIDATION_2026-08-16_R2.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16_R2.md) — second field pass after #130 and the decision to prioritize pre-handshake peer qualification.
