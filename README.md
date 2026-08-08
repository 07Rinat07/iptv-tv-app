# Rinat IPTV

Android TV/TV Box приложение для просмотра IPTV, управления плейлистами и работы с EPG.

## Быстрый старт

После запуска открывается экран **«Смотреть ТВ»**:

1. выберите один из сохранённых списков каналов; или
2. нажмите **«Смотреть»** у готового списка — приложение загрузит его и сразу откроет плеер;
3. когда готовых источников станет мало, используйте кнопку **«Найти новые списки в сканере»**.

Сканер и поиск плейлистов остаются отдельным инструментом и не изменяются текущим этапом.

## Возможности

- готовые и пользовательские M3U/M3U8-плейлисты;
- сканирование публичных источников и ручной импорт;
- редактор каналов и безопасные рабочие копии;
- Media3/ExoPlayer с изолированным LibVLC fallback;
- Ace Stream Android Service/AIDL;
- группы, подгруппы, избранное, история и EPG;
- управление D-pad, пультом, мышью, тачпадом и сенсорным экраном;
- Scanner: видимые TV-кнопки постраничной прокрутки, PageUp/PageDown и ChannelUp/ChannelDown без изменения алгоритма поиска;
- полноэкранный режим, автоскрытие панели и постраничная навигация;
- адаптивный буфер для слабых TV Box;
- диагностика, экспорт и сброс старых ошибок.

## Требования

- JDK 17;
- Android SDK 35;
- Android 7.0+ (`minSdk 24`).

## Сборка

Windows PowerShell:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --no-daemon assembleDebug
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

APK/AAB не хранятся в Git. GitHub Actions прикладывает их к успешным запускам workflow.

## Проверка

Основной workflow `.github/workflows/android.yml` выполняет:

- Android lint;
- unit-тесты модулей;
- `assembleDebug`;
- `assembleRelease`;
- публикацию APK и отчётов как временных GitHub Actions artifacts.

## Документация

Актуальная документация находится в [`docs/README.md`](docs/README.md).
План развития и оставшиеся проверки: [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Автор

**Rinat Sarmuldin**  
`ura07srr@gmail.com`

## Лицензия

Проект распространяется на условиях файла [`LICENSE`](LICENSE). Лицензии сторонних компонентов сохранены в `docs/legal/`.
