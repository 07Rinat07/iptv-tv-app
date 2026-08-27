# Playback latency analysis

`tools/analyze_playback_latency.py` разбирает TXT export из Diagnostics и связывает primary playback request с ключевыми Player/P2P milestones. Документ описывает только повторяемую процедуру; результаты конкретных field runs должны храниться в Issue/PR/Actions artifact.

## Запуск

Требуется Python 3.10+ без внешних пакетов.

Linux/macOS:

```bash
python3 tools/analyze_playback_latency.py diagnostics-logs.txt
```

Windows PowerShell:

```powershell
py tools\analyze_playback_latency.py .\diagnostics-logs.txt
```

Дополнительный CSV/JSON:

```bash
python3 tools/analyze_playback_latency.py diagnostics-logs.txt \
  --csv playback-latency.csv \
  --json playback-latency.json
```

## Интерпретация

Анализатор восстанавливает хронологию из bounded diagnostics export и коррелирует доступные события одной primary playback попытки, включая:

- play request;
- P2P resolve/discovery/peer milestones, если они присутствуют;
- localhost/P2P boundary;
- Media3 buffering/load/READY;
- first frame и другие поддерживаемые player milestones;
- stale/superseded/error outcome.

`READY` не является доказательством исправности upstream discovery, а найденный peer не является доказательством media production. Для P2P failure сначала определяется первая отсутствующая стадия по [`../ACE_LIVE_STARTUP_TIMELINE.md`](../ACE_LIVE_STARTUP_TIMELINE.md).

## Rapid switching

При серии быстрых channel requests ожидаемое корректное поведение — старая попытка становится stale/superseded и не получает ownership после более новой generation. Сам факт большого количества play requests должен отдельно проверяться: перемещение focus по списку не должно запускать каждый промежуточный канал без явно задокументированного поведения.

## Evidence contract

Для сравнения запусков фиксировать:

- exact app build SHA/version;
- device/API;
- channel/source class без публикации secrets;
- outcome;
- ключевые elapsed milestones;
- ссылку на исходный diagnostics artifact.

Не коммитить в `docs/` новый Markdown-файл для каждого запуска. Field output прикладывать к соответствующему Issue/PR либо Actions artifact.