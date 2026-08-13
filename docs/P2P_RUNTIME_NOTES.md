# Embedded P2P runtime notes

## Stale preparation and direct playback

A magnet/torrent metadata resolve may remain inside native libtorrent work for several seconds. Player actions invalidate each P2P request with a monotonically increasing epoch.

When no embedded stream has been published yet, `stopTorrentStream()` and `releaseTorrentStream()` must not wait for the repository stream mutex held by that obsolete preparation. They invalidate its epoch and let direct IPTV continue immediately. If the stale preparation later succeeds, the epoch check closes the newly created embedded stream before it can be returned to the player.

When an embedded stream is already active, stop still performs synchronous cleanup so its loopback server and torrent handle are released before playback continues.

## Ace Live startup discovery and timeout budgets

The initial Ace Live discovery may return as soon as one tracker peer is available so TCP probing can begin without waiting for Mainline DHT. If that fast path yields only one to three tracker peers, startup immediately performs one mandatory DHT-only refill. Background refill then returns to the normal four-peer discovery threshold.

The shared clean-room BEP-5 walker uses at most four concurrent KRPC requests. Each request is bounded by 2 seconds, the complete walk by 15 seconds and 64 queries, and cancellation closes outstanding UDP sockets. One silent DHT branch therefore cannot serialize the complete lookup. A successful same-swarm/bootstrap result may be reused for 20 seconds, but an empty result is never cached: a bounded `get_peers` walk with no `values` is not proof that the distributed swarm is empty.

The 30-second no-connected-peer budget starts after the first candidates enter the TCP pool. When the one-peer tracker fast path requires the mandatory DHT expansion, that budget is rearmed after the expansion unless a peer has already connected. This keeps DHT time from consuming the interval reserved for the newly found peers. Once any peer reaches `TransportConnected`, the narrow dead-swarm guard is disabled and the ordinary absolute 60-second startup budget remains authoritative.

## Ace Live buffering and retry

The Ace Live runtime currently keeps a bounded 16 MiB sliding output buffer and publishes the local stream after 4 MiB is retained. It targets six active peers, caps the pool at ten, allows two in-flight requests per peer and applies bounded request, stale-peer and media-stall timeouts.

Player retry must release the old local stream and prepare a new P2P session. Retrying the same stopped loopback URL is not a valid recovery path. Network/source failures also do not trigger LibVLC because a decoder change cannot repair a dead upstream.

The presence of these limits does not mean sustained prefetch is accepted. Current device feedback shows long channel switches and insufficient buffer replenishment on some Torrent TV channels. The next hardening increment must log retained bytes, download/consume rates and rebuffer events, then tune startup/read-ahead and peer scheduling from those measurements.
