# Field log note — 2026-08-14

The TV-box diagnostics used for this increment showed both outcomes in the same process: one Torrent TV channel reached the player after bounded Ace Live preparation, while another ended with the explicit no-peer timeout. This is why catalog membership must not be coupled to the latest availability result.

The same log also showed a separate engine-cleanup issue: `startup_dht_expansion` could still be logged after a successful `player_ready`. That lifecycle issue is intentionally tracked separately from this UI/catalog increment so availability presentation does not change P2P discovery semantics.
