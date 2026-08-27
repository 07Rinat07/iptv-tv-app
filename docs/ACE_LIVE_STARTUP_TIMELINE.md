# Ace Live startup timeline

## Purpose

Startup timeline — observational contract для определения первой отсутствующей стадии Torrent TV playback. Она не управляет timeout, retry, scheduler, buffer или playback ownership.

## Canonical milestones

Основная последовательность:

`transport_selection -> direct/metadata attempt -> discovery_completed -> first_candidate -> connected -> handshake -> useful_window -> first_media -> buffer_ready -> http_reader_open -> http_first_delivery -> media3_load -> media3_ready -> first_audio/first_frame`

Milestone записывается только по фактическому событию. Отсутствующая стадия остаётся отсутствующей; synthetic timestamp запрещён.

`discovery_completed` и `first_candidate` различаются: завершённый пустой discovery round является timing evidence, но не peer evidence. Аналогично TCP `connected` не означает protocol `handshake`, а media ingress не означает accepted/producing output.

## First-write-wins semantics

Для одной playback preparation canonical milestone фиксирует первое валидное наступление события. Повторные discovery/refill rounds, reconnect, HTTP reopen или Media3 retry могут иметь собственные diagnostics, но не переписывают исходный startup timestamp.

Speculative direct/metadata branches одной подготовки должны коррелироваться с общей generation/session ownership, чтобы проигравшая branch не создавала второй независимый «успешный» timeline.

## Clock ownership

Core preparation timeline и Player elapsed-time telemetry могут иметь разные origins. Они должны связываться correlation identifiers, но не смешиваться в одну шкалу времени без явного преобразования.

Runtime policy clock, используемый timeout/buffer guards, не заменяется diagnostics clock.

## Player load boundary

Для P2P Media3 полезно различать:

- first load started;
- first successful load completion;
- load error;
- retry/recovery event;
- `BUFFERING` / `READY`;
- first audio progress;
- first rendered video frame;
- rebuffer count/duration.

Load telemetry должна быть bounded: обычные повторяющиеся chunk loads не должны вытеснять из diagnostics более важные lifecycle/startup события.

## Loopback boundary

Local HTTP telemetry различает:

- reader open;
- requested Range/start, если есть;
- фактический retained offset;
- first positive socket delivery;
- total delivered bytes;
- reader close/reopen reason;
- reader lifetime.

Сам факт `GET`/open не является media delivery. Аналогично большой delivered byte count не является first-frame доказательством: после loopback остаются TS/extractor/decoder/rendering stages.

## Failure interpretation

Примеры первой отсутствующей стадии:

- `first_candidate` отсутствует — discovery/bootstrap/routing boundary;
- candidate есть, `connected` отсутствует — TCP/connect boundary;
- connected есть, `handshake` отсутствует — protocol qualification boundary;
- handshake есть, `useful_window` отсутствует — live-window/requestability boundary;
- useful peer есть, `first_media` отсутствует — scheduler/request/peer production boundary;
- media есть, `buffer_ready` отсутствует — output/headroom boundary;
- buffer ready есть, HTTP delivery отсутствует — loopback ownership/server boundary;
- HTTP delivery есть, Media3 READY/tracks/frame отсутствуют — TS/Media3 boundary.

Изменение production behavior должно целиться в первую доказанно отсутствующую стадию, а не в последний UI symptom.

## Diagnostics safety

Timeline collection должна:

- переживать failure diagnostics sink без влияния на playback;
- не логировать raw content ids, tokens или credential-bearing URLs;
- сохранять bounded number of records;
- различать lifecycle и volatile quality samples;
- позволять сопоставить startup/runtime/generation/session там, где эти identifiers доступны.

## Invariants

- discovered endpoint не считается connected/handshaked/useful/producing;
- repeated callback не изменяет canonical first milestone;
- diagnostics не меняет absolute failure bounds;
- generic IPTV telemetry/policy не меняется ради P2P timeline;
- stale generation не может публиковать milestones как текущая session;
- field acceptance опирается на exact integrated build и полный stage trace, а не только на READY/error UI.