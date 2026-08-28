# P2P discovery state

Этот документ фиксирует устойчивый контракт состояния embedded Torrent TV / Ace Live discovery. Он не хранит SHA, результаты отдельного field run или активные ветки.

## Цель

Embedded P2P runtime должен быстро получать несколько **квалифицированных и производящих media** peers, а не считать успехом сам факт получения endpoint от tracker/DHT.

Рабочая лестница остаётся:

`discovered -> connected -> handshaked -> useful window -> unchoked -> producing -> authenticated TS -> Media3 -> first frame/audio`

Tracker/DHT candidate count — только вход для acquisition. Startup quality измеряется временем до qualified/productive peer set и первого media/frame.

## Ownership состояния

### DHT routing state

Разрешено сохранять только Mainline-DHT contacts, которые уже ответили валидным KRPC response и прошли локальную packet/transaction validation.

Хранилище:

- app-private;
- bounded по количеству записей и размеру файла;
- LRU + TTL;
- сохраняет `node id + endpoint + last seen`;
- сохраняет реальный возраст записи между process restarts;
- ограничивает network concentration;
- failed warm contact удаляется из routing memory;
- bootstrap nodes всегда остаются fallback и не заменяются persistent cache.

Запрещено хранить в routing cache:

- swarm/content id;
- media payload;
- tracker credentials;
- playlist URL;
- пользовательские токены.

### Peer reputation

Peer reputation всегда scoped по точному ключу:

`swarm key + endpoint`

Один и тот же endpoint не переносит положительную репутацию между разными swarms.

Допустимое evidence:

- successful handshake;
- authenticated/resynchronized media реально принято output boundary;
- final TCP/disconnect/handshake failure после завершения внутренних retry.

Reputation используется только для ranking. Она не является permanent ban/allow list. Live peer endpoints churn, поэтому positive и negative evidence имеют короткий TTL.

## Candidate ranking

При равной protocol/window пригодности порядок должен учитывать:

1. полезность advertised live window для текущего cursor;
2. recent same-swarm producer/handshake reputation;
3. local consecutive failures/backoff;
4. успешный handshake текущего runtime;
5. независимость discovery sources;
6. recency discovery;
7. стабильный deterministic endpoint tie-break.

Ни tracker, ни DHT сами по себе не дают peer привилегию выше проверенного protocol/media evidence.

## Concurrency

Discovery sources и TCP acquisition имеют разные ownership boundaries.

- tracker может немедленно дать первые TCP attempts;
- наличие tracker candidates не означает, что DHT diversity больше не нужна;
- DHT work должен быть bounded по time/query/packet/heap;
- несколько независимых TCP candidates могут квалифицироваться параллельно в пределах hard peer cap;
- после появления достаточного qualified/productive set лишний speculative acquisition должен прекращаться;
- stale generation не может получить ownership после нового channel request.

## Persistence lifecycle

Persistent state является optimization, а не correctness dependency.

Если файл отсутствует, повреждён, устарел или содержит отдельную плохую строку:

- runtime продолжает работу;
- malformed entry игнорируется независимо;
- bootstrap/tracker discovery остаётся доступен;
- приложение не должно падать из-за discovery cache.

Запись выполняется атомарно через временный файл и замену после flush/fsync. Disk I/O не выполняется под refill candidate state lock.

## Performance target

Для живых Torrent TV swarms целевой regression benchmark — быстрый первый media/frame и быстрый channel switching на одном и том же TV Box/network. Длительное ожидание 20–60 секунд нельзя объявлять нормой только потому, что общий startup timeout позволяет ждать столько времени.

Основные метрики:

- first candidate;
- tracker elapsed;
- DHT warm seeds/cache state;
- DHT query/failure count;
- first TCP connected;
- first accepted handshake;
- first useful window;
- first producing peer;
- first authenticated/resynchronized media;
- first Media3 frame/audio.

## Этапы развития

### A. Persistent discovery state

- persistent verified DHT routing contacts;
- same-swarm peer reputation;
- regression tests для TTL, process restore, swarm isolation и ranking.

### B. Continuous source acquisition

Убрать трактовку tracker fast-path как завершённого discovery. Tracker должен давать immediate candidates, пока bounded DHT acquisition независимо пополняет diversity до появления достаточного qualified set.

### C. Bounded candidate racing

Реализовать happy-eyeballs-подобную квалификацию нескольких кандидатов: небольшой параллельный пакет connect/handshake attempts, прекращение спекулятивных стартов после достижения target и сохранение hard cap.

### D. Routing quality

Перейти от плоской памяти contacts к более качественному routing selection по XOR-distance, freshness, responsiveness и network diversity без копирования vendor-specific закрытого протокола.

### E. Field regression

Проверять один и тот же набор живых каналов на одном устройстве/сети, сравнивая first-frame latency и failure rate. Изменения timeout/buffer/peer caps допустимы только при evidence конкретного saturating limit.
