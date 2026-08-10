# Rinat IPTV

**Rinat IPTV** — Android TV / TV Box приложение для просмотра IPTV с интерфейсом, рассчитанным на телевизор, пульт и мышь. Проект активно развивается: обычное IPTV и встроенный BitTorrent/P2P уже работают как отдельные сценарии, а автономная поддержка Ace Live продолжает дорабатываться.

<p align="center">
  <img src="docs/images/rinat-iptv-preview.png" alt="Rinat IPTV — экран просмотра телеканалов" width="900">
</p>

## Возможности

- **Просмотр IPTV** из M3U/M3U8-плейлистов и сохранённых списков каналов.
- **Готовые списки и ручной импорт**: URL, локальный файл, текст и поддерживаемые провайдерские источники.
- **Сканер публичных источников** для поиска новых плейлистов с последующим просмотром и импортом найденных результатов.
- **Редактор каналов и плейлистов**: работа со списками, группами и подгруппами без необходимости редактировать M3U вручную.
- **Избранное, история и EPG**: быстрый доступ к сохранённым каналам, истории просмотра и программе передач, когда она доступна у источника.
- **Media3 / ExoPlayer как основной плеер** с изолированным **LibVLC fallback**, если поток корректнее воспроизводится через VLC.
- **Встроенный BitTorrent/P2P backend** для `magnet:`, infohash, локальных `.torrent` и HTTP(S)-ссылок на `.torrent`; поток для плеера отдаётся через локальный HTTP Range.
- **Ace Stream compatibility** для источников, которые пока требуют внешний Ace Engine. Собственный Ace Live backend развивается отдельно и не подменяется обычным BitTorrent.
- **Управление с телевизора**: D-pad, Enter/Center, PageUp/PageDown, ChannelUp/ChannelDown, мышь, колесо, тачпад и сенсорный экран.
- **TV-friendly навигация**: видимый focus, возврат focus после меню и диалогов, прокрутка сфокусированного элемента в видимую область.
- **Полноэкранный просмотр**, автоскрытие элементов управления и адаптивный буфер для менее производительных TV Box.
- **Диагностика и техническая информация** для проверки сети, playback/P2P маршрута и причин fallback.
- **Экспорт и обслуживание данных**: сохранение списков и очистка устаревших/ошибочных данных через предусмотренные экраны приложения.

## Быстрый старт

После запуска откройте **«Смотреть ТВ»**:

1. выберите сохранённый список каналов; или
2. откройте один из готовых списков — приложение загрузит его и перейдёт к просмотру;
3. для добавления своих источников используйте импорт;
4. если нужны новые публичные списки, откройте Scanner и импортируйте подходящий результат.

## Статус P2P / Torrent TV

В проекте уже есть встроенный P2P-движок на libtorrent/libtorrent4j для обычного BitTorrent transport. Он поддерживает подготовку torrent metadata, приоритетную подкачку pieces, seek/read-ahead и локальную HTTP Range выдачу в Media3/LibVLC.

`acestream://content_id` и `.acelive` обрабатываются отдельно: Ace `content_id` не считается BitTorrent infohash. Совместимость с внешним Ace Engine сохраняется как fallback, пока автономный Ace Live transport проходит поэтапную реализацию и аппаратную проверку.

## Требования

- JDK 17;
- Android SDK 35;
- Android 7.0+ (`minSdk 24`).

## Сборка

Windows PowerShell:

```powershell
$env:JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"
.\\gradlew.bat --no-daemon assembleDebug
```

Linux:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon assembleDebug
```

APK создаётся стандартно:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/
```

APK/AAB не хранятся в Git. GitHub Actions прикладывает сборки к успешным запускам workflow.

## Проверка

Основной workflow `.github/workflows/android.yml` выполняет:

- Android lint;
- unit-тесты модулей;
- компиляцию Android instrumentation tests;
- `assembleDebug`;
- `assembleRelease`;
- публикацию APK и отчётов как временных GitHub Actions artifacts.

## Документация

Актуальная документация находится в [`docs/README.md`](docs/README.md).  
План развития и оставшиеся проверки — в [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Автор

**Rinat Sarmuldin**  
`ura07srr@gmail.com`

## Лицензия

Проект распространяется на условиях файла [`LICENSE`](LICENSE). Лицензии сторонних компонентов сохранены в `docs/legal/`.
