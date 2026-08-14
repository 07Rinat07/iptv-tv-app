# Playback latency baseline

Этот документ описывает измерительный и fast-zap этап playback-hardening после PR #101.

## Зачем нужен анализатор

В Diagnostics уже сохраняются структурированные события Player и P2P. Экспорт содержит строки вида:

```text
createdAt | status | playlist=<id> | message
```

Для primary playback особенно важны:

- `player_play_request` — пользователь/Player запросил запуск канала;
- `*peer_connected*` — Ace Live/P2P получил рабочее peer-соединение, если такое событие есть в экспортированном окне;
- `*startup_buffer_ready*` / `*first_media_byte*` — P2P подготовил минимальный стартовый запас/первые медиа-данные;
- `player_resolve_ok` — исходный descriptor/URL подготовлен для Player;
- `player_start` — создана/обновлена внутренняя playback session;
- `player_ready` — Media3/внутренний Player сообщил READY;
- `player_resolve_error` — подготовка источника завершилась контролируемой ошибкой;
- `player_play_request_stale` / `player_start_ignored` — устаревший запрос был отброшен.

Экспорт Diagnostics хранит последние structured rows в порядке **от новых к старым**. Анализатор сначала восстанавливает хронологический порядок, затем связывает события с primary playback request.

## Запуск

Нужен только Python 3.10+; внешние пакеты не требуются.

Linux/macOS:

```bash
python3 tools/analyze_playback_latency.py diagnostics-logs-123.txt
```

Windows PowerShell:

```powershell
py tools\analyze_playback_latency.py .\diagnostics-logs-123.txt
```

Дополнительно можно сохранить CSV и JSON:

```bash
python3 tools/analyze_playback_latency.py diagnostics-logs-123.txt \
  --csv playback-latency.csv \
  --json playback-latency.json
```

## Что показывает таблица

Для каждого `player_play_request` выводятся:

| Поле | Значение |
|---|---|
| `peer` | время от play request до первого `peer_connected` |
| `buffer` | время до `startup_buffer_ready` или `first_media_byte` |
| `resolve` | время до `player_resolve_ok` / известного embedded resolve event |
| `start` | время до `player_start` |
| `ready` | полное время `play_request -> player_ready` |
| `outcome` | `ready`, `superseded`, `stale`, `resolve_error` или `pending` |

`superseded` означает, что до READY пришёл новый primary playback request. Это ключевой показатель rapid-zap: промежуточный выбор не должен продолжать дорогую работу и догонять новый канал.

В summary дополнительно считаются:

- число запросов;
- READY/superseded/failed/pending;
- duplicate requests, которые Player уже проигнорировал;
- median, p90 и max для полного `play_request -> READY`.

## PR #102 — измерительный baseline

PR #102 добавил анализатор и regression coverage, не меняя runtime playback path. Исторический аппаратный журнал от 13 августа показал главный перекос: Ace Live resolve занимает порядка 16–18 секунд, а при быстром пролистывании пользователь способен отправлять новые channel requests через несколько сотен миллисекунд. Значит промежуточные P2P-запуски выгоднее не доводить до дорогого discovery/metadata resolve.

## Rapid-zap coalescing

PR #103 использует `CoalescingEngineRepository` только на P2P/Ace boundary:

- первый P2P request после паузы запускается немедленно;
- если следующий P2P request приходит не позднее чем через `1200 ms`, перед дорогим resolver применяется cancellable settle delay `550 ms`;
- новый выбор канала отменяет предыдущую Player coroutine, поэтому промежуточный request, находящийся в settle delay, не входит в P2P resolver;
- `stopTorrentStream()` и `releaseTorrentStream()` сбрасывают rapid-zap gate;
- обычный HTTP IPTV не вызывает `resolveTorrentStream()`, поэтому coalescing не добавляет ему задержку;
- absolute P2P discovery/metadata/startup bounds этим изменением не уменьшаются.

`P2pRapidZapGate` является отдельной чистой thread-safe policy и имеет JVM regression tests для первого request, rapid-window boundary, reset, выхода из окна и clock re-anchor.

