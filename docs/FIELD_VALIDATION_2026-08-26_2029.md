# Field validation — 2026-08-26 20:09–20:26

Baseline build: `main@40ac556621cb4002a5457b41c909344f90c75e89` after PR #254.

## EPG

The source-format classifier produced decisive field evidence:

- the tested production EPG source is delivered as raw `GZIP`;
- the Player/Guide currently rejects it before XML parsing with `EPG source is not XMLTV: format=GZIP`;
- therefore the next EPG increment is bounded streaming gzip decode before the existing XMLTV parser;
- do not add global XML text rewriting or ampersand sanitizing based on the older parser error;
- keep a 128 MiB source/network envelope and a separate bounded decoded-XMLTV envelope;
- after gzip decode reaches the parser, repeat field validation before changing matching/alias policy.

## Torrent TV / embedded P2P

The field run contains both successful and failing embedded-engine paths.

Successful evidence shows the engine can reach a clean end-to-end path:

`discovered -> connected -> handshaked -> useful window -> producing -> TS -> loopback -> Media3 -> first frame/audio`.

The same run also reproduces weak swarm acquisition:

- tracker returns one candidate;
- that candidate can fail TCP connect;
- bounded DHT probes can still return zero additional peers;
- some channels therefore fail before handshake/producer stages even though other channels on the same device/network work.

Keep P2P work split by boundary. Do not hide discovery/connect failures by increasing global preparation timeouts.

## Player programme UX

The `Программа` action is now wired, but the current centered modal is not the final TV UX. When EPG is empty it obscures a large part of playback while providing only an empty-state message.

After EPG data correctness is restored, use a separate Player UX increment with these constraints:

- preserve the playing video as the primary surface;
- prefer a compact right-side/partial-height programme panel rather than a large centered modal;
- show current/next and a short upcoming list from the already-loaded Player EPG state;
- no second EPG network fetch from the Player panel;
- empty EPG should use a compact non-blocking state rather than a large schedule dialog;
- preserve deterministic D-pad focus and one-action close/back behaviour.

## Next order

1. `fix/epg-gzip-source-r1` — bounded raw-gzip XMLTV decode + tests.
2. TV Box EPG retest on the exact merged build; inspect parse/match results.
3. P2P P0 discovery/connect qualification increment using this field evidence.
4. P2P producer/Media3 boundary work only for sessions that have already crossed the corresponding earlier stages.
5. `feat/player-programme-panel-r2` after EPG contains real programme data.
