# Home / Player dashboard и playback compatibility — подтверждённый план

Этот документ фиксирует подтверждённую последовательность реализации после P2P-инкремента PR #209 и дополняет канонический `PROJECT_STATUS_AND_ROADMAP.md` конкретными UX/playback acceptance-критериями.

Связанные задачи:

- Issue #46 — Player UX redesign;
- Issue #159 — evidence-driven Torrent TV / Ace Live gate;
- Issue #210 — TV dashboard redesign for Home and Live Player shell;
- Issue #211 — playback compatibility matrix and multicodec hardening.

## Текущий integration point

PR #209 `fix(p2p): avoid repeated dead peer on direct retry` squash-merged в `main` как `82418095e034c00d1ec0b3737c3a3cc25b6c2cc8`.

Перед merge exact head `a8c74442f2d5fec254959338f56a5291406973ab` прошёл:

- Android CI #941;
- real Torrent TV playback smoke without external Ace Engine;
- полный `core:p2p` unit suite;
- lint / debug / AndroidTest compile;
- signed ARM TV APK packaging;
- Player Refactor Guard #109.

Issue #159 остаётся открытым для следующей проверки на реальном TV Box. До нового field evidence не расширять P2P peer/DHT/request/timeout/buffer policy.

## Подтверждённая очередность

1. Завершить и слить PR #209 — выполнено.
2. Работать только от свежего `main`.
3. Завершить механический Player shell split без изменения поведения.
4. Начать Issue #210: новый Home / Live Player dashboard layout.
5. Довести focus/navigation и channel rails/lists.
6. Реализовать dashboard ↔ fullscreen без перезапуска playback session.
7. Добавить EPG Now/Next, Favorites и UI polish.
8. Отдельными fresh-main PR выполнить Issue #211: Media3/LibVLC compatibility and multicodec hardening.
9. Провести реальную TV Box матрицу H.264/H.265/MPEG-2/AC-3/E-AC-3 и других распространённых IPTV комбинаций.

Каждый кодовый пункт выполняется отдельным небольшим PR от свежего `main`. Не создавать зависимые PR stacks.

## Issue #210 — Home / Live Player dashboard

### Визуальное направление

Целевой интерфейс — современный dark TV dashboard, а не растянутый мобильный экран:

- компактный left navigation rail;
- крупный центральный video/player pane;
- компактный channel/group selector справа;
- горизонтальный quick-channel rail снизу;
- хорошо заметный TV focus;
- ограниченное количество постоянного текста поверх видео;
- крупные click/focus targets;
- собственный визуальный стиль Rinat IPTV без копирования чужих branding/assets.

### Home

Главная страница должна сразу показывать полезный TV-контекст, когда данные существуют:

- последний/продолжаемый live channel;
- Favorites;
- Recent / History;
- live groups / catalog shortcuts;
- EPG Now/Next там, где сопоставление надёжно;
- явный быстрый вход в Live Player.

Home обязан сохранять canonical catalog provenance и корректно восстанавливать focus после возврата из Player.

### Dashboard Player

Embedded/dashboard режим Player должен иметь одну playback session и один playback runtime:

- центральное видео;
- channel/group selector;
- Now/Next и текущий channel context;
- нижний quick-channel rail;
- Favorite;
- playback options;
- доступ к fullscreen без пересоздания канала.

### Dashboard ↔ fullscreen contract

Переходы обязаны сохранять текущую session ownership:

- fullscreen control открывает true fullscreen;
- mouse/touchpad double-click по video pane может открывать fullscreen;
- обычный click может использоваться для show/hide controls;
- `Back` в fullscreen сначала закрывает активный overlay, затем возвращает dashboard;
- fullscreen toggle возвращает dashboard;
- выход из fullscreen не должен останавливать и заново запускать канал;
- selected channel, volume/mute, aspect/scale, EPG context и focus return destination сохраняются;
- для VOD/archive сохраняется позиция там, где это семантически корректно;
- Channel +/- продолжает работать в fullscreen;
- fullscreen controls auto-hide;
- покидание Player происходит только после возврата на dashboard level и следующего Back.

