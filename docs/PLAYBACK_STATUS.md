# Статус воспроизведения

Актуальный срез: **20 августа 2026 года**.

## Главный приоритет

Автономный встроенный Ace Live/P2P runtime остаётся главным техническим приоритетом. Внешний Ace
Stream Engine используется только как A/B benchmark и не является runtime dependency или fallback.

Цепочка проверки разделена на независимые границы:

`DHT/tracker → TCP → handshake → useful/unchoked → media output → loopback → Media3 READY/frame`.

Количество найденных endpoint не считается успехом. Успех startup — подтверждённый media output и
готовый плеер в пределах общего bounded deadline.

## Что подтверждено

- Обычный IPTV работает через Media3; LibVLC fallback разрешён только для container/demux/codec
  класса, а не для сетевых/P2P ошибок.
- Magnet/infohash/.torrent обслуживает встроенный libtorrent backend.
- Torrent TV `content_id` и Ace Live identities обслуживает встроенный runtime.
- Adaptive buffer, request scheduling, peer-quality accounting, generation ownership и loopback
  lifecycle находятся в `main` до этого инкремента.
- PR #155 добавил bounded startup DHT candidate batch.
- Terminal pool guard из PR #158 корректно закрывает late-start race, но сам по себе не устраняет
  startup stall.

## Новый лог 20.08.2026

Подробный разбор: [`testing/playback-log-analysis-2026-08-20.md`](testing/playback-log-analysis-2026-08-20.md).

Подтверждены три разных дефекта:

1. перспективный direct-runtime с connect на 7.810 с и handshake на 8.007 с отменялся фиксированным
   metadata handoff около 8 секунд;
2. instance-local warm DHT не переживал создание нового production discovery/runtime, а bootstrap
   DNS выполнялся до KRPC query loop;
3. отменённые каналы оставались `SEARCHING` в UI-кэше и создавали ложное впечатление нескольких
   одновременно зависших поисков.

Отдельно подтверждён player blocker: один поток отдал Media3 около 20,97 МБ за 32,4 секунды без load
error, но READY появился только после EOF при переключении. Это нельзя исправлять увеличением peer
timeout или считать доказательством отсутствия пиров.

Follow-up лог `myscanerIPTV-logs-1787221389074.txt` подтвердил использование warm DHT contacts, но
нашёл второй fixed-timeout edge: fallback direct после metadata failure успел пройти
connect/handshake/useful/unchoked и был отменён примерно через 0.4 секунды. Этот retry теперь также
получает только при текущем qualification один bounded двухсекундный grace внутри общего 60 с.

## Текущий bounded fix

- Engine-owned TTL/LRU routing memory переиспользуется новыми live и metadata DHT wrappers.
- В память попадает только ответивший KRPC node с совпавшим remote node ID; ошибки и mismatch удаляют
  remembered contact. Node-ID и IPv4-network diversity ограничивают повторение одного источника.
- Warm KRPC идёт параллельно с глобально bounded bootstrap DNS pipeline. Bootstrap сохраняет lane и
  query token, а первый wave распределяется по hostname до дополнительных IP одного оператора.
- Direct-runtime получает только один двухсекундный grace, если его **текущее** runtime-состояние
  показывает свежий connect либо requestable/producing peer. Та же политика применяется к одной
  fallback-direct попытке после неудачного metadata startup. Общий content preparation остаётся 60 с.
- Закрытый TCP pool терминально запрещает поздний запуск transport.
- При выборе нового канала старый незавершённый `SEARCHING` сбрасывается в `UNCHECKED`.
- Если квалифицированный fallback так и не отдаёт media, UI честно показывает найденный пир без
  данных потока и состояние `ERROR`, а не «нет пиров».

## Что ещё не решено

- Нужен реальный TV Box A/B gate текущего exact head; unit-тесты не доказывают полевую скорость DHT.
- Нужен deterministic A→B→C integration test с отменой во время DHT/refill/handoff.
- Media3/TS startup требует PAT/PMT/PID/continuity/random-access telemetry и fixture, достигающий READY
  до EOF; поведенческий TS fix пока намеренно не смешивается с DHT.
- Embedded peer-quality metrics ещё не полностью являются источником UI `enginePeers/speed`.
- Strict BEP-42 и per-lookup IPv4-prefix caps остаются отдельным compatibility-measured hardening.
- Не завершены weak-network, peer-loss, 2 h и 8 h ARM soak tests.

## Критерий готовности

- повторяемый startup/zap healthy swarm без внешнего Ace Engine;
- bounded точная ошибка недоступного swarm без вечного `SEARCHING`;
- старый runtime/loopback/pool не переживает supersession;
- Media3 достигает READY/first frame во время live response, не впервые после EOF;
- фиксированная channel matrix, A↔B и 20 rapid switches проходят на одном устройстве;
- exact-head unit/lint/build/instrumentation/smoke и длительные soak gates зелёные.

Канонический порядок работ: [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md).
