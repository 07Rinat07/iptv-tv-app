# P2P discovery state

Этот документ фиксирует устойчивый контракт состояния embedded Torrent TV / Ace Live discovery. Он не хранит SHA, результаты отдельного field run или активные ветки.

## Цель

Embedded P2P runtime должен быстро получать несколько **квалифицированных и производящих media** peers, а не считать успехом сам факт получения endpoint от tracker/DHT/PEX.

Рабочая лестница остаётся:

`discovered -> connected -> handshaked -> useful window -> unchoked -> producing -> authenticated TS -> Media3 -> first frame/audio`

Candidate count — только вход для acquisition. Startup quality измеряется временем до qualified/productive peer set и первого media/frame.

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

## Candidate sources и acquisition ordering

Один bounded candidate pool может пополняться из нескольких независимых источников:

- UDP tracker;
- Mainline DHT;
- `ut_pex` от уже подключённых peers;
- короткоживущего same-runtime learned state;
- bounded same-swarm reputation/routing state, если он содержит достаточно evidence для ranking, но не для автоматической квалификации.

Источник endpoint не является доказательством его пригодности. Tracker/DHT/PEX влияют на provenance/diversity и ranking, но не заменяют connect/handshake/useful-window/media qualification.

Refill ordering:

1. определить qualification demand и hard-cap capacity;
2. сначала зарезервировать и запустить уже известные eligible candidates;
3. если после этих starts остаётся bounded demand и есть capacity, выполнить network discovery;
4. ingest новых tracker/DHT candidates в тот же pool и использовать только оставшийся `maxStartsPerCycle` budget;
5. не выполнять обязательный tracker/DHT walk перед уже доступным PEX/known candidate;
6. не превышать hard peer cap и cycle start cap при переходе между known-candidate и discovery phase.

Этот порядок следует общему поведению зрелых BitTorrent клиентов: peer discovery — это мультиплекс независимых источников, а learned/PEX peers являются реальным input следующего connection scheduling, а не только диагностикой.

## Candidate ranking

При равной protocol/window пригодности порядок должен учитывать:

1. полезность advertised live window для текущего cursor;
2. recent same-swarm producer/handshake reputation;
3. local consecutive failures/backoff;
4. успешный handshake текущего runtime;
5. независимость discovery sources;
6. recency discovery;
7. стабильный deterministic endpoint tie-break.

Ни tracker, ни DHT, ни PEX сами по себе не дают peer привилегию выше проверенного protocol/media evidence.

## Concurrency

Discovery sources и TCP acquisition имеют разные ownership boundaries.

- tracker может немедленно дать первые TCP attempts;
- PEX candidate может немедленно участвовать в refill без ожидания нового DHT walk;
- наличие tracker/PEX candidates не означает, что DHT diversity больше не нужна, если qualification demand остаётся;
- DHT work должен быть bounded по time/query/packet/heap;
- несколько независимых TCP candidates могут квалифицироваться параллельно в пределах hard peer cap;
- `maxStartsPerCycle` применяется ко всему refill cycle, а не отдельно к каждой discovery source;
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
- candidate source (`tracker`/`dht`/`pex`/learned);
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

### B. Multi-source candidate acquisition

- tracker fast path не является завершением discovery;
- PEX — first-class candidate source;
- already-known/PEX candidates используются до нового network discovery;
- tracker/DHT добирают только оставшийся qualification demand;
- общий start/cap budget сохраняется на весь cycle.

Следующий отдельный boundary после known-candidate fast path — event-driven refill wakeup: появление новых PEX candidates или освобождение capacity после final peer failure не должно обязательно ждать полного periodic refresh interval. Такой wakeup должен быть coalesced/bounded и не запускать параллельные unbounded discovery cycles.

### C. Bounded candidate racing

Реализовать happy-eyeballs-подобную квалификацию нескольких кандидатов: небольшой параллельный пакет connect/handshake attempts, прекращение спекулятивных стартов после достижения target и сохранение hard cap.

### D. Routing quality

Перейти от плоской памяти contacts к более качественному routing selection по XOR-distance, freshness, responsiveness и network diversity без копирования vendor-specific закрытого протокола.

### E. Reference-driven gap review

Сетевые изменения сверяются с реальным field evidence и открытыми реализациями, включая AceStream/RePEX, TorrServer/anacrolix и WebTorrent/torrent-discovery. Reference code используется для понимания protocol/lifecycle behavior; перенос в Android runtime выполняется через собственные интерфейсы, bounds и deterministic tests, без слепого копирования архитектуры другого продукта.

### F. Field regression

Проверять один и тот же набор живых каналов на одном устройстве/сети, сравнивая first-frame latency, candidate-source mix, qualification rate и failure rate. Изменения timeout/buffer/peer caps допустимы только при evidence конкретного saturating limit.
