# P2P content transport boundary

Torrent-TV `acestream://content_id` is not treated as a BitTorrent infohash.

The playback path resolves a content id through `AceContentTransportResolver` and accepts only a proven non-live BitTorrent transport for the embedded libtorrent backend. Ace Live, HLS/wrapper/unknown transports, resolver failures and embedded preparation failures remain on the explicit compatibility fallback path.

The current production resolver still obtains transport metadata from a locally available external Ace Engine. Therefore this stage is an architectural decoupling, not a claim of autonomous Torrent-TV playback.

The next transport increment must add an independent metadata provider based only on a verified public protocol/open implementation. It must preserve the distinction between `content_id`, BitTorrent `infohash`, transport-file data and Ace Live descriptors.

Regression acceptance remains hardware-driven: real `acestream://content_id` samples must reach transport resolution without falsely reporting an embedded start, ordinary magnet/infohash/.torrent playback must remain unchanged, and live transport must never be routed to standard libtorrent.
