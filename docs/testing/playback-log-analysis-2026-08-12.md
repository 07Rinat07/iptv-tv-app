# Анализ playback/OOM — 12 августа 2026

## Базовое состояние

- Базовая ветка для текущего исправления: `main`.
- Базовый commit: `aa9a0e67b0183145f01d15f28045ba0e0e0e9d67` (merge PR #98).
- PR #98 закрыл fast-switch/first-success регрессию Ace Live и прошёл Android CI #425 перед merge.
- PR #99 ранее устранил полное пересоздание Media3/MediaCodec при каждом переключении обычного IPTV и уже находится в `main`.
- Текущая рабочая ветка: `codex/epg-oom-streaming-guard`.

## Подтверждённый текущий блокер

Пользовательские журналы от 12 августа показывают закрытие приложения во время просмотра и быстрого переключения обычных IPTV-каналов. Каналы перед падением успешно классифицируются как `IPTV поток (прямой URL)`, поэтому этот crash не является специфичным для Torrent TV/Ace.

Критический сценарий:

1. обычный IPTV-канал успешно resolve/start/ready;
2. heap на устройстве ограничен примерно 256 MiB;
3. EPG/OkHttp продолжает сетевую работу на фоне playback;
4. процесс доходит до 224–252 MiB used heap;
5. `OkHttp TaskRunner` получает `OutOfMemoryError`, после чего процесс приложения завершается.

Отдельный журнал показывает повторную загрузку одного повреждённого XMLTV при переключении каналов: `XmlPullParserException: unterminated entity ref`. Ошибка не сохраняется в negative cache, поэтому следующий канал снова запускает ту же загрузку/разбор.

## Причина в текущем EPG-коде

`PlaylistRepositoryImpl.getOrLoadXmlTv()` сейчас:

- загружает весь XMLTV через `response.body?.bytes()`;
- создаёт полный `ByteArray` EPG в heap;
- затем парсит этот массив и одновременно строит крупные `Map/List` со всеми программами;
- после парсинга дополнительно создаёт нормализованные копии maps/lists;
- кэширует только успешный результат;
- не кэширует malformed/failed EPG;
- не ограничивает размер XMLTV, число программ или объём EPG cache;
- не проверяет доступный heap headroom перед тяжёлой EPG-загрузкой;
- не предотвращает несколько одновременных загрузок/разборов EPG.

На 256-MiB Android heap это может кратковременно требовать существенно больше памяти, чем размер самого XMLTV.

## Текущий приоритет: EPG OOM hardening

До MPEG-TS/decoder hardening исправляется общий EPG memory path, потому что он способен закрыть приложение даже при обычном прямом IPTV.

Обязательный scope текущего PR:

1. убрать `response.body.bytes()` из XMLTV path;
2. разбирать XMLTV непосредственно из `ResponseBody.byteStream()`;
3. ограничить число одновременно выполняющихся EPG load/parse операций;
4. добавить negative-cache/backoff для HTTP/parser/malformed XMLTV ошибок;
5. не хранить `Throwable` в negative cache — только timestamp и короткую причину;
6. ограничить максимальный объём читаемого XMLTV;
7. ограничить объём retained EPG: число каналов/программ, число программ на канал и длину текстовых полей;
8. убрать лишнюю вторую копию всех program/display-name collections после parsing;
9. ограничить число тяжёлых EPG entries в memory cache;
10. перед началом EPG load проверять heap headroom и при низкой памяти возвращать контролируемое `EPG unavailable/deferred`, а не запускать тяжёлую сетевую работу;
11. при смене EPG URL инвалидировать positive/negative cache для старого и нового источника;
12. добавить unit/regression coverage для bounded streaming input и failure/backoff поведения, где это возможно без Android runtime;
13. прогнать полный Android CI до зелёного exact-head SHA;
14. после CI выполнить ручной smoke: 20–30 быстрых переключений обычных IPTV-каналов на 256-MiB/аналогичном устройстве и убедиться, что malformed EPG даёт только отсутствие телепрограммы, но не crash.

## Критерий приёмки EPG OOM PR

Исправление считается пригодным к merge, если одновременно выполнено следующее:

- в production XMLTV path отсутствует `ResponseBody.bytes()`;
- один тяжёлый EPG source не загружается/парсится параллельно несколько раз;
- malformed XMLTV после первого fail попадает в bounded negative-cache и не скачивается снова при каждом zap;
- EPG parser работает потоково и имеет hard byte/program/text limits;
- при низком heap headroom EPG отказывается от загрузки без влияния на video playback;
- обычный IPTV продолжает запускаться, даже если EPG повреждён, слишком большой или временно недоступен;
- Android CI полностью зелёный на exact PR head;
- ручной rapid-zap smoke не воспроизводит `OutOfMemoryError`/закрытие приложения.

## Что не смешивать с этим PR

Отдельными следующими инкрементами остаются:

- Ace Live `content_id`, который на некоторых источниках всё ещё может исчерпывать полный 60-секундный startup budget при недоступной metadata;
- MPEG-TS PAT/PMT/random-access/IDR gating и decoder/discontinuity hardening;
- дополнительные debounce/coalescing улучшения сверх уже сделанного Media3 reuse, если после memory fix останутся краткие зависания при удержании кнопки переключения;
- полноценный EPG/archive redesign из roadmap #47.

## Порядок продолжения после текущего PR

1. EPG OOM hardening и 256-MiB rapid-zap smoke.
2. Повторный анализ свежего лога после memory fix.
3. Если обычный IPTV больше не закрывается — отдельно измерить residual zap latency.
4. Затем ограничить/улучшить оставшийся медленный Ace Live startup без возвращения hard-cancel регрессии PR #98.
5. После этого перейти к MPEG-TS/decoder stability.
6. Синхронизировать `docs/ROADMAP.md`, `docs/PLAYBACK_STATUS.md`, `CHANGELOG.md` и этот журнал с фактически прошедшими CI/ручными результатами перед merge/release.
