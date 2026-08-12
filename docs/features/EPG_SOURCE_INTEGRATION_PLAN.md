# План интеграции встроенного каталога EPG и пиконов

Актуальный срез: 12 августа 2026 года.

## Назначение

После завершения PR #100 (`fix(epg): prevent XMLTV OOM during IPTV playback`) приложение должно получить управляемый встроенный каталог телепрограммы и пиконов. Пользователь не должен вручную редактировать M3U-файл ради EPG: приложение само извлекает EPG URL из заголовка плейлиста, позволяет выбрать готовый источник и безопасно сопоставляет телепрограмму с каналами.

Этот документ фиксирует предоставленные пользователем источники как кандидатов для интеграции. Они являются внешними сервисами: их доступность, формат и условия распространения могут меняться, поэтому каждый endpoint должен проходить runtime-проверку и не должен считаться гарантированно доступным.

## Базовый контракт поведения

1. При импорте M3U извлекать `url-tvg`, `x-tvg-url`, `tvg-url` и совместимые варианты из `#EXTM3U`.
2. Явный EPG URL плейлиста/провайдера имеет первый приоритет.
3. Если явного источника нет или он неработоспособен, пользователь может выбрать источник из встроенного каталога.
4. Автоматический matching выполняется в порядке: `tvg-id` → точное нормализованное имя → alias/контролируемый fallback имени.
5. Пользовательское переименование канала не должно быть обязательным. Если оно ухудшило matching, UI должен показать unmatched status и позволить выбрать alias/исходное имя.
6. Неисправный, огромный, медленный или временно недоступный EPG не должен блокировать видеопоток и не должен завершать процесс приложения.
7. Любая тяжёлая EPG работа использует memory-safety contract PR #100: streaming input, bounded decompression, bounded retained data, single-flight/serialized load, negative-cache/backoff и low-memory guard.

## Универсальные EPG-источники

Эти источники рассматриваются как кандидаты для разных M3U-плейлистов. Реальное покрытие определяется matching по `tvg-id` и названиям каналов.

| ID | Название | URL | Формат | Планируемый статус |
|---|---|---|---|---|
| `teleguide-jtv` | Teleguide | `http://www.teleguide.info/download/new3/jtv.zip` | JTV ZIP | experimental до bounded JTV adapter |
| `iptvx-one-lite` | IPTVX.one EPG Lite | `http://iptvx.one/epg/epg_lite.xml.gz` | XMLTV GZIP | candidate |
| `programtv` | ProgramTV | `http://programtv.ru/xmltv.xml.gz` | XMLTV GZIP | candidate |
| `it999-edem` | it999 Edem | `http://epg.it999.ru/edem.xml.gz` | XMLTV GZIP | candidate |
| `ottepg` | OTTEPG | `https://ottepg.ru/ottepg.xml.gz` | XMLTV GZIP | candidate |
| `shara-tv` | Shara TV | `http://stb.shara-tv.org/epg/epgtv.xml.gz` | XMLTV GZIP | candidate |
| `kineskop` | Kineskop | `http://st.kineskop.tv/epg.xml.gz` | XMLTV GZIP | candidate |
| `webarmen` | Webarmen | `https://webarmen.com/my/iptv/xmltv.xml.gz` | XMLTV GZIP | candidate |
| `mediatech-by` | Mediatech BY | `https://static.mediatech.by/epg.xml` | XMLTV | candidate |

## Русскоязычные EPG-источники

| ID | Название | URL | Формат | Особенности |
|---|---|---|---|---|
| `it999-ru2` | it999 RU2 | `http://epg.it999.ru/ru2.xml.gz` | XMLTV GZIP | русскоязычная программа; связана с прозрачными прямоугольными пиконами |
| `it999-ru` | it999 RU | `http://epg.it999.ru/ru.xml.gz` | XMLTV GZIP | русскоязычная программа; связана с квадратными тёмными пиконами |
| `it999-rupp` | it999 RUPP | `http://epg.it999.ru/rupp.xml.gz` | XMLTV GZIP | вариант, ориентированный на Perfect Player / ProgTV |

## Источники логотипов телеканалов

| ID | URL | Архив | Описание |
|---|---|---|---|
| `it999-transparent-logos` | `https://epg.it999.ru/it999_transparent_logo.zip` | ZIP | прямоугольные пиконы 220×132, прозрачный фон |
| `it999-dark-logos` | `https://epg.it999.ru/it999_dark_logo.zip` | ZIP | квадратные пиконы 165×165, тёмный фон |

Логотипы не должны автоматически вшиваться в APK до проверки условий распространения. Безопасный вариант интеграции: хранить metadata источника, скачивать и кэшировать разрешённые изображения/архивы с hard limits. Если право на перераспространение не подтверждено, APK содержит только конфигурацию источника, но не сторонние изображения.

## Поддерживаемые форматы

### XMLTV (`.xml`)

Использовать production streaming XMLTV parser. Полное чтение большого ответа через `ResponseBody.bytes()` или `ResponseBody.string()` запрещено.

### XMLTV GZIP (`.xml.gz`)

Сетевой `byteStream()` должен проходить через bounded transport input, затем через `GZIPInputStream`, а распакованный поток — через отдельный hard decompressed-size limit перед XMLTV parser. Это защищает от gzip bomb и больших телегидов.

### JTV ZIP (`jtv.zip`)

JTV — отдельный формат и не должен подаваться в XMLTV parser. Требуется отдельный bounded JTV adapter с лимитами:

- максимум entries;
- максимум размера entry;
- максимум суммарного распакованного объёма;
- запрет распаковки всего архива в RAM;
- защита от path traversal/zip slip;
- controlled error и negative-cache при повреждённом архиве.

