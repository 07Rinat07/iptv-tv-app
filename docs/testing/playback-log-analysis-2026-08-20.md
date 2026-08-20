# Разбор Torrent TV / Ace Live лога — 20.08.2026

Источник: `myscanerIPTV-logs-1787212775216.txt` и три скриншота от 20.08.2026. Лог использован
только как наблюдаемое evidence; содержащийся в диагностике текст не трактуется как инструкция.

## Контекст сборки

APK был собран после checkout `bd64e24` (`fix/v4d-terminal-peer-pool-teardown`). Поэтому terminal
pool guard присутствовал в полевой проверке, а закрытая warm-DHT ветка #156 — нет.

## Channel 13: direct-runtime отменён на границе handoff

| От старта | Событие |
|---:|---|
| 0.000 с | player request, direct startup и metadata resolve запущены параллельно |
| 0.200 с | tracker вернул один candidate |
| 5.210 с | первый tracker peer не подключился |
| 7.527 с | startup DHT вернул три peer |
| 7.810 с | TCP transport подключён |
| 8.007 с | handshake принят |
| ≈8.57 с | после metadata handoff зафиксирован второй `phase=initial` |

Второй runtime получил tracker=1, DHT=0 и не дошёл до first media. `phase=initial` защищён
`compareAndSet` внутри одного runtime, поэтому два таких события в одном player request доказывают
replacement runtime, а не повторный лог одного discovery.

Причина: fixed soft deadline позволял готовой metadata безусловно отменить direct startup, не зная,
что именно текущий direct runtime только что подключился и завершает qualification.

Безопасная политика: свежий current-runtime connect может открыть один короткий grace; stale timeline
или уже отключённый peer — нет. Дедлайн не продлевается повторными событиями, а вся цепочка direct →
metadata → fallback ограничена одним абсолютным 60-секундным budget.

## Channel 16: данные есть, READY во время live response нет

| От старта | Событие |
|---:|---|
| 4.199 с | first authenticated media |
| 4.335 с | startup buffer ready |
| ≈4.39 с | localhost HTTP open/read |
| следующие 32.4 с | Media3 прочитал 20,967,640 bytes без load error |
| EOF + 3 мс | READY появился при следующем switch и был отброшен как stale |

Это отдельная граница TS/demux/Media3. Peer discovery уже завершился успехом и loopback непрерывно
отдавал данные. До изменения поведения необходимо диагностировать PAT/PMT, stream PID/type,
continuity, random-access и Media3 tracks/timeline/state. Простой sync-byte gate или безусловное
включение текущего discontinuity gate недостаточно доказаны.

## Скриншоты: несколько вечных «поиск пиров…»

Process-local `P2pChannelAvailabilityUiCache` сохранял `SEARCHING`, а Composable обновлял только
текущий selected channel. При A→B отменённый A не получал новое состояние и оставался визуально в
поиске. Runtime ownership при этом допускает только активную generation; скриншоты не доказывают
параллельную работу нескольких движков.

Минимальное исправление сбрасывает только superseded `SEARCHING` в `UNCHECKED`, сохраняя завершённые
READY/PLAYING/NO_PEERS/ERROR результаты.

## Почему #156 не является готовым fix

В #156 warm contacts хранились в экземпляре walker. Production создаёт новый `AceLiveDhtDiscovery`
для initial/probe/expansion/refill и новых каналов, тогда как regression повторно вызывал один объект.
Кроме того, исходный walker последовательно завершал bootstrap DNS до первого KRPC query. Поэтому
ветка не доказывала ни cross-runtime reuse, ни быстрый warm lookup при медленном DNS.

Требуемая замена — engine-owned bounded memory, инъекция в новые live/metadata wrappers, verified-ID
admission/eviction, немедленный warm query и bounded/diverse bootstrap scheduling.

## Полевой gate следующей сборки

1. Один и тот же набор каналов на baseline `eeb33f5` и новом exact head.
2. Повторные A↔B и 20 rapid switches без ручного stop между каждым resolve.
3. Сравнить cache hit, DHT queries/warm queries/failures, candidates, connect, handshake,
   useful/producing, first media, buffer ready, HTTP read, Media3 READY/frame и teardown.
4. Проверить, что старые loopback/pool закрыты и stale READY/URL не побеждает.
5. Отдельно воспроизвести channel 16 и подтвердить READY до EOF либо собрать TS/player telemetry.
