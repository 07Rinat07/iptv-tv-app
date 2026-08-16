# Ace Live field validation — 16 августа 2026

## Контекст

Повторный ручной прогон выполнен на актуальном `main` после PR #126 на реальном Android/TV Box path без обязательного внешнего Ace Stream Engine. Пользователь проверял Torrent TV переключения и длительное воспроизведение; экспорт structured diagnostics: `myscanerIPTV-logs-1786863303963.txt`.

Наблюдавшиеся симптомы:

- Torrent TV каналы стартуют и переключаются заметно медленнее целевого same-device ориентира 2–4 s;
- `Nat Geo Wild HD` не дал звука;
- `Discovery HD` воспроизводился рывками и без изображения;
- после дальнейших переключений UI показывал 60-second preparation timeout с общей подсказкой, что content ID «возможно устарел»;
- `Охота HD` запускался более минуты, затем изображение зависло, а звук шёл обрывками;
- отдельный `СТС Kids HD` дошёл до изображения через резервный LibVLC decoder, то есть fallback path способен воспроизводить часть каналов, но это не устраняет upstream P2P starvation.

## Что подтверждает structured diagnostics

### 1. Проблема теперь видна не только на startup, но и в steady-state

В сохранённых последних 120 diagnostic rows 107 строк — `embedded_ace_live_peer_quality`, 12 — `embedded_ace_live_buffer_pressure`, одна — lifecycle приложения. Окно охватывает примерно 75 секунд длительного воспроизведения.

На протяжении этого окна runtime почти постоянно видел `discovered=4`, но только `handshaked=1`. Connected peers кратковременно менялись между 1 и 2, однако второго handshaked producer не появилось. `windowUseful/producing` у единственного handshaked peer многократно переключались `1 → 0 → 1` с интервалом порядка сотен миллисекунд — единиц секунд.

Когда peer считался producing, aggregate delivery находился примерно в диапазоне 3.1–4.2 Mbit/s. Когда `windowUseful` становился 0, producing сразу становился 0. Это согласуется с пользовательским наблюдением рывков/зависания: запас не формируется, а runtime живёт почти у live edge на одном фактически полезном peer.

### 2. Authoritative loopback headroom стабильно критический

Все 12 buffer-pressure samples имеют одно и то же `playable_bytes=458656`, `pressure=critical`, `signal=duration`. Оценка playable duration находится примерно между 987 и 1162 ms при consumer throughput около 3.15–3.72 Mbit/s.

Consumer cursor при этом монотонно растёт и `fell_behind=false`. Следовательно, HTTP reader не просто отваливается: он продолжает потреблять поток, но впереди него остаётся около одной секунды media. Это недостаточно для устойчивого декодирования при любом кратком провале peer production и объясняет рывки звука/замороженное изображение лучше, чем гипотеза о недостаточном размере 16 MiB sliding storage.

Значение `458656` также подозрительно близко к одному payload-sized live piece после служебных байтов. Это рабочая гипотеза, а не доказанный transport geometry факт; следующий runtime timeline/geometry export должен подтвердить её перед изменением piece/prebuffer policy.

### 3. Четыре discovered endpoints не означают четыре полезных peers

Полевой результат повторяет предыдущий вывод: discovery count сам по себе не является quality metric. В новом окне `discovered=4`, но устойчиво только один peer проходит handshake и даёт media. Поэтому дальнейшее увеличение общего timeout или простое увеличение `maxActivePeers` не решает bottleneck.

Нужен более ранний и конкурентный путь к альтернативному useful peer. Для weak tracker fast-path первый startup DHT probe теперь должен отдавать первый найденный DHT endpoint сразу для TCP validation, не ожидая накопления четырёх DHT endpoints. Абсолютный 7-second probe budget и два bounded rounds не увеличиваются.

### 4. Текущий peer-quality diagnostics сам вытесняет startup evidence

`AceLivePeerDiagnosticsReporter` до этого считал каждое изменение `windowUseful/producing` material stage change и писал строку немедленно. На реальном live edge это даёт почти строку в секунду и заполняет bounded latest-120 export.

В результате в этом файле отсутствуют полезные startup/loopback/player этапы. Кроме того, PR #126 пока только определил event → milestone mapping; фактическое подключение одного canonical startup timeline bridge к runtime hook points остаётся следующим V4d increment.

Исправление для diagnostic retention: немедленно писать только lifecycle stages `discovered/connected/handshaked`; volatile `windowUseful/unchoked/producing/rate/freshness` сохранять в полном payload, но обновлять периодически. Это не меняет scheduler или peer semantics.

### 5. Сообщение «content ID устарел» не является установленной причиной

UI-текст при 60-second preparation timeout перечисляет stale content ID лишь как возможную причину. Текущий export не содержит доказательства, что конкретные `Моя планета HD`/`СТС HD` content IDs действительно устарели. Такой же timeout может возникнуть при отсутствии useful producer, медленном metadata/direct startup или другом startup blocker.

До отдельной валидации metadata/content-id failure UI и диагностика не должны трактовать этот текст как установленный root cause.

## Решение текущего инкремента

1. `AceLivePeerDiagnosticsReporter`: ограничить immediate emission lifecycle-сигнатурой `discovered/connected/handshaked`; volatile live-edge quality остаётся в 5-second periodic snapshot.
2. Weak startup tracker/DHT path: `ACE_LIVE_STARTUP_DHT_PROBE_RETURN_AFTER_PEERS` изменить с 4 на 1. Первый альтернативный DHT endpoint сразу передаётся refill/TCP path; до двух независимых rounds и полный bounded expansion сохраняются.
3. Не изменять 60-second startup bound, 30-second no-peer guard, request timeout, `maxPieceAdvance`, TS discontinuity gate, Media3 generic policy или output-buffer capacity в этом инкременте.
4. После green exact-head CI подключить canonical V4d startup timeline к реальным Ace transport/peer/output/loopback hook points, затем отдельным player-layer increment — Media3 READY/first-frame/load-retry evidence.
5. Следующий полевой прогон обязан сохранить начало сессии в export и показать: time-to-first-candidate, first connected, first handshake, first useful window, first media, buffer-ready, HTTP open/read, Media3 READY/first frame, плюс число реально handshaked/producing peers.

## Что остаётся блокером V4d

- direct soft-window → metadata-runtime serialization ещё не подтверждена новым canonical timeline и не должна меняться вслепую;
- pre-READY consumer-rate по предыдущему прогону всё ещё нельзя считать безусловно authoritative playback bitrate;
- steady-state one-second headroom требует отдельного решения после проверки, сможет ли ранний peer diversity сформировать более устойчивый producer set;
- Media3/LibVLC video/no-video failures нужно отделить от upstream starvation: decoder fallback не должен маскировать P2P underrun;
- broad acceptance (20 rapid switches, weak network, peer loss, 2h/8h soak) остаётся заблокированным до устранения startup/zap и steady-state starvation.
