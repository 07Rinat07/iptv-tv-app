# Ace Stream

Приложение распознаёт `acestream://`, `ace://`, Content ID, magnet, `.torrent`, `.acelive` и локальные Engine URL.

Встроенный Ace Live runtime самостоятельно выполняет DHT/tracker discovery, подписанное peer-handshake, чтение live window, ограниченную очередь chunk-запросов, сборку/проверку pieces и локальную MPEG-TS выдачу. Прямые `infohash` из legacy `127.0.0.1:6878/ace/getstream` URL не отправляются в обычный libtorrent.

Для `content_id` приложение сначала пробует прямой Ace Live swarm с тем же peer-wire ключом, затем публичный transport catalog и metadata swarm. Если оба автономных пути недоступны, Android Service/AIDL integration остаётся совместимым fallback:

1. обнаружение установленного Ace Stream Media/Engine, включая `org.acestream.live`;
2. `bindService` к `org.acestream.engine.service.v0.IAceStreamEngine`;
3. запуск и ожидание готовности движка;
4. получение динамического локального HTTP endpoint через AIDL;
5. нормализация descriptor: `content_id`, `magnet` или transport-file `url`;
6. `GET /ace/getstream` с `format=json`, `sid`, `_idx`, `stream_id`, `auto_start_stream=1`, `client_session_id` и `manifest_p2p_wait_timeout=10`;
7. проверка JSON-ответа и извлечение `response.playback_url`;
8. передача только `playback_url` в основной плеер.

`/ace/getstream` является control endpoint и сам по себе не считается media URL. Если ответ содержит `error` или не содержит `response.playback_url`, resolver возвращает диагностируемую ошибку вместо попытки открыть JSON как видео.

HTTP-клиент локального engine использует короткий 2-секундный connect timeout, но read/call deadlines превышают 10-секундное окно ожидания P2P manifest. Это не даёт приложению оборвать легитимный запуск live transport раньше самого engine.

40-символьный `content_id` остаётся hex-строкой. Decimal-конвертация и автоматическое приравнивание к BTIH запрещены. Ordinary BitTorrent продолжает идти через embedded libtorrent. Полученные через metadata API live descriptor или live swarm передаются встроенному Ace Live runtime; внешний Engine полностью воспроизводит поток только как последний fallback.

Если движок отсутствует, не виден Android package manager или не отвечает, пользователь получает диагностируемую ошибку без бесконечного цикла повторов. Нормальный installed-engine path использует порт, полученный по AIDL; `127.0.0.1:6878` остаётся только отдельным compatibility probe там, где он явно предусмотрен resolver-ом.