## Ace direct soft-start window

После coalescing следующий bottleneck находится внутри Ace content-id startup race. Direct swarm и transport-metadata resolution уже запускаются конкурентно, но раньше ранний успешный metadata lookup немедленно отменял direct runtime. Это уничтожало уже выполненный DHT/peer/startup-buffer прогресс даже тогда, когда direct swarm был близок к READY.

Текущий инкремент делает существующий `DIRECT_STARTUP_SOFT_TIMEOUT_MILLIS = 8000` реальной политикой переключения путей:

- direct startup и metadata resolution по-прежнему стартуют одновременно;
- metadata может разрешиться сразу, но до истечения 8-секундного soft-window только сохраняется как готовая альтернатива и не пересоздаёт runtime;
- если direct успевает получить пригодный поток внутри окна, он выигрывает без потери уже набранного swarm-прогресса;
- если direct завершается ошибкой раньше окна, уже разрешённая metadata запускается немедленно;
- если direct всё ещё работает после 8 секунд и metadata уже готова, direct корректно отменяется и запускается metadata transport;
- если metadata появляется только после 8 секунд, она сразу получает право заменить всё ещё pending direct;
- если metadata lookup завершился ошибкой, soft-window не обрывает direct: его существующий абсолютный startup timeout продолжает ограничивать работу;
- одновременно два Ace runtime не запускаются: metadata playback начинается только после полного завершения/cleanup direct runtime.

Таким образом изменяется только момент безопасного handoff между двумя уже существующими стратегиями. Абсолютные discovery, no-connected-peer и startup bounds не увеличиваются и не уменьшаются.

## Рекомендуемый аппаратный baseline

На одном и том же устройстве выполнить отдельные серии, не смешивая их:

1. 20 последовательных обычных IPTV переключений;
2. 10 rapid-zap обычных IPTV переключений с интервалом примерно 0,2–0,8 секунды;
3. 3 запуска одного подтверждённо рабочего Ace Live/Torrent TV канала;
4. 10 переключений между 2–3 подтверждённо рабочими Ace Live каналами;
5. один заведомо недоступный Ace Live source для проверки bounded failure.

После каждой короткой серии сразу экспортировать Diagnostics. Если процесс закрылся — после повторного запуска экспортировать журнал до новой длинной серии.

## Как принимать fast-zap PR

Оптимизацию нельзя оценивать только субъективно. Перед изменением runtime сохранить baseline CSV/JSON, затем тем же набором каналов повторить тест после PR и сравнить:

- median/p90 `ready` для обычного IPTV — не должно появиться регрессии от P2P coalescing;
- median/p90 `resolve` и `ready` для Ace Live;
- число промежуточных P2P resolves во время rapid-zap — должно уменьшиться;
- `content_metadata_result` может появиться раньше direct результата, но direct runtime не должен быть уничтожен до soft-window, если он продолжает прогрессировать;
- успешный direct результат раньше 8 секунд должен выигрывать без `content_metadata_startup_result`;
- после 8 секунд готовая metadata должна получить handoff вместо бесконечного ожидания speculative direct;
- долю `superseded` rapid-zap requests;
- наличие старого `player_start`/`player_ready` после нового request — stale playback не допускается;
- время контролируемого завершения недоступного source — safety bounds остаются ограниченными.

После этого инкремента следующий runtime-шаг выбирается только по новому аппаратному breakdown `request -> peer -> buffer -> resolve -> READY`: дальнейшее уменьшение абсолютных safety bounds вслепую не допускается.

## CI

Файл `.github/workflows/playback-latency-tools.yml` запускает стандартные `unittest` для корреляции newest-first export, rapid-zap supersede, P2P milestones, resolve errors и CSV/JSON output. Анализатор использует только Python standard library. Android CI прогоняет `core:data` и `core:p2p` unit tests; изменения `core:p2p` дополнительно активируют реальный Torrent TV emulator smoke без внешнего Ace Engine.
