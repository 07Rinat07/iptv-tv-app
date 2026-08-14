# Playback latency baseline

Этот документ описывает первый измерительный шаг fast-zap/playback-hardening после PR #101.

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

## Рекомендуемый аппаратный baseline

На одном и том же устройстве выполнить отдельные серии, не смешивая их:

1. 20 последовательных обычных IPTV переключений;
2. 10 rapid-zap обычных IPTV переключений с интервалом примерно 0,2–0,8 секунды;
3. 3 запуска одного подтверждённо рабочего Ace Live/Torrent TV канала;
4. 10 переключений между 2–3 подтверждённо рабочими Ace Live каналами;
5. один заведомо недоступный Ace Live source для проверки bounded failure.

После каждой короткой серии сразу экспортировать Diagnostics. Если процесс закрылся — после повторного запуска экспортировать журнал до новой длинной серии.

## Как принимать следующий fast-zap PR

Оптимизацию нельзя оценивать только субъективно. Перед изменением runtime сохранить baseline CSV/JSON, затем тем же набором каналов повторить тест после PR и сравнить:

- median/p90 `ready` для обычного IPTV;
- median/p90 `resolve` и `ready` для Ace Live;
- долю `superseded` rapid-zap requests;
- наличие старого `player_start`/`player_ready` после нового request;
- время контролируемого завершения недоступного source.

Следующий кодовый инкремент должен быть направлен на самый большой измеренный участок (`request -> peer`, `peer -> buffer`, `resolve -> start` или `start -> READY`), а не уменьшать абсолютные safety bounds вслепую.

## CI

Файл `.github/workflows/playback-latency-tools.yml` запускает стандартные `unittest` для корреляции newest-first export, rapid-zap supersede, P2P milestones, resolve errors и CSV/JSON output. Анализатор использует только Python standard library.
