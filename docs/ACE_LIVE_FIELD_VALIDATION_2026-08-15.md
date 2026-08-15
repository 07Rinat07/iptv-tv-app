# Ace Live field validation — 15 августа 2026

## Контекст

Полевой прогон выполнен на реальном Android-устройстве/TV Box path без обязательного внешнего Ace Stream Engine. Для поведенческого A/B benchmark на том же устройстве используются Televizo + Ace Stream Engine: здоровые Torrent TV каналы там обычно стартуют и переключаются примерно за 2–4 секунды. Это benchmark, а не runtime-зависимость проекта.

Источник наблюдений: экспорт structured diagnostics `myscanerIPTV-logs-1786816983118.txt` из сборки с V4a/V4b telemetry и текущим autonomous Ace Live runtime.

## Подтверждённые наблюдения

### 1. Основная задержка не сводится к Media3 startup buffer

Для channelId=13 autonomous runtime открыл первый localhost reader при `elapsed_ms=29587` и уже имел `retained_bytes=6814400`. `player_start` произошёл практически одновременно с этим open/read, но `player_ready` пришёл только через `startupMs=66391`.

В результате от начала embedded runtime до READY прошло примерно 95.9 секунды. Это существенно хуже целевого A/B поведения 2–4 секунды и нельзя считать приемлемым acceptance baseline.

### 2. Первый Media3 reader долго потребляет поток без READY

Первый reader успел доставить около 55.6 MiB до момента, когда открылся второй reader. На протяжении этого периода authoritative buffer telemetry почти постоянно показывала `CRITICAL` и всего около `458656` playable bytes. После появления consumer-rate эта величина соответствовала примерно 46–303 ms playable headroom.

При этом peer telemetry показывала в основном только один connected/handshaked/unchoked peer. Его `windowUseful/producing` состояние переключалось между useful/producing и zero-production с паузами в несколько секунд. Aggregate production в активные моменты был высоким, но непрерывного запаса у reader не формировалось.

### 3. Второй reader является важным диагностическим переломом

Второй localhost reader открылся при `elapsed_ms=95874`, когда retained live buffer уже составлял `16774144` bytes. Новый reader быстро вычитал накопленный retained window, и `player_ready` появился примерно через 14 ms после его открытия.

Это сильный признак, что длительная часть `player_start → READY` связана не только с количеством загруженных P2P-байтов. Нужно явно измерить причину закрытия/повторного открытия первого HTTP reader, Media3 load/retry state, request/range semantics и extractor/track readiness.

### 4. Текущий consumer-rate до READY нельзя считать надёжной playback-rate оценкой

Сразу после HTTP open Media3 быстро вычитывает уже накопленный retained window. В логе из-за этого consumer-rate кратковременно оценивается десятками и даже сотнями Mbit/s, например 79.659 Mbit/s и 310.632 Mbit/s. Это скорость parser/read burst, а не фактический bitrate воспроизводимого телеканала.

Следовательно, duration-based `CRITICAL/LOW/TARGET/HIGH` до подтверждённого player READY может переоценивать consumption rate и держать scheduler в постоянном `CRITICAL`. До READY такой сигнал должен рассматриваться как suspect и отдельно проверяться/ограничиваться; менять bounds без подтверждения нельзя.

### 5. Rapid zap упирается в полезность первых peers

Для следующих channelId=14/15/16 initial tracker discovery возвращал один endpoint быстро — примерно за 176–355 ms. Но этот endpoint не успевал стать handshaked/useful producer. Для последнего запроса startup DHT probe занял около 4316 ms и снова вернул только одного peer.

То есть сам tracker response может быть быстрым, но «первый найденный endpoint» не равен «полезный media-producing peer». Для целевого zap 2–4 s резервный discovery/connection path должен быть конкурентным и ранним, а не зависеть от одного слабого endpoint или последовательного многосекундного fallback.

## Рабочая гипотеза по startup latency

Ниже — вывод из сочетания runtime-кода и полевого timeline, а не отдельный непосредственно залогированный event.

Текущий `content_id` startup держит metadata transport в ожидании, пока speculative direct runtime не завершится или не истечёт 8-second direct soft window. После выбора metadata runtime создаётся заново и начинает собственный tracker/DHT startup. В сценарии, где direct swarm не даёт media, а metadata transport уже доступен, это способно сериализовать до ~8 секунд speculative ожидания перед новым discovery path.

Полевой timeline channelId=13 совместим с такой схемой: значимая peer activity появляется только через десятки секунд, а после получения действительно useful peer handshake/window/media начинают развиваться быстро. Для доказательства нужны отдельные structured timestamps `direct_started/direct_cancelled`, `metadata_resolved`, `metadata_runtime_started`, `first_candidate`, `first_connected`, `first_handshake`, `first_window_useful`, `first_media`.

## V4d — startup/zap latency parity blocker

V4d должен выполняться после V4c и до широкого acceptance/soak.

1. Добавить end-to-end structured startup timeline для каждого playback request: play request → transport selection → tracker/DHT result → first candidate → connect → handshake → useful window → first authenticated/output media → startup buffer ready → HTTP open/read/close/reopen → Media3 READY/first frame.
2. Добавить loopback reader lifecycle telemetry: method/range/request position, open reason, close reason, delivered bytes, duration и correlation с Media3 load/retry.
3. Добавить Media3 P2P load diagnostics для load error/retry/reconnect и extractor/track readiness, не затрагивая generic IPTV path.
4. Проверить и устранить сериализованный direct-soft-window → metadata-runtime startup там, где metadata transport уже actionable. Оптимизация не должна ослаблять absolute startup/no-peer/stall bounds и stale-session ownership.
5. Не использовать pre-READY parser/read burst как безусловную playback consumption rate. До подтверждённого READY сравнить byte-headroom, producer rate и bounded consumer-rate estimator; post-READY authoritative reader semantics сохранить.
6. Ускорить получение нескольких полезных startup candidates: tracker fast path остаётся, но один непроверенный endpoint не должен задерживать ранний конкурентный DHT/alternative-peer path. Не увеличивать discovery timeout как способ «лечения» latency.
7. На fixed same-device A/B matrix измерять autonomous app и Televizo + Ace Engine на одних и тех же content IDs. Целевой ориентир здорового swarm — приблизиться к 2–4 s zap/start и не иметь систематически более низкую долю успешных запусков.
8. Только после устранения startup/zap blocker переходить к 20 rapid switches, weak-network, peer-loss и 2h/8h soak acceptance.

## Safety invariants V4d

- внешний Ace Stream Engine не становится runtime fallback или release dependency;
- обычный IPTV и ordinary BitTorrent path не меняются без отдельного доказательства;
- stale-session/request ownership из PR #103/#105 сохраняется;
- абсолютные startup/no-connected-peer/media-stall bounds не увеличиваются;
- recovery `maxPieceAdvance`, ownership/requeue и V4c discontinuity gate не ослабляются;
- любые scheduler/refill изменения должны оставаться bounded и иметь regression tests.

## Acceptance evidence после V4d

Минимальный полевой отчёт должен содержать для каждого тестового content ID: результат запуска, `play_request → first frame/READY`, transport-selection latency, peer-discovery latency, first useful peer latency, first-media latency, HTTP reader count/reopen reason, rebuffer count/duration и сравнение с A/B benchmark на том же устройстве и сети.
