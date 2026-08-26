# Текущий handoff разработки

_Актуализирован: 26 августа 2026 после merge PR #254 и TV Box retest на `main@40ac556621cb4002a5457b41c909344f90c75e89`._

Перед продолжением всегда сверять фактические `main`, branch head, open PR и CI в GitHub. Этот файл фиксирует направление работы, но не заменяет проверку exact SHA.

## Текущий baseline

Field build основан на:

`main@40ac556621cb4002a5457b41c909344f90c75e89`

В baseline уже вошли:

- #249 — bounded DHT bootstrap diversity;
- #250 — EPG matching + invalid/placeholder filtering;
- #251 — timezone correction + bounded 6/12/24h refresh policy;
- #252 — XMLTV source/network envelope 128 MiB;
- #253 — Player `Программа` dialog + nearby logos;
- #254 — bounded source-format classifier before XML parsing.

#252/#254 **не являются persistent disk cache**. Persistent raw XMLTV L2 cache остаётся отдельным планом в `EPG_DISK_CACHE_PLAN.md`.

## Активная ветка

`fix/epg-gzip-source-r1`

Она создана от exact field baseline `40ac556...` и не должна содержать P2P behaviour changes.

## Текущий EPG evidence

Последний TV Box field run дал окончательную source-format классификацию:

`EPG source is not XMLTV: format=GZIP`

Следовательно старый `unterminated entity ref` больше не является основанием для XML text sanitizer. Первая подтверждённая EPG точка отказа теперь:

`raw GZIP source -> decode -> XMLTV parser -> matching -> Player/Guide`

Следующий increment обязан декодировать raw gzip streaming перед существующим XMLTV parser.

## Что делает текущий increment

`fix/epg-gzip-source-r1`:

- сохраняет 128 MiB hard envelope на исходный HTTP/raw-gzip payload;
- распаковывает raw gzip через streaming `GZIPInputStream`;
- повторно классифицирует распакованный prefix и пропускает только XMLTV;
- устанавливает отдельный bounded decoded-XMLTV envelope 256 MiB;
- не использует `body.bytes()`, `readBytes()` или строковую материализацию полного guide в production path;
- сохраняет существующие heap/program/channel limits;
- не логирует тело EPG и credential-bearing URL;
- не делает глобальную замену `&` или другое speculative XML rewriting.

Если field source после decode превышает 256 MiB, следующий лог должен показать decoded byte-limit failure. Увеличивать этот bound без такого evidence нельзя.

## Acceptance текущей ветки

До merge:

1. raw gzip XMLTV декодируется и сохраняет исходные XML bytes;
2. gzip HTML/non-XMLTV fail-closed после decode;
3. decoded expansion hard-limit покрыт тестом;
4. исходный 128 MiB source envelope сохраняется;
5. existing EPG/source-format/input-limit tests не регрессируют;
6. Database Unit CI + Android CI + relevant guards зелёные на exact head;
7. PR остаётся draft до зелёного exact-head CI.

После merge нужен TV Box retest exact integrated build. Возможные следующие evidence-классы:

- XMLTV parser/matching работает и появляются programmes;
- decoded XMLTV превышает 256 MiB;
- parser получает XMLTV, но падает на конкретной malformed XML конструкции;
- parser работает, но channel matching остаётся слабым.

Только фактический следующий boundary определяет следующий EPG PR.

## P2P evidence — отдельный P0

Последний field run снова показывает и успешный, и неуспешный embedded P2P путь.

Успешный путь достигает:

`discovered -> connected -> handshaked -> useful window -> producing -> clean TS -> loopback -> Media3 -> first frame/audio`.

Есть сессии с несколькими producing peers, десятками мегабайт чистого MPEG-TS, `sync_losses=0`, `transport_errors=0`, PAT/PMT/video/audio PID и быстрым first frame.

Но failure path всё ещё воспроизводится:

`tracker=1 -> connect_failed -> bounded DHT probes -> 0 replacement peers`.

Поэтому после EPG gzip increment возвращаться к issue #232 именно в discovery/connect qualification. Не повышать global startup timeout, heap или player buffers для маскировки отсутствующего swarm.

Producer/Media3 boundary чинить отдельно только для сессий, которые уже доказанно прошли discovery/handshake/producer stages.

## Player `Программа` UX

Текущий centered modal функционально подключён, но field screenshots показывают, что он закрывает слишком большую часть видео, особенно когда EPG пуст.

После восстановления реальных EPG данных нужен отдельный `feat/player-programme-panel-r2`:

- video остаётся основной видимой поверхностью;
- программа открывается как компактная правая/частичная панель, а не большой centered modal;
- текущая/следующая передача + короткий upcoming list из уже загруженного Player EPG state;
- без второго сетевого EPG fetch;
- пустой EPG показывает компактное неблокирующее состояние;
- D-pad focus и Back/Close детерминированы.

Не смешивать этот UX change с gzip parser или P2P fixes.

## Field evidence

Текущий подробный прогон:

`docs/FIELD_VALIDATION_2026-08-26_2029.md`

## Persistent XMLTV disk cache

План: `feat/epg-disk-cache-r1`, только после source/parser correctness.

Raw gzip field source имеет reported size 88,578,547 bytes. Disk-cache implementation должен явно определить, хранится ли compressed raw payload или decoded XMLTV; предпочтительно хранить source bytes в том виде, который пришёл по сети, а decode выполнять streaming при чтении, чтобы не раздувать storage write path.

Обязательные свойства остаются:

- bounded per-source snapshot;
- aggregate disk budget;
- deterministic LRU;
- conditional HTTP;
- atomic write/checksum;
- stale policy;
- secret-safe key;
- low-storage skip;
- process-restart tests.

## Следующий порядок действий

1. завершить `fix/epg-gzip-source-r1` + exact-head CI;
2. merge только после зелёного exact head;
3. проверить post-merge CI `main`;
4. собрать exact merged build и повторить EPG TV Box test;
5. по новому evidence закрыть parser/matching boundary;
6. затем вернуться к #232 P2P discovery/connect;
7. после EPG correctness — `feat/player-programme-panel-r2`;
8. persistent disk cache — отдельный infrastructure PR.

## Branch discipline

- один defect boundary — один PR;
- P2P, EPG source/parser, disk cache и Player UX не объединять;
- старый зелёный CI не подтверждает новый SHA;
- CI не заменяет field proof;
- absolute bounds меняются только по evidence;
- после merge всегда проверять integrated `main` и тестировать именно эту сборку.
