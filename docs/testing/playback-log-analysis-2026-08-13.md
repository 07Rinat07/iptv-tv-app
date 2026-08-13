# Разбор ARM playback-журнала от 13 августа 2026 года

## Входные данные

- пять снимков ручного теста примерно с 16:05 до 16:12 (UTC+5);
- `myscanerIPTV-logs-1786619792164.txt`;
- сборка exact-head commit `fbf465ff` из Actions run #462;
- устройство сообщает Android API 28, модель `samsung/SM-S908E`, максимальный Java heap 256 MiB.

Экспорт содержит 120 последних записей `SyncLog`, отсортированных от новых к старым. Это не Android logcat и не постоянный `app.log`.

## Воспроизведение и таймауты

За интервал 16:03:58–16:16:32 (UTC+5) зафиксировано:

| Результат | Количество | Наблюдение |
|---|---:|---|
| Playback request | 50 | Включая несколько rapid-zap серий с интервалом 0,1–0,8 секунды |
| Ace Live success | 2 | Resolve примерно 16,5 и 18,4 секунды |
| Absolute startup timeout | 5 | Controlled failure ровно около 60 секунд |
| No-connected-peer timeout | 1 | Controlled 30-second guard после peer start; около 45 секунд от play request |
| EPG source failure | 43 | Один oversized XMLTV source повторял одинаковую ошибку для разных channel IDs |

Два успешных Ace Live результата доказывают, что embedded runtime, текущая DHT реализация и player handoff на этом устройстве в принципе работают. Ошибки остальных источников не являются crash: runtime возвращает bounded `AppResult.Error`, после чего UI остаётся доступен.

## EPG и память

Все EPG failures имеют одинаковую причину:

```text
EPG input exceeds the 67108864 byte safety limit (reported=83760807)
```

Это ожидаемое действие safety guard из PR #100: источник размером около 79,9 MiB отклоняется до полного удержания XMLTV в heap. В экспортированном интервале нет `OutOfMemoryError`, `app_low_memory`, Java uncaught exception, ANR или native signal.

Запись `app_trim_memory level=20` также не доказывает нехватку памяти. Android использует уровень 20 как `TRIM_MEMORY_UI_HIDDEN`: Activity перестала быть видимой. Следующая сборка записывает это отдельно как `app_ui_hidden`, не как memory pressure.

## Почему причина выходов приложения отсутствует

`IptvApp` уже устанавливает `Thread.setDefaultUncaughtExceptionHandler` и синхронно пишет ошибку в `FileLogger` (`files/logs/app.log`). Однако экран Diagnostics экспортировал только 120 строк `SyncLog`. Постоянный файл и его rotated predecessor `app.log.1` в присланный TXT не попадали.

Дополнительно одинаковая EPG source error включала `channelId` в cooldown signature. Поэтому rapid-zap создавал отдельную запись для каждого канала и быстро вытеснял более старые события из 120-row окна.

## Исправление после ручного прогона

1. Diagnostics export объединяет structured rows с bounded tail `app.log` и `app.log.1`.
2. Экспорт из Player также включает оба persistent log файла, а не только текущий `app.log`.
3. При каждом `Application.onCreate` записывается `app_process_start` с PID, process uptime, device uptime и heap.
4. `TRIM_MEMORY_UI_HIDDEN=20` записывается как lifecycle event `app_ui_hidden`.
5. Одинаковые source-level EPG failures дедуплицируются по playlist и normalized error, а не по channel ID.
6. Пользовательская Ace Live ошибка становится короткой русской формулировкой; полный raw failure остаётся в repository diagnostics.

## Следующий обязательный тест

После установки следующего exact-head APK:

1. очистить видимые diagnostics;
2. повторить 20 rapid-zap переключений;
3. если приложение закрылось, сразу открыть его снова и выполнить экспорт Diagnostics;
4. передать объединённый TXT, не выполняя длинную новую серию;
5. отдельно, при наличии ADB, сохранить `adb logcat -b all -d`, чтобы классифицировать native crash, ANR или system kill, которые Java uncaught handler не перехватывает.
