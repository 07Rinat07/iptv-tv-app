# Ace Stream Engine: Android Service integration

## Назначение

Приложение не встраивает и не распространяет Ace Stream Engine. Оно подключается к отдельно установленному официальному приложению Ace Stream через Android Service/AIDL, получает локальный HTTP endpoint и передаёт подготовленный URL в существующий Media3-плеер.

## Поддерживаемые источники

- `acestream://<content-id>`
- `ace://<content-id>`
- 40-символьный Content ID
- `infohash:<content-id>`
- magnet URI
- URL transport-файла `.torrent`
- URL transport-файла `.acelive`
- уже подготовленные localhost URL вида `/ace/getstream`

Content ID отправляется в официальный HTTP API как параметр `id`. Magnet и transport-file URL отправляются как параметр `url`.

## Запуск движка

1. Проверяются известные официальные пакеты:
   - `org.acestream.media`
   - `org.acestream.media.atv`
   - `org.acestream.core`
   - `org.acestream.core.atv`
2. Дополнительно выполняется поиск сервиса по action `org.acestream.engine.service.v0.IAceStreamEngine`.
3. При наличии нескольких вариантов выбирается пакет с наибольшим version code.
4. Клиент связывается с сервисом, регистрирует callback и вызывает `startEngineWithCallback`.
5. После готовности считываются Engine API port, HTTP API port и access token.
6. Для воспроизведения используется `http://127.0.0.1:<http-api-port>/ace/getstream?...`.

## Совместимость

- Android minSdk 24.
- Android 11+ package visibility объявлена через `<queries>`.
- Android TV и обычные Android-устройства используют один сервисный слой.
- Сохраняется ручной режим подключения к локальному или сетевому Engine endpoint.
- При отсутствии официального приложения выводится диагностическая ошибка, а приложение не падает.

## Лицензирование

AIDL-сигнатуры совместимы с официальным проектом `acestream/acestream-engine-client`, опубликованным под MIT License. Copyright и текст лицензии сохранены в `docs/third-party/acestream-engine-client-LICENSE.txt`.

Интеграция не включает плейлисты, Content ID или медиаконтент. Пользователь обязан использовать только источники, на просмотр которых у него есть права.
