# План проекта

_Актуализирован: 25 августа 2026 после ручного полевого теста._

Этот файл — технический порядок разработки. Фактический baseline теста: [`FIELD_VALIDATION_2026-08-25.md`](FIELD_VALIDATION_2026-08-25.md).

## Цель

Стабильное Android TV / TV Box приложение, которое:

- уверенно импортирует и хранит большие IPTV/Torrent TV каталоги без OOM и UI freeze;
- имеет настоящую новую Home как root TV dashboard;
- имеет production TV-first Player, а не legacy-looking compatibility shell;
- воспроизводит обычный IPTV через Media3 с bounded LibVLC fallback;
- воспроизводит Torrent TV через собственный embedded P2P/Ace Live runtime без обязательного внешнего Ace Engine;
- управляется D-pad/Enter/Back/Channel keys, мышью и тачпадом;
- сохраняет избранное, EPG и provenance больших каталогов без тяжёлых full-table hot flows.

## Текущий release gate

После теста 25.08.2026 проект находится в recovery-фазе. До закрытия P0 нельзя считать новые функции приоритетом.

### P0-1 — memory/catalog stability — #229

Проблема: реальный OOM при Scanner/import и последующий OOM в Room/Favorites.

Обязательное исправление:
- убрать непрерывный `Flow<List<ChannelEntity>>` полного `channels`;
- reconciliation выполнять bounded pages по минимальной identity projection;
- полные Channel rows читать только для совпавших Favorites;
- не повышать heap как способ скрыть дефект;
- повторить large-catalog import на том же классе устройства.

### P0-2 — Home root integration — #230

Проблема: `HomeDashboard` вложен в legacy AppRoot shell.

Обязательное исправление:
- HOME исключить из legacy top bar/outer surface;
- новый Home должен быть первым устойчиво видимым экраном;
- startup navigation не должен давать краткий новый экран с последующим возвратом в legacy UI;
- сохранить доступ к Scanner/Playlists/Player/Settings/Exit.

### P0-3 — production new Player — #231

Проблема: `StablePlayerScreen` маршрутизирован, но видимый результат остаётся legacy-looking.

Обязательное исправление:
- fullscreen video — главный слой;
- controls/EPG/channel rail — новые TV-first overlays/panels;
- dashboard <-> fullscreen сохраняет одну playback session;
- не создавать второй P2P runtime;
- обычный IPTV и LibVLC fallback не ломать.

### P0-4 — embedded Torrent TV parity — #232

Проблема: same-device Televizo + Ace Stream Engine существенно успешнее на тех же каналах.

Диагностика должна классифицироваться по первой отсутствующей стадии:
1. discovery;
2. TCP connect;
3. BT/Ace Live handshake;
4. metadata/live-window;
5. request selection/sent;
6. chunk ingress/accept;
7. piece complete/authentication;
8. TS resync/output;
9. Media3/LibVLC boundary.

Запрещено лечить protocol gap увеличением общих timeout/buffer bounds без подтверждённой причины.

Цель — существенно повысить peer -> handshake -> producer conversion и приблизить startup/zap успешность к same-device benchmark, не превращая внешний Ace Engine в dependency.

### P1 — unintended playback churn — #233

Проверить burst `player_play_request` при перемещении по списку. Focus не должен запускать тяжёлый playback каждого промежуточного канала.

## Что пока не переписываем

- Media3 ordinary IPTV stack: реальные first-frame/ready подтверждены.
- LibVLC fallback: реальные успешные случаи есть.
- Scanner search semantics: сначала исправляется memory boundary, а не сам поиск.
- EPG/archive: не приоритет до recovery P0.
- Общая архитектура P2P: менять только конкретную подтверждённую первую отсутствующую стадию.

## Workflow

Для каждого блока:
1. свежий `main`;
2. одна тематическая fix/feature ветка;
3. минимальный diff;
4. unit/Room/build/CI regression gates;
5. squash merge только после зелёного exact-head;
6. удалить временную ветку;
7. ручной TV Box acceptance для P0;
8. следующий блок только после фактической проверки предыдущего.

## Порядок ближайших веток

1. `fix/p0-catalog-memory-field-recovery` — #229 + синхронизация recovery docs.
2. fresh-main fix для #230 Home.
3. fresh-main fix для #231 Player.
4. fresh-main protocol fix для #232 по полевым логам и public Ace/BitTorrent reference contract.
5. #233 только после проверки происхождения rapid request burst.

## Definition of Done для recovery P0

PR не считается завершённым только по CI.

Нужны:
- отсутствие регрессии unit/build;
- ручная сборка из merged `main`;
- повтор конкретного сценария, который был сломан;
- скриншот/видео результата;
- diagnostics без прежнего failure signature;
- для memory: отсутствие OOM/процесс death;
- для Home/Player: явно видимый новый UI;
- для Torrent TV: same-device A/B по фиксированным каналам.
