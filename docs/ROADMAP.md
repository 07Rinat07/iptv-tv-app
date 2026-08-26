# План проекта

_Актуализирован: 26 августа 2026 после merge PR #249 и #250; следующий field build готовится через финальную проверку PR #251._

Канонический handoff следующей сессии: [`CURRENT_DEVELOPMENT_HANDOFF.md`](CURRENT_DEVELOPMENT_HANDOFF.md). Текущий статус: [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md). Исходный recovery evidence: [`FIELD_VALIDATION_2026-08-25.md`](FIELD_VALIDATION_2026-08-25.md).

## Цель

Стабильное Android TV / TV Box приложение, которое:

- уверенно работает с большими IPTV/Torrent TV каталогами без OOM/UI freeze;
- использует TV-first Home и Player;
- воспроизводит ordinary IPTV через Media3 с bounded LibVLC fallback;
- воспроизводит Torrent TV через собственный embedded P2P/Ace Live runtime без обязательного внешнего Ace Engine;
- управляется D-pad/Enter/Back/Channel keys, мышью и тачпадом;
- показывает корректный EPG с локальным временем устройства и bounded пользовательской поправкой;
- выполняет EPG refresh без лишних загрузок сети/heap;
- сохраняет Favorites/EPG/provenance без тяжёлых full-table hot flows.

## Зафиксированный integration baseline

Уже merged в `main`:

- #249 P2P DHT bootstrap diversity -> `f2cb6e02c8725e49032038942521232438e18f38`;
- #250 EPG field matching/invalid programme recovery -> `1d4c86569537a525661525dfb949c0aa6754b284`.

#251 (`feat/epg-time-refresh-r1`) должен быть чисто основан на текущем `main` и merged только после зелёного exact-head CI. Следующая сессия разработки обязана проверить фактический статус #251 и `main` SHA перед анализом field build.

## Release gates

### P0 — memory/catalog stability (#229)

- bounded paging/projections вместо полного hot-flow materialization;
- отсутствие OOM/process death на large catalog;
- heap не повышается как workaround;
- deterministic large-fixture + real-device soak.

### P0 — Home root integration (#230)

Field-recovered. Reopen только по новому reproduction.

### P0 — production Player (#231)

Оригинальный визуальный regression field-recovered. Дальнейшие programme/nearby-channel функции добавляются отдельными Player/EPG increments без второго playback/P2P runtime.

### P0 — embedded Torrent TV parity (#232)

Основной release blocker.

Диагностика классифицируется по первой отсутствующей стадии:

1. discovery / peer-set acquisition;
2. TCP connect;
3. BT/Ace Live handshake/qualification;
4. metadata/live-window;
5. request selection/sent;
6. chunk ingress/accept;
7. piece complete/authentication;
8. TS resync/output;
9. Player boundary.

PR #249 расширяет bootstrap diversity, сохраняя текущие startup bounds. Его эффект проверяется на следующем TV Box run.

Запрещено лечить protocol gap увеличением общих timeout/buffer/request-depth/peer-cap bounds без подтверждённой причины.

### P1 — unintended playback churn (#233)

Проверить, создаёт ли focus movement burst `player_play_request`. Playback должен начинаться по явному выбору, не по одному перемещению focus.

## EPG sequence

### Уже merged: #250 field recovery

- invalid/placeholder programmes не выдаются как нормальная программа;
- matching нормализует типичные resolution/status decorations;
- ambiguous mapping остаётся fail-closed.

### Integration candidate: #251 time + low-load refresh

После чистого exact-head CI:

- timezone TV Box — default display zone;
- manual correction `-12h...+12h`, шаг 30 минут;
- одна corrected timeline для Guide/Player/RecordingSchedule;
- refresh 6/12/24h, default 24h;
- refresh-on-start только если stale;
- старый 30-минутный EPG periodic refresh удалён;
- sequential/serialized XMLTV work;
- virtual aggregate playlists не считаются реальными EPG sources;
- startup/periodic race повторно проверяет freshness внутри worker;
- ручной `Обновить` позже выполняет forced refresh.

### Следующий отдельный infrastructure increment

`feat/epg-disk-cache-r1`

Полный контракт: [`EPG_DISK_CACHE_PLAN.md`](EPG_DISK_CACHE_PLAN.md).

Требования:

- L1 parsed memory + L2 app-private raw XMLTV snapshots;
- process restart внутри network-fresh interval без полного повторного download;
- `ETag`/`Last-Modified`/`304` revalidation;
- <=64 MiB snapshot;
- <=128 MiB total cache;
- <=4 entries;
- deterministic LRU;
- hard stale <=96h;
- atomic temp-write/checksum/rename;
- credential-bearing URL не попадает в filename/diagnostics;
- existing streaming parser/headroom bounds используются и для disk read;
- low storage -> skip cache write, а не failure EPG;
- restart/disk-hit/304/200/corruption/eviction/concurrency tests;
- TV Box restart field gate.

### EPG/Player UX после cache boundary

Отдельными increments:

1. Settings EPG controls: manual offset, 6/12/24h, refresh-on-start-if-stale, `Обновить`;
2. Player action `Программа`;
3. programme list выбранного канала: `Сейчас`, `Далее`, время, progress, description;
4. logo + `Сейчас/Далее` у nearby/quick channels;
5. 1280x720 TV Box D-pad/layout validation;
6. archive/catch-up polish.

## Следующий field gate

После merge всех зелёных integration changes и зелёного `main` CI:

1. обновить локальный `main` fast-forward;
2. собрать свежий APK из clean working tree;
3. установить именно эту сборку на TV Box;
4. проверить ordinary IPTV;
5. проверить несколько Torrent TV каналов, включая быстрые/медленные/ранее проблемные;
6. проверить EPG на нескольких каналах, локальное время и restart приложения;
7. проверить Player overlays, nearby channels и D-pad;
8. выполнить достаточно переключений для оценки heap/runtime cleanup;
9. экспортировать diagnostics;
10. сохранить скриншоты успешных и проблемных состояний.

Фиксированная A/B-матрица не обязательна для обычного field handoff; если используется сравнительный прогон, его evidence сохраняется вместе с основным diagnostics export.

## Как разбирать следующий field run

Torrent TV:

`discovered -> connected -> handshaked/qualified -> producing -> first media/player ready`

EPG:

`source -> channel match -> programme validity -> time correction -> UI presentation`

Memory/runtime:

`heap trend -> OOM/process death -> duplicate work -> stale runtimes/resources`

Следующий кодовый increment выбирается только после определения первой подтверждённой проблемы в этих цепочках.

## Workflow

Для каждого блока:

1. актуальный `main`;
2. одна тематическая ветка;
3. deterministic regression test, где возможно;
4. минимальный diff;
5. relevant unit/lint/Room/guard/build gates;
6. exact-head CI;
7. squash merge только зелёного exact head;
8. проверить CI интегрированного `main`;
9. device/network-dependent изменения подтвердить field evidence;
10. обновить handoff/status/roadmap после изменения фактического состояния.

Старый зелёный CI не считается проверкой нового SHA. P2P, EPG cache/data и Player UI не объединяются в один большой PR.

## Definition of Done

PR не завершён только потому, что компилируется.

Нужны:

- соответствующие deterministic tests;
- отсутствие регрессии relevant unit/build gates;
- green exact-head CI;
- для integration — green `main` после merge;
- для device/network behavior — field run именно интегрированной сборки;
- diagnostics без прежнего failure signature либо с чётко классифицированной следующей стадией;
- отсутствие нового OOM/process-death signature;
- документация handoff синхронизирована с фактическим состоянием.
