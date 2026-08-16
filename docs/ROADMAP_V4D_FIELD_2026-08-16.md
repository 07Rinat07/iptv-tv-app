# V4d field execution addendum — 16 августа 2026

Этот файл уточняет текущий блок `Startup/zap latency parity (V4d)` из основного ROADMAP по результатам второго TV Box прогона. Верхнеуровневый порядок проекта не меняется: V4d остаётся blocker перед broad acceptance.

## Подтверждённые приоритеты

1. Сохранить startup evidence в bounded diagnostics: volatile `windowUseful/producing` не должны вытеснять lifecycle/timeline события.
2. Ускорить первый альтернативный peer при weak tracker fast-path: первый startup DHT endpoint передаётся в TCP path сразу, без ожидания batch из четырёх endpoints.
3. Подключить canonical startup timeline к реальным runtime hook points: transport/discovery/connect/handshake/useful-window/first-media/buffer-ready/HTTP open/read.
4. Отдельно подключить Media3 READY/first-frame/load-retry evidence.
5. После нового полевого timeline проверить direct 8-second soft-window → metadata serialization; не менять soft-window до подтверждения.
6. Исправить pre-READY pressure authority: parser/read burst не должен считаться безусловным playback bitrate.
7. Если после раннего peer diversity остаётся один handshaked producer и около одной секунды headroom, ввести bounded fresh-candidate diversity по handshaked/useful evidence, а не по discovered count.
8. После закрытия startup/zap и steady-state starvation перейти к fixed A/B matrix, 20 rapid switches, weak network, peer loss и 2h/8h ARM soak.

## Не менять вслепую

- 60-second startup failure bound;
- 30-second no-connected-peer guard;
- request timeout и recovery `maxPieceAdvance`;
- TS discontinuity gate;
- generic IPTV/Media3 policy;
- output buffer capacity как самостоятельное «лечение» starvation.

Подробные измерения: [`ACE_LIVE_FIELD_VALIDATION_2026-08-16.md`](ACE_LIVE_FIELD_VALIDATION_2026-08-16.md).
