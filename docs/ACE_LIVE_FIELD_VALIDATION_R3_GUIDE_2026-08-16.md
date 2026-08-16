# Ace Live V4d — R3 field validation guide

Дата: 16 августа 2026.

Этот прогон выполняется после merge PR #131 и #132. Его цель — не «проверить, стало ли субъективно быстрее», а определить следующий измеряемый blocker по полной цепочке `candidate -> connected -> handshake/reject/disconnect -> useful -> producing -> first_media -> buffer_ready -> localhost -> Media3 READY/frame/audio`.

## Требуемая сборка

Использовать `main` не старее:

`ca9f09bb491405b9a9c4397a38292c9a0888c550`

В этой сборке уже есть:

- bounded pre-handshake qualification: новый endpoint не расходует established-peer reconnect budget до первого успешного Ace handshake;
- sparse persistent `embedded_ace_live_peer_lifecycle` с точными transport/handshake/disconnect reasons;
- canonical startup timeline;
- Media3 load/READY/frame/audio telemetry;
- localhost method/Range/open/close telemetry.

## Сценарии

Провести один непрерывный запуск приложения и экспортировать лог только после завершения всех сценариев.

1. **Healthy baseline**
   - выбрать канал, который стабильно запускался в предыдущем прогоне;
   - дождаться изображения и звука;
   - оставить воспроизведение минимум 20–30 секунд.

2. **Ранее медленный/проблемный канал**
   - выбрать канал, который раньше долго находился на «Подключение к каналу…»;
   - если он не стартует, не прерывать до штатной ошибки/60-second bound;
   - после ошибки перейти к следующему каналу, не перезапуская приложение.

3. **Ещё 2–3 P2P канала разного качества**
   - один успешный или частично успешный;
   - один слабый/неуспешный, если доступен;
   - фиксировать название канала и примерное время запуска только как вспомогательную информацию.

4. **Rapid zap**
   - выполнить минимум 8–10 последовательных переключений P2P-каналов;
   - не ждать минуту на каждом, если предыдущий уже явно запущен; после READY/кадра переключать дальше;
   - один неуспешный канал желательно довести до его штатного failure outcome.

## Что должно попасть в экспорт

Обязательные structured statuses:

- `embedded_ace_live_startup_timeline`
- `embedded_ace_live_peer_lifecycle`
- `embedded_ace_live_peer_discovery`
- `embedded_ace_live_peer_quality`
- `embedded_ace_live_buffer_pressure`
- `embedded_ace_live_loopback_http_lifecycle`
- `P2pBoundaryLoad`
- `player_p2p_boundary`
- `player_ready`
- `player_resolve_error`
- `embedded_ace_live_resolve_error`

Для `embedded_ace_live_peer_lifecycle` особенно важны:

- `connected` + reconnect attempt;
- `connect_failed` + `retrying`;
- `handshake_accepted`;
- `handshake_rejected` + reject reason;
- `disconnected` + exact reason: `HANDSHAKE_TIMEOUT`, `HANDSHAKE_REJECTED`, `REMOTE_CLOSED`, `IO_ERROR`, `PROTOCOL_REJECTED`;
- requeued-piece count;
- `startup_id` и `elapsed_ms`.

## Как интерпретировать R3

### A. Discovery/acquisition blocker

Признаки:

- `candidate=0`, либо tracker/DHT дают слишком мало endpoints до существенной части startup deadline;
- нет meaningful `connected` lifecycle rows.

Следующий PR должен улучшать discovery/acquisition. Не трогать HTTP, Media3 или buffer reserve.

### B. Dial/connect blocker

Признаки:

- candidates есть;
- lifecycle показывает повторяющиеся `connect_failed`;
- handshake не начинается.

Следующий PR должен быть bounded dial/acquisition increment. Не увеличивать глобальный 60-second timeout.

### C. Pre-handshake qualification/diversity blocker

Признаки:

- несколько endpoints доходят до `connected`;
- затем фиксируются `HANDSHAKE_TIMEOUT`, `HANDSHAKE_REJECTED` или ранний `REMOTE_CLOSED`;
- мало или нет `handshake_accepted`.

Тогда следующий PR — небольшой bounded competitive alternative/half-open budget, чтобы не сериализовать квалификацию плохих endpoints. Не увеличивать `maxActivePeers` вслепую.

### D. Useful-window blocker

Признаки:

- handshake accepted есть;
- `windowUseful=0` устойчиво;
- producing отсутствует.

Тогда следующий PR должен исправлять usefulness/window selection, а не player layer.

### E. Scheduler/reserve/output blocker

Признаки:

- handshaked/useful/producing peer есть;
- `first_media` или `buffer_ready` всё равно запаздывают;
- либо после старта playable headroom быстро остаётся около ~1 секунды/CRITICAL.

Тогда отдельно исследовать forward reserve, priority gradient и pressure authority.

### F. HTTP/Media3 blocker

Признаки:

- `buffer_ready` и localhost exposure происходят вовремя;
- Media3 долго остаётся BUFFERING или делает reopen;
- появляется `Range` и requested/actual offset mismatch.

Только в этом случае возвращаться к bounded logical-offset HTTP resume.

## Что не менять до анализа R3

- absolute 60-second startup bound;
- no-connected-peer guard;
- handshake timeout;
- peer target/max pool size;
- DHT budgets;
- request depth/recovery maxPieceAdvance;
- TS auth/resync/discontinuity;
- output-buffer/cache capacity;
- generic IPTV/Media3 policy;
- HTTP Range/resume без фактического Range/reopen evidence.

## Результат

После R3 сохранить экспортированный лог и приложить его к следующему анализу. Выбор следующего behavior PR должен быть однозначно привязан к наблюдаемой категории A–F выше; если evidence противоречив, сначала расширяется diagnostics, а не меняется runtime policy.
