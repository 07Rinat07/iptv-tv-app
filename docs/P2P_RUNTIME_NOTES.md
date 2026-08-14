# Embedded P2P runtime notes

## Project boundary

Torrent TV/Ace Live playback is owned by the in-process runtime. An installed external Ace Stream Engine is not the normal backend, fallback or release requirement for Torrent TV. External products are useful only as behavioral benchmarks when the same source and device are compared during testing.

The runtime must improve by better peer selection, scheduling, buffering and recovery rather than by extending already bounded failure timeouts.

## Stale preparation and direct playback

A magnet/torrent metadata resolve may remain inside native libtorrent work for several seconds. Player actions invalidate each P2P request with a monotonically increasing epoch.

When no embedded stream has been published yet, `stopTorrentStream()` and `releaseTorrentStream()` must not wait for the repository stream mutex held by that obsolete preparation. They invalidate its epoch and let direct IPTV continue immediately. If the stale preparation later succeeds, the epoch check closes the newly created embedded stream before it can be returned to the player.

When an embedded stream is already active, stop still performs synchronous cleanup so its loopback server and torrent handle are released before playback continues.

Playback ownership is additionally protected above the repository by separate monotonic request and decoder-session identities. A delayed retry from an older channel may not resolve, publish an error or start a decoder after a newer request owns the player.

## Ace Live startup discovery and timeout budgets

The initial Ace Live discovery may return as soon as one tracker peer is available so TCP probing can begin without waiting for Mainline DHT. If that fast path yields only a small candidate set, startup performs bounded DHT probe batches and may expand discovery while media scheduling already runs.

The shared clean-room BEP-5 walker uses bounded concurrency, per-request timeouts, an absolute walk budget and a query cap. Cancellation closes outstanding UDP sockets. A successful same-swarm/bootstrap result may be reused briefly, but an empty result is never cached as proof that the distributed swarm is empty.

The narrow 30-second no-connected-peer guard and 60-second absolute startup budget remain failure bounds, not performance targets. Adaptive-streaming work must not make a slow/dead channel appear more reliable merely by increasing these values.

## Ace Live peer meaning

`discovered peers` are only network endpoints returned by tracker/DHT. They are not proof that the channel is playable. Runtime quality is represented as a staged state rather than one ambiguous counter:

`discovered → connected → handshaked → windowUseful → unchoked → producing`.

PR #108 introduced the production accounting foundation. `AceLivePeerProductionTracker` records connected/handshaked lifecycle, fresh contiguous media contribution and aggregate EWMA delivery rate; the TCP pool feeds it from actual transport/handshake/disconnect/media-ingress events. A field log may therefore legitimately contain several discovered endpoints while zero peers are producing media.

The V2b increment tightens requestability semantics. `windowUseful` means the peer's advertised `[minPiece..maxPiece]` contains the authoritative `nextNeededPiece()`. `unchoked` is the actual peer-wire state. Fresh media counts as `producing` only while both are true. The pool refreshes these stages when metadata changes, contiguous output advances the cursor, a choke/unchoke frame arrives, or recovery explicitly advances the cursor.

This quality snapshot is still an observation layer, not scheduler policy. Before scheduler feedback it must be persisted in structured diagnostics and the media-contribution boundary must be hardened to confirmed post-authenticated/post-output bytes.

## Ace Live adaptive startup buffering

The output ring remains bounded at 16 MiB by default. Startup readiness is now time-based but driven by **actual media delivery after the first media bytes arrive**:

- AUTO target duration: 4 seconds;
- AUTO minimum startup reserve: 1 MiB;
- AUTO maximum startup reserve: 6 MiB;
- throughput uses an EWMA of real retained-media growth;
- discovery/handshake latency is excluded from the throughput clock;
- the old 512-KiB startup floor is no longer sufficient;
- AUTO forced-start begins from first media, not runtime start, and requires at least 2 MiB by default;
- MANUAL mode keeps its explicit threshold and is never bypassed by AUTO forced-start.

This fixes a concrete field-log failure mode in the old policy. Previously, if discovery consumed 15–20 seconds before media arrived, `retainedBytes / runtimeAge` made a healthy stream look artificially slow. AUTO could collapse toward the minimum threshold and expose HD playback with too little reserve.

Adaptive startup V1 is merged and accepted by exact-head CI/real Torrent TV smoke. It does **not** by itself claim accepted sustained playback. The next controller must track buffer seconds after playback begins, producer/consumer rates and critical/low/target/high watermarks.

## Ace Live scheduling and retry

The runtime currently targets a bounded active peer pool, caps concurrent peers, keeps a bounded reassembly window and uses finite request/stale/media-stall recovery. The current request depth is still conservative and largely static.

Player retry releases the old local stream and prepares a new P2P session. Retrying the same stopped loopback URL is not a valid recovery path. Network/source failures also do not trigger LibVLC because a decoder change cannot repair a dead upstream.

The next scheduling hardening must use buffer pressure and producing-peer quality to adjust request depth/refill. A healthy, already-buffered stream should avoid discovery churn; a falling buffer should increase useful work and replace stale/non-producing peers within explicit bounds. The V2/V2b quality snapshot is intentionally prepared before this policy change so scheduler tuning is driven by measured useful peers rather than raw discovery counts.

## Loopback/player boundary

The current pipeline is:

`Ace peers → live scheduler/reassembly → MPEG-TS resync → AceLiveMediaBuffer → 127.0.0.1/live.ts → Media3`.

The P2P buffer and Media3 LoadControl are currently separate feedback systems. Field logs show some resolved Ace streams spending roughly 66 seconds between `player_start` and `player_ready`, while other streams reach READY in well under a second. Codec absence therefore cannot explain the whole class of failures.

Next telemetry must capture at least first localhost HTTP open/read, media producer rate, consumer rate, retained bytes/seconds, Media3 BUFFERING/READY, first rendered frame and rebuffer count. Only after those measurements should the P2P-specific Media3 LoadControl be tuned.

## Acceptance rule

A runtime change that affects P2P playback requires exact-head full Android CI and the real Torrent TV smoke without external Ace Engine. PR #108 met this gate in Android CI #495 before merge. Every following V2b/V3 runtime increment must repeat the same exact-head gate; a previous green run is not transferable to a new head. Hardware acceptance additionally uses a fixed TV Box matrix, rapid channel switching, weak-network/peer-loss cases and long-run soak tests.