Запрещено создавать второй Media3/LibVLC instance или второй P2P runtime только ради fullscreen layout transition.

### Navigation acceptance

Обязательные пути:

- D-pad: left rail → video → right channel list → bottom rail → fullscreen → controls → Back → dashboard;
- mouse/touchpad: те же переходы кликами/scroll;
- без focus traps;
- focus всегда видим;
- wheel прокручивает соответствующий list/rail, а не случайно меняет channel/volume;
- после возврата из fullscreen/dashboard сохраняется логический focus target.

## Issue #211 — playback compatibility / multicodec hardening

Цель — максимально широкая измеряемая IPTV-совместимость. Нельзя обещать буквально любой codec на любом TV Box: аппаратные decoder capabilities, Android API level, DRM и лицензирование задают реальные границы.

### Backend strategy

- Media3 — primary backend;
- LibVLC — встроенный fallback для подтверждённых decoder/container/demux/backend failures;
- ordinary IPTV не должен требовать внешнее player application;
- fallback не должен терять selected channel, volume/mute, fullscreen/dashboard state и navigation context;
- причина backend fallback записывается в diagnostics;
- исключить бесконечные Media3 ↔ LibVLC loops.

### Compatibility matrix

Проверять распространённые комбинации, где они доступны и допустимы в сборке.

Video:

- H.264/AVC;
- H.265/HEVC;
- MPEG-2 Video;
- MPEG-4 Part 2;
- VP9;
- AV1 там, где device/backend поддерживает его.

Audio:

- AAC / HE-AAC;
- MP2 / MP3;
- AC-3;
- E-AC-3;
- Opus;
- multiple audio tracks.

Delivery / containers:

- MPEG-TS over HTTP/HTTPS;
- HLS;
- progressive MP4/MKV/WebM для VOD/archive;
- redirects и типовые IPTV HTTP headers;
- local loopback MPEG-TS от embedded P2P runtime.

### Error classification

Playback diagnostics должны отличать как минимум:

- network/connectivity failure;
- stream/source failure;
- unsupported/invalid container;
- demux/extractor failure;
- decoder initialization failure;
- unsupported codec/profile/level;
- backend runtime failure.

Fallback запускать только когда классификация допускает альтернативный backend, а не при любом сетевом сбое.

### Playback functions

В рамках compatibility hardening проверить:

- audio track selection;
- subtitles;
- aspect ratio / zoom presets;
- deinterlacing там, где backend поддерживает его;
- live-edge recovery;
- transient reconnect;
- rapid channel switching;
- сохранение dashboard/fullscreen context при backend fallback.

## Real-device acceptance

После завершения layout/Player и compatibility increments провести фиксированную матрицу на BlueStacks/emulator и реальных ARM TV Box.

Для каждого sample сохранять:

- protocol/container;
- video codec/profile/level;
- audio codec;
- resolution/fps;
- выбранный backend;
- hardware/software decoder, если это наблюдаемо;
- startup result;
- first frame / first audio;
- fallback reason;
- channel-switch result.

Минимальный реальный набор: H.264, H.265, MPEG-2 Video, AAC/HE-AAC, AC-3 и E-AC-3 на ARM64 TV Box; дополнительно ARMv7/weak-device profile по доступности.

## Merge gates

Player/UI increments:

- Player Refactor Guard;
- relevant Player unit/regression tests;
- full Android CI;
- debug + AndroidTest compile;
- signed ARM TV APK.

Playback compatibility increments:

- backend/fallback regression tests;
- relevant player/backend unit tests;
- full Android CI;
- real-device evidence для claims, зависящих от hardware decoder.

P2P policy не менять в Issue #210/#211. Любое новое Ace Live behavior изменение — только после отдельного Issue #159 field evidence.
