# Embedded P2P runtime notes

## Stale preparation and direct playback

A magnet/torrent metadata resolve may remain inside native libtorrent work for several seconds. Player actions invalidate each P2P request with a monotonically increasing epoch.

When no embedded stream has been published yet, `stopTorrentStream()` and `releaseTorrentStream()` must not wait for the repository stream mutex held by that obsolete preparation. They invalidate its epoch and let direct IPTV continue immediately. If the stale preparation later succeeds, the epoch check closes the newly created embedded stream before it can be returned to the player.

When an embedded stream is already active, stop still performs synchronous cleanup so its loopback server and torrent handle are released before playback continues.
