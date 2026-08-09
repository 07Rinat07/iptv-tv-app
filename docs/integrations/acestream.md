# Ace Stream

Приложение распознаёт `acestream://`, `ace://`, Content ID, magnet, `.torrent`, `.acelive` и локальные Engine URL.

Интеграция использует Android Service/AIDL boundary и не подменяет Ace `content_id` BitTorrent infohash:

1. обнаружение установленного Ace Stream Media/Engine, включая `org.acestream.live`;
2. `bindService` к `org.acestream.engine.service.v0.IAceStreamEngine`;
3. запуск и ожидание готовности движка;
4. получение динамического локального HTTP endpoint через AIDL;
5. нормализация descriptor: `content_id`, `magnet` или transport-file `url`;
6. `GET /ace/getstream` с `format=json`, `sid`, `_idx`, `stream_id`, `auto_start_stream=1` и `client_session_id`;
7. проверка JSON-ответа и извлечение `response.playback_url`;
8. передача только `playback_url` в основной плеер.

`/ace/getstream` является control endpoint и сам по себе не считается media URL. Если ответ содержит `error` или не содержит `response.playback_url`, resolver возвращает диагностируемую ошибку вместо попытки открыть JSON как видео.

40-символьный `content_id` остаётся hex-строкой. Decimal-конвертация и автоматическое приравнивание к BTIH запрещены. Ordinary BitTorrent продолжает идти через embedded libtorrent; Ace Live остаётся compatibility fallback до появления проверенного автономного live backend.

Если движок отсутствует, не виден Android package manager или не отвечает, пользователь получает диагностируемую ошибку без бесконечного цикла повторов. Нормальный installed-engine path использует порт, полученный по AIDL; `127.0.0.1:6878` остаётся только отдельным compatibility probe там, где он явно предусмотрен resolver-ом.
