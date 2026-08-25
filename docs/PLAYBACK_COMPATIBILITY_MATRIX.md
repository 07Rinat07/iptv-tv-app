# Playback compatibility matrix

Этот документ задаёт воспроизводимый evidence-контракт для Issue #211. Канонический machine-readable список целей находится в:

`core/player-vlc/src/test/resources/playback_compatibility_matrix.tsv`

Матрица не является заявлением о поддержке кодека или устройства сама по себе. Строка с `evidence_status=NOT_RUN` означает только запланированную проверку.

## Зачем нужен отдельный matrix contract

Playback compatibility зависит от сочетания delivery, redirect/header behavior, video/audio codec, profile/level, устройства, sample revision и фактически выбранного backend. Поэтому результат нельзя фиксировать фразами вроде «HEVC работает» без конкретного контекста.

Для каждой строки матрица сохраняет:

- стабильный `sample_id`, который входит в baseline registry contract и не переименовывается после появления evidence;
- protocol/container, redirect mode и требуемые IPTV request headers;
- video codec и `PROFILE@LEVEL`;
- один или несколько audio codecs;
- resolution/fps;
- тип источника: ordinary IPTV, VOD/archive или P2P loopback;
- device gate;
- evidence status;
- provenance запуска: `run_id`, `device_model`, `android_api`, `app_build`, `sample_revision`, `evidence_artifact`;
- фактический backend;
- hardware/software decoder mode, если это наблюдаемо;
- отдельный `startup_result`;
- first-frame/first-audio evidence;
- fallback reason;
- результат channel switch;
- отдельный `multi_audio_result` для обнаружения и переключения нескольких аудиодорожек.

JUnit contract-тест проверяет схему, immutable baseline IDs, обязательное покрытие codec/delivery/redirect/header целей, минимальную ARM64-матрицу, наличие profile+level и fail-closed evidence semantics.

## Начальный target set

Текущая матрица охватывает как минимум:

- H.264/AVC, H.265/HEVC, MPEG-2 Video, MPEG-4 Part 2, VP9 и AV1;
- AAC, HE-AAC, MP2, MP3, AC-3, E-AC-3 и Opus;
- отдельную multi-audio цель;
- MPEG-TS по HTTP и HTTPS;
- HLS;
- progressive MP4, MKV и WebM;
- точный HTTP→HTTPS redirect target: `protocol=HTTP`, `container=MPEG_TS`, `redirect_mode=HTTP_TO_HTTPS`;
- точный типовой IPTV header target: `protocol=HTTPS`, `container=MPEG_TS`, `request_headers=USER_AGENT+REFERER`;
- local loopback MPEG-TS от embedded P2P runtime.

Минимальный `ARM64_TV_BOX` gate сохраняет отдельные цели, которые вместе покрывают H.264, H.265, MPEG-2 Video и AAC, HE-AAC, AC-3, E-AC-3. AV1 помечен capability-gated: отсутствие decoder capability на конкретном устройстве не интерпретируется как общий regression приложения.

## Profile и level

`profile_level` всегда записывается как `PROFILE@LEVEL`, например `HIGH@L4.1`, `MAIN10@L4.1` или `MAIN@MAIN_LEVEL`. Profile-only значение запрещено contract-тестом, потому что decoder compatibility может различаться между уровнями одного профиля.

## Device gates

- `EMULATOR` — детерминированные проверки, которые не требуют hardware-specific claim.
- `ARM64_TV_BOX` — основной реальный Android TV / TV Box acceptance target.
- `ARMV7_OR_WEAK` — слабое или 32-bit устройство, когда доступно.
- `CAPABILITY_GATED` — запускать только при наличии заявленной device/backend capability.

Device gate — категория цели, а не доказательство поддержки всей категории. Каждая выполненная строка обязана содержать конкретную модель устройства и Android API.

## Evidence state

Разрешённые состояния:

- `NOT_RUN` — проверка ещё не выполнялась;
- `PASS` — соответствующий sample успешно прошёл acceptance;
- `FAIL` — запуск воспроизводимо не прошёл acceptance;
- `UNSUPPORTED_CAPABILITY` — запуск зафиксировал отсутствие требуемой capability на конкретном устройстве.

Для `NOT_RUN` все provenance/result fields обязаны оставаться `PENDING`, включая `startup_result`.

Для любого выполненного состояния (`PASS`, `FAIL`, `UNSUPPORTED_CAPABILITY`) обязательны конкретные provenance и result fields:

- `run_id` — namespaced reference вида `github-actions:123456` или `field:2026-08-25:device-a`;
- `device_model` — конкретная модель устройства, не placeholder;
- `android_api` — формат `API_<level>`, например `API_35`;
- `app_build` — точная сборка: `git:<7..40 hex>` или `version:<version>`;
- `sample_revision` — immutable digest `sha256:<64 hex>`;
- `evidence_artifact` — стабильная URI-ссылка на evidence, например `https://...`, `artifact://...` или `diagnostic://...`;
- `actual_backend`;
- `decoder_mode`;
- `startup_result`;
- `first_frame`;
- `first_audio`;
- `fallback_reason`;
- `channel_switch`;
- `multi_audio_result`.

Placeholder markers (`PENDING`, `UNKNOWN`, `NOT_APPLICABLE`, `NONE`, `N/A`, `NA`, `TBD`, `UNSPECIFIED`, `NULL`, `...`) запрещены не только как полное значение provenance, но и внутри структурированного payload. Например, `github-actions:UNKNOWN`, `version:UNKNOWN` и `https://...` не являются допустимым evidence.

`startup_result` фиксируется отдельно от first-frame/first-audio, чтобы различать ошибку запуска и более поздний decoder/rendering failure. Для общего `PASS` обязательны `startup_result=PASS`, `first_frame=PASS`, `first_audio=PASS` и `channel_switch=PASS`, а backend — `MEDIA3` или `LIBVLC`.

Для single-audio executed rows `multi_audio_result=NOT_APPLICABLE`. Для multi-audio строки общий `PASS` разрешён только при `multi_audio_result=PASS`; если обнаружение или переключение альтернативной дорожки не прошло, общий status не может оставаться `PASS`.

`evidence_artifact` должен указывать на воспроизводимый артефакт запуска: CI artifact/run, сохранённый diagnostic log/field report или другой стабильный reference, из которого можно проверить результат. `sample_revision` фиксирует конкретное содержимое fixture/sample, а `app_build` — протестированную сборку приложения.

## Backend и safety boundary

Media3 остаётся primary backend. LibVLC используется только согласно отдельному детерминированному fallback classifier. Эта матрица не изменяет fallback policy и не вводит новый Player runtime.

Строка `P2P_LOOPBACK` проверяет только уже существующий local HTTP MPEG-TS output как вход Player. Она не разрешает и не меняет Ace/P2P peer, DHT, request, timeout или buffer policy.

## Следующий шаг

После принятия matrix contract следующий bounded increment должен подключить реальные/легально доступные sample assets или воспроизводимые fixture endpoints и начать заполнять результаты без расширения runtime policy. Software decoder additions допускаются отдельными fresh-main PR только после измеряемого пробела в этой матрице, проверки ABI/APK-size и применимых лицензий.
