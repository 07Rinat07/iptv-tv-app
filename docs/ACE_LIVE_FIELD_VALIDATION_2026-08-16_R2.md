# Ace Live field validation — 2026-08-16 R2

## Scope

Second manual TV/Android field pass after PR #130 (`Media3 + localhost reopen telemetry`). The purpose of this pass was to decide whether the next V4d behavior change should be HTTP logical-offset resume, forward reserve, pre-READY pressure tuning, or peer acquisition/qualification.

## Observed playback behavior

- `Моя планета HD` and `Дикая рыбалка HD` can reach playable video, with LibVLC fallback visible in the captured field screenshots.
- Some channel switches remain slow enough to show `Подключение к каналу...` for a prolonged period.
- `Премиальное HD` ended with the generic 60-second Torrent TV preparation timeout.
- The channel list can remain in `P2P · поиск пиров...` while the runtime is still qualifying candidates.

## Canonical successful startup evidence

For the retained successful P2P session (`channelId=3300`):

| Milestone | elapsed from Ace Live preparation |
|---|---:|
| transport/direct attempt | 0 ms |
| first candidate / discovery complete | 2472 ms |
| TCP connected | 5376 ms |
| Ace handshake accepted | 5568 ms |
| useful live window | 5684 ms |
| first authenticated/resynchronized media | 6740 ms |
| startup buffer ready | 10298 ms |
| localhost reader open / first read | 10364 ms |

Once the localhost stream was exposed to Media3, the player side was fast:

| Player boundary | elapsed from player start |
|---|---:|
| load started | 101 ms |
| READY | 135 ms |
| first video frame | 223 ms |
| first audio | 361 ms |

The localhost request was `GET` with no `Range` header (`requested_start=none`). Therefore this run does **not** support implementing HTTP Range/resume as the next behavior change.

The successful peer also demonstrates that a valid Ace handshake can complete quickly after TCP connect: roughly 192 ms in this sample (`5376 -> 5568 ms`).

## Failed-channel evidence

The final failed sessions show a different blocker:

- candidates were discovered (`1..5` depending on the session);
- TCP repeatedly reached `connected=1`;
- `handshaked=0`, `windowUseful=0`, `producing=0` persisted;
- no `first_media`, `buffer_ready`, localhost-open, READY, first-frame, or first-audio milestone occurred;
- the public-facing 60-second timeout was reached.

This means the exported evidence does **not** prove that the content ID is stale. It proves only that the runtime failed to qualify a useful Ace Live peer before the absolute preparation timeout.

The current exported diagnostics still do not distinguish whether those pre-handshake failures were `HANDSHAKE_TIMEOUT`, explicit handshake rejection, remote close, connect failure, or another final disconnect reason.

## Decision

### Do not implement HTTP resume yet

There was no `Range` request and no Media3 reopen sequence in the retained successful session. Media3 reached READY/first-frame in sub-second time after localhost exposure. HTTP logical-offset resume remains a later evidence-gated item.

### Do not tune Media3 buffer policy yet

The dominant startup latency is before player consumption. The player is not the critical path in this run.

### Next V4d increment: bounded pre-handshake peer qualification

The TCP pool currently allows the same endpoint to consume the normal reconnect budget before it has ever completed an Ace handshake. For a peer that is reachable at TCP level but cannot qualify for the requested Ace swarm, repeating the same endpoint delays alternative-candidate validation.

Implement a separate pre-handshake reconnect budget:

- default/runtime pre-handshake reconnect attempts: `0`;
- keep the existing bounded reconnect budget for peers that have successfully handshaked at least once;
- final pre-handshake failure returns ownership to the refill coordinator immediately, where existing bounded backoff applies;
- do not permanently ban an endpoint;
- do not change the absolute 60-second preparation bound in this increment;
- do not change scheduler, request depth, refill target/max peers, TS recovery/discontinuity, output-buffer size, HTTP behavior, Media3 behavior, or generic IPTV.

Also export sparse peer lifecycle diagnostics containing:

- peer id;
- connect/reconnect attempt;
- handshake accepted/rejected + reject reason;
- connect failure + retrying flag;
- disconnect reason + retrying flag;
- requeued-piece count;
- startup elapsed time.

Suggested status: `embedded_ace_live_peer_lifecycle`.

## Acceptance after the increment

Run the same device against:

1. one known-working Torrent TV channel;
2. `Премиальное HD` or another channel that previously reached TCP but no handshake;
3. 10 rapid P2P channel switches.

The next exported log must let us compare:

- time from first candidate to first accepted handshake;
- how many distinct peers are attempted before handshake;
- exact pre-handshake failure reasons;
- whether the same endpoint is retried internally before qualification;
- time to `buffer_ready` on a working channel;
- whether a failing channel exits earlier or finds an alternative peer without increasing any absolute timeout.
