# Ace Live: внешние streaming reference — 16 августа 2026

## Назначение

Этот документ фиксирует технические выводы из просмотра открытых проектов TorrServer (`YouROK/TorrServer`) и `webtor-io`, выполненного после второго полевого TV Box прогона. Цель — не перенос чужого runtime как зависимости, а проверить, какие устойчивые streaming-паттерны подтверждают наши полевые симптомы и должны быть независимо реализованы в автономном Ace Live path.

## Лицензионная граница

- TorrServer распространяется по GPLv3. Его исходный код не копируется в этот проект. Используются только архитектурные идеи и наблюдаемые алгоритмические принципы с собственной реализацией.
- `webtor-io/torrent-web-seeder` и `webtor-io/torrent-http-proxy` используются как MIT/reference implementations там, где это применимо. Даже для MIT-кода предпочтительна собственная Kotlin/Ace Live адаптация вместо механического переноса Go-кода.
- Внешний TorrServer/webtor не становится runtime dependency приложения.

## Подтверждённые reference-паттерны

### 1. HTTP stream должен иметь однозначную reopen/resume semantics

TorrServer отдаёт seekable torrent reader через стандартный HTTP content-serving path с Range/seek semantics.

`webtor-io/torrent-http-proxy` при retryable разрыве не начинает логический поток заново: вычисляет фактически доставленный offset, повторно открывает upstream с `Range: bytes=<resume>-`, ожидает корректный partial response и отдельно обрабатывает граничный EOF/416 случай.

Наш текущий `LoopbackHttpLiveServer` для Ace Live читает request headers, но live response всегда строится как новый `200 OK` reader. Новый `AceLiveMediaBuffer.Reader` начинается с текущего retained floor. До полевого timeline нельзя утверждать, что Media3 обязательно посылает Range, но предыдущий паттерн `reader #1 долго BUFFERING -> reader #2 -> почти немедленный READY` делает request method/Range/start-offset/close-reason обязательной диагностикой V4d.

**Следствие для проекта:** сначала измерить реальные Media3 reopen requests; затем реализовать bounded logical-offset resume только для подтверждённой semantics. Если requested offset уже выпал из live retained window, это должно быть явным bounded outcome, а не тихим перезапуском с другого offset.

### 2. Forward reserve должен быть самостоятельным playback invariant

TorrServer использует существенно более широкий cache/read-ahead и распределяет приоритеты вокруг reader cursor: текущий piece получает высший приоритет, следующие — progressively lower read-ahead/high/normal priorities. В default settings cache имеет десятки MiB, а reader window в основном направлен вперёд.

Webtor также включает responsive reader и отдельный bounded readahead function, а не выводит весь scheduling из мгновенной скорости HTTP consumer.

Наш полевой прогон показал authoritative `playable_bytes` около 458656 байт, примерно около одной секунды media, почти всё длительное окно. Это слишком близко к live edge: любой краткий producer gap превращается в audio/video starvation.

**Следствие для проекта:** размер sliding storage сам по себе не является playback reserve. Нужен отдельный bounded forward-reserve controller вокруг authoritative consumer cursor. Его цель — поддерживать несколько полезных future pieces/time units, не нарушая live-latency bounds и не превращая runtime в VOD-style downloader.

### 3. Piece scheduling должен различать срочность

Reference-проекты не относятся ко всем pieces одинаково: текущая позиция, ближайший следующий piece, readahead range и дальняя часть окна имеют разные priorities.

**Следствие для Ace Live:** после измерения actual live geometry ввести собственный небольшой priority gradient, например логические классы `NOW -> NEXT -> READAHEAD -> PROBE`, привязанные к authoritative consumer cursor и recovery window. Это не копия TorrServer priority API и не должно менять существующий `maxPieceAdvance`/recovery bound без отдельного доказательства.

### 4. Startup warmup полезен только если он decoder-safe и bounded

Webtor имеет отдельный warmup path, повышающий priority pieces целевого byte range и считающий реально completed data. TorrServer тоже использует preload/read-ahead перед обычным consumption.

Для Ace Live простой byte-count warmup недостаточен, потому что MPEG-TS может начинаться в неподходящей точке.

**Следствие для проекта:** V4d warmup должен использовать уже имеющиеся V4c guarantees: contiguous TS sync, PAT, matching PMT, video PID и random-access evidence. Localhost exposure/first playable decision должен учитывать decoder-safe reserve, но оставаться bounded существующими startup failure limits.

### 5. Peer acquisition оценивается не discovered count, а стадиями dial -> handshake -> useful

Webtor отдельно контролирует established/half-open connections, peer watermarks, dial rate/timeouts и handshake timeout, а также считает dial attempts/failures/success и completed handshakes.

Наш field evidence: `discovered=4`, но устойчиво только `handshaked=1`; `windowUseful/producing` у этого peer периодически падает в ноль.

**Следствие для проекта:** startup diversity должна быть конкурентной, но bounded. Несколько перспективных tracker/DHT candidates можно проверять параллельно/раньше, однако решение о достаточной diversity принимается по `connected/handshaked/useful/producing`, а не по количеству найденных адресов. PR #127 является первым ограниченным шагом: первый альтернативный DHT endpoint освобождается сразу вместо ожидания batch из четырёх.

### 6. Pre-READY parser burst не равен playback bitrate

И TorrServer, и webtor отделяют readahead/cache policy от мгновенного чтения HTTP клиента. В нашем предыдущем field log Media3 до READY быстро вычитывал накопленные данные и создавал кратковременные consumer-rate оценки в десятки/сотни Mbit/s, которые не отражают реальный bitrate телеканала.

**Следствие для проекта:** до подтверждённого player READY/другого trustworthy playback-clock consumer socket rate не должен быть authoritative duration signal. Pre-READY reserve должен опираться на producer/media-growth estimate и byte/time floors. После READY consumer-rate можно вводить в pressure model через явный trust transition.

## Обновлённая последовательность V4d

1. Сделать PR #127 exact-head green и только после полного gate слить его в `main`.
2. Подключить один canonical startup timeline bridge на реальных runtime hook points.
3. Отдельным player-layer инкрементом добавить Media3 load start/error/retry, request method/Range, HTTP reader open/first-read/close reason, READY и first rendered frame/audio evidence.
4. По измеренным requests реализовать bounded live HTTP logical-offset reopen/resume; не угадывать Range semantics заранее.
5. Ввести bounded forward playback reserve вокруг authoritative consumer cursor и измерять reserve в bytes + estimated duration + piece geometry.
6. Перевести pre-READY pressure model на недоверенный consumer-rate до явного player-ready transition.
7. Расширить bounded competitive peer acquisition/half-open diversity, если поле по-прежнему показывает один useful producer.
8. Добавить decoder-safe startup warmup на базе существующего TS/PAT/PMT/random-access gate.
9. Только затем выполнить fixed same-device A/B matrix, 20 rapid switches, weak-network/peer-loss и 2h/8h ARM soak.

## Что намеренно не переносим

- произвольные 64 MiB/20 MiB значения как магические настройки;
- VOD-oriented begin/end preload для live stream;
- чужой BitTorrent client/runtime как обязательную зависимость;
- увеличение 60-second startup timeout, 30-second no-connected-peer guard или recovery bounds без измеренного основания;
- предположение, что timeout означает stale content ID без metadata evidence.

## Acceptance ориентир

На здоровых swarm и тех же TV Box/network условиях автономный runtime должен стремиться к observed same-device benchmark порядка 2–4 секунд start/zap и высокой успешности переключений. Это target для измеримого healthy-swarm поведения, а не гарантия для любого слабого/мертвого swarm.
