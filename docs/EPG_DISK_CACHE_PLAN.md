# Bounded EPG disk cache contract

## Goal

Persistent EPG cache должен уменьшать повторные network downloads и повторную работу после process restart, не увеличивая heap и не скрывая malformed/unsupported source.

Cache остаётся отдельным storage layer вокруг существующего streaming source pipeline:

`network/source bytes -> bounded source classifier/decoder -> bounded XMLTV parser -> matching -> parsed L1 state`

## Source representation

L2 snapshot хранит source bytes в сетевом представлении, которое уже прошло bounded transport write:

- plain XMLTV сохраняется как XMLTV source bytes;
- raw GZIP XMLTV сохраняется compressed, а decompression выполняется streaming при чтении;
- HTML/error/binary/unsupported source не публикуется как valid EPG snapshot.

Это сохраняет raw transport envelope и не заставляет disk layer материализовать потенциально более крупный decoded XMLTV body.

При чтении snapshot проходит тот же production pipeline, что network source: format classification, поддерживаемый decoder, повторная decoded-prefix classification, XMLTV parser и heap/program/channel guards.

## Bounds

Baseline storage contract:

- raw/source snapshot <= **128 MiB**;
- aggregate published cache <= **128 MiB**;
- entries <= **4**;
- decoded XMLTV stream <= **256 MiB**;
- deterministic LRU по `lastAccessAtMs`, fallback `loadedAtMs`;
- temporary write не считается published cache entry, но должен учитывать free-space reserve;
- low storage -> skip cache write, а не failure live EPG load.

Один крупный snapshot может занять почти весь aggregate budget и вытеснить старые entries. Ни один лимит не является резервированием объёма на каждый source.

Bounds меняются только по измеренному field/storage evidence.

## L1 and L2 ownership

### L1 parsed memory

- маленький bounded parsed cache;
- короткий hot TTL;
- no full-guide database materialization только ради cache;
- low-memory/OOM headroom policy может очистить L1 независимо от L2.

### L2 source snapshot

Narrow storage abstraction, например:

- `EpgSnapshotStore` — read/write/delete/evict;
- file-backed app-private implementation;
- metadata: loaded/access time, size, checksum, format version, conditional HTTP validators;
- cache key — secret-safe digest source identity, не raw URL/credentials.

## Read / refresh flow

1. fresh L1 -> return;
2. eligible L2 -> validate metadata/checksum -> production source/decode/parse pipeline -> L1;
3. expired/revalidation-required L2 -> conditional HTTP;
4. `304` -> refresh metadata and reuse validated snapshot;
5. `200` -> bounded temp write -> classify/validate -> atomic publish -> parse;
6. transient network failure -> stale snapshot только в пределах stale policy;
7. corrupt/malformed/permanent failure -> fail closed and drop/invalidate bad snapshot according to policy.

Concurrent callers одного source должны сходиться в один owner load/refresh, а не параллельно скачивать один feed.

## Freshness

Различать:

- L1 memory freshness;
- L2/network freshness;
- stale fallback lifetime.

Conditional HTTP использует `ETag`/`If-None-Match` и/или `Last-Modified`/`If-Modified-Since` при наличии. Hard stale lifetime должен оставаться bounded; baseline ceiling — **96h**.

Malformed/permanent source failure не превращается в бесконечный stale success.

## Atomic write and corruption safety

- temp file в том же app-private storage domain;
- streaming byte count во время write;
- checksum считается без второго full-body read;
- validate source format before publish;
- flush + best-effort fsync;
- atomic rename/publish;
- metadata публикуется после payload commit;
- старый valid snapshot сохраняется до успешной замены;
- orphan temp cleanup opportunistic;
- size/checksum/version mismatch -> bounded corrupt drop, не crash.

## GZIP safety

Raw gzip source использует два независимых envelopes:

- compressed/raw source bound — 128 MiB;
- decoded XMLTV bound — 256 MiB.

Cache read не может обходить raw accounting через `skip()` или другой uncounted path. GZIP corruption/truncation классифицируется как malformed source, а не как generic transient network failure.

После decode разрешён только XMLTV-looking payload; gzip HTML/other text/binary fail closed.

## Security / privacy

- app-private storage;
- filenames и metadata не содержат username/password/token/query secret;
- raw source URL не попадает в diagnostics;
- snapshot не экспортируется автоматически в diagnostics/backup;
- clear app data штатно удаляет cache;
- XML/source payload не логируется.

## Diagnostics

Bounded events могут различать:

- memory hit;
- disk hit;
- network `200`;
- network `304`;
- stale fallback;
- eviction;
- corrupt drop;
- write skipped due low storage.

Полезные поля: age, source/raw size, decoded size where observed, freshness, failure class и elapsed load/parse. Без credential-bearing source identity и body.

## Required tests

- fresh process + valid snapshot -> no network;
- process restart -> snapshot reusable;
- plain XMLTV snapshot read;
- raw gzip XMLTV snapshot read/decode;
- gzip non-XMLTV -> fail closed;
- corrupt/truncated gzip -> malformed failure;
- >128 MiB raw snapshot -> rejected without OOM;
- decoded expansion >256 MiB -> rejected without OOM;
- aggregate budget / entry-count deterministic LRU;
- expired + `304` -> no body replacement;
- expired + `200` -> atomic replacement;
- transient + allowed stale -> fallback;
- malformed/permanent -> no stale-success loop;
- checksum mismatch/truncation -> drop without crash;
- low storage -> skip write, live path survives;
- concurrent same-source callers -> one load;
- cache key/filenames do not expose secrets;
- disk parser path reuses production classifier/decoder/input/heap guards.

## Device acceptance

На exact integrated build:

1. загрузить реальный большой EPG source;
2. дождаться successful parse/matching;
3. перезапустить process;
4. повторно открыть тот же EPG внутри freshness interval;
5. подтвердить disk hit без полного повторного download;
6. проверить conditional `304` либо один bounded `200` после expiration;
7. проверить transient/offline stale behavior;
8. подтвердить aggregate cache <=128 MiB и отсутствие heap regression.

CI подтверждает storage/parser regressions, но реальный network/provider behavior требует field validation.