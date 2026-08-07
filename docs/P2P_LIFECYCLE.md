# Embedded P2P stream lifecycle

The embedded BitTorrent backend owns at most one active playback stream.

- Starting a new embedded stream first releases the previous loopback server and removes its `TorrentHandle` from `SessionManager`.
- Stopping the resolved P2P stream closes the loopback server and removes the active torrent handle.
- Removing the handle does not use delete-files flags, so cached content remains reusable.
- The full libtorrent session remains available for subsequent playback until the engine itself is stopped.
- Player-facing code uses `EngineRepository.stopResolvedP2pStream()` and does not depend directly on libtorrent APIs.
