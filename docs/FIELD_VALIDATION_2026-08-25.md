# Полевой тест 25 августа 2026 — recovery baseline

## Контрольная сборка

Полевой тест выполнен после консолидации `main` на коммите `bb49b2aa1c460923358943fee7afc650e7b58d68`.

Эта сборка не принимается как стабильная. Ручной тест выявил блокирующие дефекты, которые имеют приоритет над новыми архитектурными и телеметрическими инкрементами.

## Подтверждённые результаты

### 1. P0 — OOM и торможение больших каталогов

На устройстве с лимитом heap около 256 MB процесс достигал примерно 242 MB и падал с `OutOfMemoryError` в OkHttp. После перезапуска зафиксирован второй OOM в Room при чтении `FavoriteChannelLookupDao_Impl`.

Подтверждённый риск в коде: Favorites непрерывно наблюдал `SELECT * FROM channels` и материализовал полный `Flow<List<ChannelEntity>>` при изменениях большой таблицы каналов.

Следствие: импорт/сохранение найденных Scanner плейлистов и загрузка крупных Torrent TV каталогов могут вызывать тяжёлое давление на память, фризы и завершение процесса.

Рабочий issue: #229.

### 2. P0 — новая Home не является настоящей главной

`HomeScreen/HomeDashboard` подключены к маршруту HOME, но HOME остаётся вложенным в legacy `AppRoot` shell: старый `Scaffold`, верхняя панель `Rinat IPTV / Раздел`, внешние padding/Surface.

Ручной тест визуально подтвердил неустойчивый запуск нового Home и возврат к старой оболочке.

Рабочий issue: #230.

### 3. P0 — новый Player формально маршрутизирован, но пользовательский результат не принят

`MainActivity` ведёт Player routes в `StablePlayerScreen`, однако полевой тест не показал ожидаемый новый TV-first Player. Видимый экран остаётся похожим на старый panel/card layout.

Рабочий issue: #231.

### 4. P0 — встроенный Torrent TV runtime существенно уступает same-device Ace Stream benchmark

На тех же каналах Televizo + Ace Stream Engine на том же устройстве заметно чаще получает рабочие пиры и запускает поток.

Встроенный runtime показывает несколько разных отказов:

- tracker быстро возвращает один endpoint, но `connected=0`, а DHT на direct path часто не запускается;
- DHT способен найти 4 peers, TCP способен подключиться к 2, но `handshaked=0`;
- встречается peer-found/no-media timeout;
- встречается `p2p_stalled` после уже начавшегося воспроизведения;
- часть P2P boundary sessions успешно достигает READY/first audio/first video frame, то есть player boundary работает, когда embedded runtime реально производит media.

Следовательно, дальнейшая работа должна идти по реальной цепочке:
`discovery -> connect -> handshake -> metadata/live-window -> requests -> accepted/completed/authenticated pieces -> TS output -> player`.

Рабочий issue: #232.

### 5. P1 — возможный unintended rapid playback churn

Диагностика содержит последовательность `player_play_request` для десятков соседних channelId примерно каждые 150–200 ms.

Если это генерируется перемещением focus, а не намеренным rapid-zap пользователя, такой поток запросов создаёт лишние Player/P2P sessions, CPU/network churn и дополнительное давление на память.

Рабочий issue: #233.

## Что подтверждено рабочим

Обычный playback не следует переписывать целиком. В журнале есть реальные `first_video_frame` для Media3 с H.264/AAC и H.264/MP2, а несколько сессий достигают `ready=true`, `first_audio=true`, `first_video_frame=true`.

На скриншотах реальные каналы отображают видео, включая случаи LibVLC fallback.

Это означает: обычный IPTV playback сохраняется как рабочая база; исправления должны быть локализованы вокруг memory/catalog, Home integration, Player presentation и embedded Torrent TV runtime.

## Новый порядок работ

1. #229 — устранить OOM/полное чтение channels и повторить большой Scanner/import test.
2. #230 — сделать Home настоящим full-screen root route.
3. #231 — довести production Player до реально нового TV-first UX.
4. #232 — закрывать embedded Torrent TV по первой отсутствующей runtime-стадии, сравнивая те же каналы с Ace Stream benchmark.
5. #233 — проверить, создаёт ли focus/browse поток playback requests; если да — отделить focus от explicit play.
6. Только после этого возвращаться к EPG/archive/polish и более широким acceptance/soak тестам.

## Acceptance policy после этого теста

CI остаётся обязательным regression gate, но не является доказательством работоспособности приложения на TV Box.

Для P0 acceptance обязательны:
- свежая сборка из `main`;
- ручной запуск;
- скриншоты/видео видимого поведения;
- экспорт diagnostics;
- для Torrent TV — same-device A/B на фиксированном наборе каналов.

Архитектурные, telemetry-only и cosmetic PR не должны опережать эти блокеры.
