# Rinat IPTV

**Rinat IPTV** — приложение для Android TV / TV Box для просмотра IPTV и Torrent TV с интерфейсом, рассчитанным на телевизор, пульт, мышь и тачпад.

Проект активно развивается. Подробная архитектура, статус разработки, roadmap, технические исследования и служебные материалы находятся в каталоге [`docs/`](docs/).

<p align="center">
  <img src="docs/images/rinat-iptv-player-preview.svg" alt="Rinat IPTV — интерфейс плеера" width="900">
</p>

## Возможности

- просмотр IPTV из M3U/M3U8 и сохранённых источников;
- импорт плейлистов из URL, файла и текста;
- поиск и импорт публичных IPTV-источников через Scanner;
- каталог каналов с группами и подгруппами;
- программа передач EPG при наличии данных у источника;
- Media3 / ExoPlayer как основной видеоплеер и LibVLC fallback;
- встроенная поддержка BitTorrent/P2P и Torrent TV;
- управление D-pad, Enter/Center, Channel Up/Down, мышью и тачпадом;
- полноэкранный режим, адаптивный TV-интерфейс и диагностика воспроизведения.

## Быстрый старт

1. Запустите приложение.
2. Добавьте или выберите IPTV-источник.
3. Откройте плейлист и выберите канал.
4. Для поиска новых публичных источников используйте Scanner.
5. Для параметров воспроизведения и диагностики используйте соответствующие разделы приложения.

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

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Проверка

Основные локальные проверки:

```bash
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

В Windows используйте `gradlew.bat` вместо `./gradlew`.

## Документация

Подробные материалы находятся в [`docs/`](docs/), в том числе:

- [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) — руководство пользователя;
- [`docs/PROJECT_STATUS_AND_ROADMAP.md`](docs/PROJECT_STATUS_AND_ROADMAP.md) — текущее состояние и план разработки;
- [`docs/PLAYBACK_STATUS.md`](docs/PLAYBACK_STATUS.md) — состояние playback/P2P;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — технический roadmap.
