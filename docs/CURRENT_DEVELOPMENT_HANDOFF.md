# Текущий handoff разработки

_Актуализирован: 26 августа 2026 после merge PR #251/#252/#253 и нового TV Box field retest._

Перед продолжением всегда сверять фактические `main`, branch head, open PR и CI в GitHub. Этот файл фиксирует направление работы, но не заменяет проверку exact SHA.

## Текущий baseline

Field build основан на `main`:

`a1b3e5e086c0470d8c21e75f8a288610c61b986a`

В baseline уже вошли:

- #249 — bounded DHT bootstrap diversity;
- #250 — EPG matching + invalid/placeholder filtering;
- #251 — timezone correction + bounded 6/12/24h refresh policy;
- #252 — XMLTV transport envelope 128 MiB;
- #253 — Player `Программа` dialog + nearby logos.

#252 **не является disk cache**. Persistent raw XMLTV L2 cache остаётся отдельным планом в `EPG_DISK_CACHE_PLAN.md`.

## Активная ветка

`fix/epg-source-format-classification-r1`

Она создана от точного field baseline `a1b3e5e...` и не должна содержать P2P fixes.

## Текущий EPG evidence

До #252 источник EPG отклонялся по старому 64 MiB лимиту; field diagnostics показал фактический `Content-Length` 88,578,547 байт.

После #252 источник проходит transport guard, но повторно падает внутри XML parser:

`Invalid XMLTV format: unterminated entity ref (position:TEXT @1:48 ...)`

Ошибка воспроизводится на нескольких каналах одного playlist. Следовательно, текущая первая подтверждённая точка отказа:

`HTTP/body -> source-format classification -> XML parser`

а не Player dialog или channel matching.

## Что делает текущий increment

В `EpgStreamingSafety` добавляется bounded source-format preflight:

- inspect максимум 8 KiB;
- вернуть inspected bytes обратно через `PushbackInputStream`;
- пропускать только XMLTV-looking root после BOM/XML declaration/comment/DOCTYPE;
- отдельно классифицировать HTML, raw gzip, другой XML, text, binary/unknown и empty;
- не читать весь 88+ MB body в память;
- не логировать payload/source URL/token;
- сохранить существующий 128 MiB streaming hard limit и heap/program/channel bounds.

Никакой глобальной замены `&` в этом PR нет. Если XMLTV-looking source всё равно падает на entity ref, нужен следующий отдельный compatibility increment только после подтверждения реального malformed XML pattern.

## Acceptance текущей ветки

До merge нужны:

1. deterministic tests на XMLTV preambles, HTML, gzip, generic XML, text/binary и сохранение prefix bytes;
2. существующие `EpgStreamingSafety` / input-limit tests без регрессии;
3. relevant `core:data` unit tests;
4. Android CI / guards на exact head;
5. draft PR до получения зелёного exact-head CI;
6. затем TV Box field run exact integrated build.

Field acceptance:

- либо EPG начинает парситься и появляются programmes;
- либо diagnostics меняется на конкретный `format=...` для не-XMLTV body;
- либо остаётся XML parser `unterminated entity ref`, что после fail-closed prefix gate подтверждает XMLTV-looking malformed payload и определяет следующий узкий шаг.

## P2P — не смешивать с EPG веткой

Последний field run показывает два независимых P2P failure class:

1. слабый discovery/connect: tracker даёт мало peers, connect failure, bounded DHT не находит замену;
2. upstream data уже поступает в loopback/Media3 большим объёмом, но отдельная сессия не достигает first audio / first video frame.

При этом другие P2P сессии успешно достигают first audio + first video frame. Поэтому после EPG classification pass возвращаться к #232 двумя отдельными расследованиями:

`discovered -> connected -> handshaked/qualified -> producing`

и

`clean TS/loopback -> Media3 tracks -> first audio/video frame`.

Не повышать global startup timeout, player buffers, request depth, peer caps или heap.

## Persistent XMLTV disk cache

План: `feat/epg-disk-cache-r1`, но только после source/parser correctness.

Field source 88,578,547 байт доказал, что старый snapshot cap 64 MiB устарел. Disk plan должен использовать:

- per-snapshot hard cap = current `EpgInputSafetyPolicy.MAX_INPUT_BYTES` (128 MiB);
- aggregate disk budget <=128 MiB;
- <=4 entries with deterministic LRU;
- если один большой snapshot занимает большую часть бюджета, eviction освобождает место; per-entry cap не означает резервирование 128 MiB на каждый entry;
- conditional HTTP, atomic write/checksum, stale policy, secret-safe key, low-storage skip и process-restart tests остаются обязательными.

## Следующий порядок действий

1. завершить source-format tests/docs в текущей ветке;
2. создать draft PR;
3. проверить exact-head CI;
4. исправлять только compile/test review findings;
5. merge — только после зелёного exact head;
6. собрать exact merged `main` и выполнить TV Box EPG retest;
7. по результату выбрать malformed-XML compatibility либо конкретный source-format support;
8. затем вернуться к #232 P2P blockers;
9. disk cache — отдельный последующий EPG infrastructure PR.

## Branch discipline

- один дефектный boundary — один PR;
- P2P, EPG parser/source, disk cache и Player UI не объединять;
- старый зелёный CI не подтверждает новый SHA;
- CI не заменяет field proof;
- изменение абсолютных bounds требует отдельного evidence;
- после merge проверить integrated `main`, затем field-test именно эту сборку.
