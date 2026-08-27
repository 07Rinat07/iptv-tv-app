# P2P content transport boundary

## Identity classification

Torrent TV `acestream://content_id` не является BitTorrent infohash и не должен автоматически передаваться в libtorrent как BTIH.

Transport classification сохраняет разные identity domains:

- Ace `content_id`;
- Ace Live transport/live identity;
- ordinary BitTorrent `infohash`/magnet/`.torrent`;
- transport descriptor URL/file;
- explicit compatibility descriptor.

Переход между domains разрешён только после явного validated descriptor evidence. Совпадение длины или hex-представления не является доказательством эквивалентности identity.

## Embedded routing

Torrent TV / Ace Live обрабатывается embedded Ace runtime. Его failure возвращается как failure этого runtime и не должен автоматически запускать установленный Ace Engine.

Ordinary BitTorrent попадает в embedded libtorrent path только когда transport доказан как обычный torrent metadata source: валидный infohash, magnet или validated `.torrent` descriptor/file.

`.acelive` и live-specific transport не переинтерпретируются как standard BitTorrent.

## Descriptor handling

Поддерживаемые Ace descriptor forms нормализуются до одного явного transport kind. Parser должен fail closed на неоднозначном или malformed descriptor и не угадывать transport по одному URL suffix/content-id shape.

Если descriptor содержит transport-file payload:

- payload имеет hard size limit;
- decode/write выполняется bounded;
- локальный файл валидируется существующим metadata path;
- live/non-BitTorrent descriptor не перенаправляется в libtorrent;
- временный файл и metadata lifecycle принадлежат текущей playback generation.

## External compatibility path

Compatibility resolver может использовать явно поддерживаемый bound-service/loopback Ace Engine contract для descriptor cases, где это предусмотрено продуктом. Такой path:

- не является success criterion embedded Torrent TV;
- не включается скрыто после embedded failure;
- возвращает Media3/LibVLC только validated playback media URL, а не JSON/control endpoint;
- не должен хранить или логировать credential-bearing query/content identity.

## Live transport invariants

Ace Live runtime владеет собственными handshake, live-window, piece/chunk, recovery и media-output semantics. Geometry и cryptographic/public-key metadata используются только из validated source/descriptor contract; speculative constants не должны становиться универсальной классификацией произвольного `content_id`.

Peer discovery, transport connection, protocol qualification и media production остаются отдельными стадиями. См. [`P2P_RUNTIME_NOTES.md`](P2P_RUNTIME_NOTES.md) и [`ACE_LIVE_STARTUP_TIMELINE.md`](ACE_LIVE_STARTUP_TIMELINE.md).

## Player boundary

Успешный P2P transport публикует bounded localhost media endpoint. Player не должен знать tracker/DHT/request scheduler internals и не должен компенсировать upstream discovery/handshake failure изменением decoder fallback policy.

При channel switch obsolete generation теряет ownership transport/loopback/session. Старый URL или delayed resolve не может победить более новую generation.

## Acceptance

Для изменения transport classification нужны regression tests на все соседние domains: `content_id`, Ace Live descriptor, ordinary magnet/infohash/`.torrent`, malformed/ambiguous input и explicit compatibility route. Device-dependent Torrent TV acceptance выполняется отдельно на реальных источниках без обязательного внешнего Ace Engine.