# P2P content transport boundary

Torrent-TV `acestream://content_id` is not treated as a BitTorrent infohash.

The playback path resolves a content id through `AceContentTransportResolver` and accepts only a proven non-live BitTorrent transport for the embedded libtorrent backend. Ace Live, HLS/wrapper/unknown transports, resolver failures and embedded preparation failures remain on the explicit compatibility fallback path.

The current production metadata source is still an external Ace Engine. The compatibility resolver first uses the normal Android bound-service path. If that path fails, it probes the conventional already-running HTTP endpoint `127.0.0.1:6878` once and retries metadata resolution. This covers installations where the engine HTTP process is alive but Android package/service discovery is unavailable. It does not make pure content-id playback autonomous.

The embedded parser also accepts the official Ace descriptor forms `acestream:?infohash=...`, `acestream:?magnet=...`, `acestream:?url=...`, `acestream:?data=...` and `acestream:?content_id=...`. Only descriptors that explicitly prove ordinary BitTorrent metadata (`infohash`, `magnet`, or a `.torrent` URL/local file) are routed to the embedded libtorrent backend. `content_id` remains a separate Ace identity and `.acelive` is never reinterpreted as standard BitTorrent.

For non-live BitTorrent metadata, the resolver can now use the official `transport_file_data` payload when no direct infohash is available. The payload is size-limited, Base64-decoded into the app cache as a deterministic local `.torrent`, and then validated by the existing libtorrent metadata path before streaming. Invalid, oversized, live or explicitly non-BitTorrent payloads remain on the compatibility fallback. This avoids a second DHT metadata fetch when the transport file is already available, but it still does not claim autonomous resolution of a pure content id when no metadata provider is reachable.

The next transport increment must add an independent metadata provider based only on a verified public protocol/open implementation. It must preserve the distinction between `content_id`, BitTorrent `infohash`, transport-file data and Ace Live descriptors. Official Ace documentation treats content id, infohash, transport-file URL and magnet as distinct playback inputs; live transport uses its own piece/chunk protocol and `.acelive` descriptor, so a 40-character content id must never be guessed to be a BTIH.

Regression acceptance remains hardware-driven: real `acestream://content_id` samples must reach transport resolution without falsely reporting an embedded start, ordinary magnet/infohash/.torrent playback must remain unchanged, official Ace descriptors carrying proven BitTorrent metadata must reach embedded libtorrent without an external-engine dependency, transport-file metadata must be validated by libtorrent before playback, and live transport must never be routed to standard libtorrent.