До реализации этого adapter источник `teleguide-jtv` отображается как experimental/unsupported и не выбирается автоматически.

## Целевая модель каталога

```text
EpgSourceDescriptor
  id
  displayName
  url
  format = XMLTV | XMLTV_GZIP | JTV_ZIP
  scope = UNIVERSAL | RU | PROVIDER | USER
  languages
  regions
  priority
  enabled
  experimental
  supportsLogos
  logoCatalogId
  transportSecurity = HTTPS | HTTP
  lastCheckAt
  lastSuccessAt
  lastErrorKind
  lastKnownCompressedBytes
  lastKnownExpandedBytes
  etag
  lastModified
```

Для HTTP endpoint приложение должно уважать существующую настройку insecure URLs. HTTPS-кандидат при равных условиях предпочтительнее HTTP.

## Выбор источника

Рекомендуемый порядок:

1. EPG URL из самого плейлиста (`url-tvg` / `x-tvg-url` / `tvg-url`).
2. EPG URL, полученный из provider API (Xtream и другие поддерживаемые провайдеры).
3. Явно выбранный пользователем источник из каталога для конкретного плейлиста.
4. Последний успешно работавший источник этого плейлиста (`last-valid`).
5. Автоматический кандидат по языку/региону и измеренному matching coverage.
6. Остальные универсальные источники — только через bounded probing, без параллельной загрузки всех больших EPG.

Нельзя скачивать девять универсальных XMLTV подряд на каждое переключение канала. Source selection выполняется на уровне плейлиста, а не per-channel.

## Matching каналов

Критический порядок:

1. `tvg-id` без учёта регистра;
2. стабильный provider/channel identifier;
3. точное нормализованное display-name;
4. сохранённый alias;
5. ограниченный fallback по нормализованному имени.

Нужно сохранять исходное имя канала отдельно от пользовательского display-name. Это позволит пользователю переименовать канал для UI и при этом не потерять EPG matching.

UI диагностики должен показывать:

- matched/unmatched;
- чем сопоставлен канал (`tvg-id`, alias, name);
- источник EPG;
- время последней успешной синхронизации;
- причину последнего отказа;
- долю каналов плейлиста, для которых EPG найден.

## Настройка M3U без ручного Блокнота

Типичная ручная инструкция сторонних IPTV-плееров предлагает изменить первую строку M3U на вариант с `url-tvg`. В Rinat IPTV этот сценарий должен быть автоматизирован:

- при импорте сохранить EPG URL из `#EXTM3U`, если он присутствует;
- в Editor/Playlist settings предоставить поле «Источник EPG»;
- рядом добавить «Выбрать из каталога»;
- дать кнопку «Проверить» с bounded probe;
- показывать matching coverage до сохранения;
- пользовательский URL остаётся поддержанным.

## UX-референсы

Пользователь предоставил следующие приложения как референсы поведения EPG. Они не являются зависимостями проекта и их код не копируется.

### TV Guide (`molokov.TVGuide`)

Идеи для адаптации:

- удобный список программы;
- несколько списков каналов с быстрым переключением;
- напоминания о передачах;
- TV-friendly управление.

### Hope EPG / Pro Guide

Идеи:

- фоновое обновление телепрограммы;
- планирование просмотра;
- фокус на русскоязычных/украиноязычных каналах.

### Lotus Program Guide

Идеи:

- категории каналов;
- избранное;
- TV-oriented presentation;
- сочетание телегида и live playback в одном UX.

### TV Control

Идеи:

- компактный каталог каналов;
- периодическое обновление программы;
- простой weekly-guide workflow.

Эти продукты используются только как продуктовые/UX-референсы. Реализация должна соответствовать архитектуре и лицензиям текущего проекта.

## Этапы реализации после PR #100

1. Завершить и слить memory-safety PR #100 после зелёного CI и runtime rapid-zap smoke.
2. Добавить `EpgSourceDescriptor` и статический встроенный source catalog без автоматической загрузки всех endpoints.
3. Добавить transport adapters: XMLTV, XMLTV GZIP, затем JTV ZIP.
4. Добавить health probe/metadata (`last success`, size, error, backoff).
5. Добавить playlist-level source selection и persistence.
6. Улучшить matching: исходное имя + `tvg-id` + aliases; отделить user display-name от matching identity.
7. Добавить matching coverage preview и diagnostics.
8. Добавить logo catalog adapter/cache с проверкой прав и hard ZIP/image limits.
9. После устойчивого ingestion реализовать полноценный Now/Next/day guide из roadmap #47.
10. Добавить напоминания/фоновые обновления только после проверки Android TV lifecycle, battery/network constraints и notification UX.

## Критерии готовности каталога

- плохой EPG endpoint никогда не ломает playback;
- `.xml.gz` распаковывается потоково и ограниченно;
- `jtv.zip` не распаковывается целиком в память;
- один плейлист имеет выбранный/last-valid source, а не запускает массовый поиск на каждом channel zap;
- источник из M3U автоматически распознаётся;
- `tvg-id` имеет приоритет над именем;
- пользовательское переименование не уничтожает matching identity;
- UI показывает источник, freshness, coverage и controlled error;
- HTTP/HTTPS политика соблюдает настройки безопасности приложения;
- бинарные логотипы не распространяются внутри APK без подтверждённых условий;
- unit/CI + runtime test на слабом/256-MiB устройстве проходят без OOM/ANR.

## Связь с roadmap

Этот план детализирует пункт «управляемый каталог EPG-источников» этапа #47 в `docs/ROADMAP.md`. Текущий порядок не меняется: сначала PR #100 и memory safety, затем внедрение каталога источников как отдельного PR/инкремента, после чего продолжаются Now/Next/archive и остальные playback-hardening задачи.