# Torrent TV channel availability UI

## Policy

Torrent TV / Ace Stream channel availability is dynamic and must never be used to remove a channel from the catalog. A channel that has no reachable peer now can become available on a later attempt.

The player therefore keeps the full catalog visible and reports the last observed P2P state only for channels the user actually tried. Untouched channels remain `P2P · не проверен`; the app does not mass-probe the catalog because that would generate excessive tracker/DHT traffic.

## User-facing states

- `P2P · не проверен` — channel has not been attempted in this process;
- `P2P · поиск пиров…` — bounded P2P preparation is in progress;
- `P2P · поток готов` — the local playback stream is prepared;
- `P2P · играет` — the internal player reported READY;
- peer counts are appended when they belong to the active prepared/playing engine state;
- `P2P · нет пиров` — the last bounded attempt ended with a peer/no-seed result;
- `P2P · ошибка` — the last P2P attempt ended for another reason.

A no-peer or error state is informational only. The channel remains selectable and can be retried at any time.

## Regression requirements

1. No `ChannelHealth.UNAVAILABLE` filter is applied to the player catalog.
2. The groups dialog has no hide-unavailable toggle.
3. Ordinary IPTV rows keep their EPG subtitle.
4. Torrent TV rows show the P2P availability subtitle.
5. No background scan is started merely by opening the 279-channel Torrent TV list.
