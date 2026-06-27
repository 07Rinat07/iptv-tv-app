# myscanerIPTV

Android TV/TV Box приложение для поиска, импорта, редактирования и просмотра IPTV-плейлистов.

## Возможности

- Поиск публичных M3U/M3U8 через API репозиториев и поисковые системы.
- Импорт плейлистов по URL, из файла, из текста, Xtream Codes и Stalker Portal.
- Редактор плейлистов с безопасной рабочей копией, ручной чисткой каналов и экспортом в `M3U`, `M3U8` и `TXT`.
- Встроенный Media3/ExoPlayer плеер с fallback во VLC.
- Списки каналов с группами, подгруппами, избранным, логотипами и программой передач, если у плейлиста настроен EPG.
- EPG отображается с учетом часового пояса плеера: `Asia/Oral` / Уральск / UTC+5.
- Диагностика сети, сканера, импорта, плеера и экспорт логов.
- Готовый APK для проверки на TV Box: `build-artifacts/myscanerIPTV-tvbox-debug-latest.apk`.

## Технологии

- Kotlin
- Jetpack Compose
- Media3 / ExoPlayer
- Coroutines / Flow
- Room
- WorkManager
- Hilt
- Retrofit / OkHttp

## Требования

- JDK 17
- Android SDK 35
- Android Gradle Plugin окружение, совместимое с проектом
- Android TV / Android TV Box с Android 7.0+ (`minSdk 24`)

## Быстрая сборка

Linux:

```bash
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon assembleDebug
```

Windows PowerShell:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --no-daemon assembleDebug
```

После `assembleDebug` актуальный APK автоматически обновляется здесь:

```text
build-artifacts/myscanerIPTV-tvbox-debug-latest.apk
```

## Проверка перед GitHub

Локальный прогон, близкий к GitHub Actions:

```bash
env GRADLE_USER_HOME=/tmp/iptv-gh-clean JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 GRADLE_OPTS=-Dorg.gradle.workers.max=2 TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew --no-daemon --stacktrace lintDebug :core:parser:test :sync:testDebugUnitTest :core:data:testDebugUnitTest :core:engine:testDebugUnitTest :core:network:testDebugUnitTest :core:player:testDebugUnitTest :feature:epg:testDebugUnitTest :feature:importer:testDebugUnitTest :feature:player:testDebugUnitTest :feature:scanner:testDebugUnitTest :feature:settings:testDebugUnitTest assembleDebug assembleRelease
```

Эта команда повторяет основные шаги `.github/workflows/android.yml`: lint, unit-тесты, debug-сборку и release-сборку.

## Установка на TV Box

1. Скопируйте `build-artifacts/myscanerIPTV-tvbox-debug-latest.apk` на флешку.
2. Подключите флешку к TV Box.
3. Откройте APK через файловый менеджер.
4. Разрешите установку из неизвестных источников, если Android попросит.
5. Запустите приложение.

## Основной сценарий

1. Откройте `Сканер` или `Готовые плейлисты`.
2. Найдите и сохраните плейлист.
3. Откройте `Мои плейлисты`.
4. При необходимости очистите список в `Редакторе`.
5. Откройте `Плеер`, выберите плейлист, группу и канал.
6. Если у плейлиста есть EPG, используйте кнопку `Программа` в списках каналов.
7. Для проблемных потоков попробуйте VLC или другой профиль буфера.

## Сканер

Режимы поиска:

- `Auto` - основной режим: API репозиториев, поисковые системы и seed-источники.
- `Direct API` - быстрее на стабильной сети, но чувствителен к DNS и блокировкам.
- `Search Engine` - полезен, если API GitHub/GitLab/Bitbucket недоступны.

Если поиск ничего не находит:

- проверьте интернет на TV Box;
- откройте `Настройки -> Сетевой тест`;
- попробуйте `Search Engine`;
- при DNS/timeout ошибках настройте proxy в `Настройки`.

## Редактор

Редактор позволяет:

- скрывать, показывать и удалять каналы;
- удалять нерабочие каналы вручную или массовой командой;
- менять название, группу, логотип и URL канала;
- сохранять изменения в пользовательской копии плейлиста;
- экспортировать список в `M3U`, `M3U8` или `TXT`;
- просматривать программу передач для каналов, если у плейлиста есть EPG.

## Плеер

- Встроенный плеер работает на Media3/ExoPlayer.
- VLC используется как внешний fallback.
- Каналы можно переключать из быстрого списка рядом с видео или из полного каталога.
- Программа передач в списках раскрывается кнопкой `Программа` и скрывается кнопкой `Скрыть программу`.
- Время EPG показывается для Уральска: UTC+5.

## Диагностика

В приложении есть раздел `Диагностика`:

- последние события сканера, импорта, плеера и Engine Stream;
- экспорт логов в файл;
- маскировка чувствительных данных в логах;
- сетевой тест для основных источников поиска.

Частые причины проблем:

- `UnknownHostException` - проблема DNS на TV Box, роутере или у провайдера.
- `Timeout` - источник долго отвечает или блокируется сетью.
- `Missing #EXTM3U header` - ссылка ведет не на M3U/M3U8 плейлист.
- `player_error` / `Source error` - поток недоступен, заблокирован или не поддерживается текущим декодером.

## Документация по модулям

- `docs/architecture-stage1.md` - архитектура проекта.
- `docs/stage2-scanner.md` - сканер.
- `docs/stage3-import.md` - импорт.
- `docs/stage4-editor.md` - редактор.
- `docs/stage5-player.md` - плеер.
- `docs/stage6-engine-diagnostics.md` - Engine Stream и диагностика.
- `docs/stage7-hardening.md` - стабилизация.
- `docs/stage8-release-readiness.md` - подготовка к релизу.
- `docs/stage9-device-acceptance-release-runbook.md` - проверка на устройстве.

## Безопасность

Приложение не содержит механизмов обхода DRM или взлома защищенного контента. Используйте только источники и трансляции, на которые у вас есть законные права.

## Лицензия

Copyright 2026 Rinat Sarmuldin.

Репозиторий распространяется по проприетарной лицензии. Условия указаны в `LICENSE`.
