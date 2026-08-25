# Playback compatibility matrix

Этот документ задаёт воспроизводимый evidence-контракт для Issue #211. Канонический machine-readable список целей находится в:

`core/player-vlc/src/test/resources/playback_compatibility_matrix.tsv`

Матрица не является заявлением о поддержке кодека или устройства сама по себе. Пока строка имеет `evidence_status=NOT_RUN`, она означает только запланированную проверку.

## Зачем нужен отдельный matrix contract

Playback compatibility зависит от сочетания protocol/container, video/audio codec, профиля, устройства и фактически выбранного backend. Поэтому результат нельзя фиксировать фразами вроде «HEVC работает» без контекста.

Для каждой строки матрица сохраняет:

- стабильный `sample_id`;
- protocol и container/delivery;
- video codec и profile/level;
- один или несколько audio codecs;
- resolution/fps;
- тип источника: ordinary IPTV, VOD/archive или P2P loopback;
- device gate;
- фактический backend;
- hardware/software decoder mode, если это наблюдаемо;
- startup/first-frame/first-audio evidence;
- fallback reason;
- результат channel switch.

JUnit contract-тест проверяет схему, уникальность sample ID, обязательное покрытие целевых codec/delivery категорий Issue #211 и запрещает незапущенным строкам выглядеть как подтверждённые device results.

## Начальный target set

Текущая матрица охватывает как минимум:

- H.264/AVC, H.265/HEVC, MPEG-2 Video, MPEG-4 Part 2, VP9 и AV1;
- AAC, HE-AAC, MP2, MP3, AC-3, E-AC-3 и Opus;
- отдельную multi-audio цель;
- MPEG-TS по HTTP и HTTPS;
- HLS;
- progressive MP4, MKV и WebM;
- local loopback MPEG-TS от embedded P2P runtime.

AV1 помечен capability-gated: отсутствие decoder capability на конкретном устройстве не должно интерпретироваться как общий regression приложения.

## Device gates

- `EMULATOR` — детерминированные проверки, которые не требуют hardware-specific claim.
- `ARM64_TV_BOX` — основной реальный Android TV / TV Box acceptance target.
- `ARMV7_OR_WEAK` — слабое или 32-bit устройство, когда доступно.
- `CAPABILITY_GATED` — запускать только при наличии заявленной device/backend capability.

## Evidence state

Начальное состояние всех строк:

- `evidence_status=NOT_RUN`;
- `actual_backend=PENDING`;
- `decoder_mode=PENDING`;
- `first_frame=PENDING`;
- `first_audio=PENDING`;
- `fallback_reason=PENDING`;
- `channel_switch=PENDING`.

Менять эти поля на фактический результат можно только после воспроизводимого запуска соответствующего sample на указанном device gate. Для hardware-dependent утверждений нужен реальный TV Box evidence.

## Backend и safety boundary

Media3 остаётся primary backend. LibVLC используется только согласно отдельному детерминированному fallback classifier. Эта матрица не изменяет fallback policy и не вводит новый Player runtime.

Строка `P2P_LOOPBACK` проверяет только уже существующий local HTTP MPEG-TS output как вход Player. Она не разрешает и не меняет Ace/P2P peer, DHT, request, timeout или buffer policy.

## Следующий шаг

После принятия matrix contract следующий bounded increment должен подключить реальные/легально доступные sample assets или воспроизводимые fixture endpoints и начать заполнять результаты без расширения runtime policy. Реальные codec/device claims должны появляться только вместе с записанным evidence.
