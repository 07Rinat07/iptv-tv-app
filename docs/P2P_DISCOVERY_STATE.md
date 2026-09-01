# P2P discovery state

Этот документ фиксирует устойчивый контракт состояния embedded Torrent TV / Ace Live discovery. Он не хранит SHA, результаты отдельного field run или активные ветки.

## Цель

Embedded P2P runtime должен быстро получать несколько **квалифицированных и производящих media** peers, а не считать успехом сам факт получения endpoint от tracker/DHT/PEX.

Рабочая лестница остаётся:

`discovered -> connected -> handshaked -> useful window -> unchoked -> producing -> authenticated TS -> Media3 -> first frame/audio`

Tracker/DHT/PEX candidate count — только вход для acquisition. Startup quality измеряется временем до qualified/productive peer set и первого media/frame.

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

Reputation используется только для ranking/backoff. Она не является permanent ban/allow list. Live peer endpoints churn, поэтому positive и negative evidence имеют короткий TTL.

### Learned runtime candidates

Tracker, DHT и `ut_pex` могут пополнять единый bounded candidate state текущего runtime. Уже известный eligible candidate не должен ждать нового tracker/DHT network pass только потому, что qualified peer target ещё не достигнут.

Контракт refill:

1. сначала планируются eligible learned candidates, включая PEX peers;
2. их start attempts немедленно расходуют общий `maxStartsPerCycle`;
3. затем bounded tracker/DHT discovery продолжает пополнять diversity, если qualified/adaptive/stale demand ещё существует;
4. newly discovered candidates могут занять только оставшуюся часть того же cycle start budget;
5. hard active-peer/socket cap и reservation ownership остаются обязательными на обеих фазах.

Таким образом fast path ускоряет использование уже полученного peer evidence, но не превращает PEX или cache в единственный источник discovery.

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

PEX-only candidates должны оставаться bounded и по возможности диверсифицироваться по независимым announcing peers, чтобы один плохой источник PEX не заполнял весь connection budget.

## Concurrency

Discovery sources и TCP acquisition имеют разные ownership boundaries.

- tracker может немедленно дать первые TCP attempts;
- наличие tracker candidates не означает, что DHT diversity больше не нужна;
- уже полученные PEX/known candidates не должны блокироваться новым сетевым discovery;
- DHT work должен быть bounded по time/query/packet/heap;
- несколько независимых TCP candidates могут квалифицироваться параллельно в пределах hard peer cap;
- один logical refill cycle имеет единый bounded start budget для learned + newly discovered peers;
- overlapping periodic/adaptive refill cycles должны сериализоваться, чтобы не дублировать discovery и reservations;
- после появления достаточного qualified/productive set лишний speculative acquisition должен прекращаться;
- stale generation не может получить ownership после нового channel request.

Event-driven wakeup на новый PEX evidence или освобождение qualification capacity является отдельным следующим boundary. Он должен быть coalesced и сериализован с periodic/adaptive refill, а не создавать coroutine/discovery job на каждый PEX packet.

## Persistence lifecycle

Persistent state является optimization, а не correctness dependency.

Если файл отсутствует, повреждён, устарел или содержит отдельную плохую строку:

- runtime продолжает работу;
- malformed entry игнорируется независимо;
- bootstrap/tracker discovery остаётся доступен;
- приложение не должно падать из-за discovery cache.

Запись выполняется атомарно через временный файл и замену после flush/fsync. Disk I/O не выполняется под refill candidate state lock.

Текущая persistent reputation используется для ranking, но не является перечислимым warm peer cache. Bounded same-swarm warm peer cache / RePEX-подобное повторное использование недавно подтверждённых peers между соседними runtime остаётся следующим отдельным этапом и должно иметь TTL, size/network-diversity bounds и fail-open bootstrap semantics.

## Performance target

Для живых Torrent TV swarms целевой regression benchmark — быстрый первый media/frame и быстрый channel switching на одном и том же TV Box/network. Длительное ожидание 20–60 секунд нельзя объявлять нормой только потому, что общий startup timeout позволяет ждать столько времени.

Основные метрики:

- first candidate;
- candidate source (`tracker`, `DHT`, `PEX`, future warm cache);
- tracker elapsed;
- DHT warm seeds/cache state;
- DHT query/failure count;
- first TCP connected;
- first accepted handshake;
- first useful window;
- first producing peer;
- first authenticated/resynchronized media;
- first Media3 frame/audio;
- learned candidate start latency относительно момента появления PEX/known evidence.

## Этапы развития

### A. Persistent discovery state

- persistent verified DHT routing contacts;
- same-swarm peer reputation;
- regression tests для TTL, process restore, swarm isolation и ranking.

### B. Continuous source acquisition

Tracker fast-path не является завершённым discovery. Tracker должен давать immediate candidates, пока bounded DHT acquisition независимо пополняет diversity до появления достаточного qualified set.

Уже полученные learned/PEX candidates планируются до нового network acquisition pass. Discovery после этого продолжает работать в пределах прежних bounds и может использовать только оставшуюся часть start budget текущего цикла.

### C. Event-driven learned-peer refill

Новый PEX evidence и освобождение qualification capacity должны будить refill без обязательного ожидания periodic interval. Сигналы обязаны coalesce, а один serialized orchestration worker должен владеть discovery/start cycle, чтобы burst PEX не порождал unbounded jobs.

### D. Warm same-swarm peer cache

Добавить bounded RePEX-подобный cache недавно подтверждённых same-swarm peers. Cache должен быть optimization: короткий TTL, строгий swarm scope, hard entry cap, network diversity, failure decay и обязательный tracker/DHT bootstrap fallback.

### E. Bounded candidate racing

Сохранять happy-eyeballs-подобную квалификацию нескольких кандидатов: небольшой параллельный пакет connect/handshake attempts, прекращение спекулятивных стартов после достижения target и сохранение hard cap.

### F. Routing quality

Переходить от плоской памяти contacts к более качественному routing selection по XOR-distance, freshness, responsiveness и network diversity без копирования vendor-specific закрытого протокола. Зрелые open-source реализации используются как reference architecture для протокольных механизмов и lifecycle, а не как основание для слепого копирования несовместимого кода.

### G. Field regression

Проверять один и тот же набор живых каналов на одном устройстве/сети, сравнивая first-frame latency, acquisition source mix, endpoint reuse и failure rate. Изменения timeout/buffer/peer caps допустимы только при evidence конкретного saturating limit.
