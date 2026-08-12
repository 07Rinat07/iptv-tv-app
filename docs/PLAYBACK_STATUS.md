# Статус воспроизведения

Актуальный срез: 12 августа 2026 года.

## Что подтверждено

- Обычные IPTV-потоки открываются через Media3; LibVLC используется только для ошибок контейнера, demux или кодека.
- PR #99 уже убрал полное пересоздание Media3/MediaCodec на каждом переключении обычного IPTV: при совместимых HTTP headers/buffer config Media3 stack переиспользуется между channel sessions.
- `magnet:`, BitTorrent infohash и `.torrent` обслуживаются встроенным libtorrent backend.
- Torrent TV `content_id` и live infohash обслуживаются встроенным Ace Live runtime без автоматического запуска внешнего Ace Engine.
- PR #98 уже находится в `main`: direct Ace Live swarm и metadata resolution выполняются first-success, а Android CI #425 перед merge прошёл real Torrent TV playback smoke без Ace Engine.
- Новые пользовательские журналы отдельно подтверждают последовательный успешный `resolve/start/ready` обычных каналов с `streamKind=IPTV поток (прямой URL)` непосредственно перед закрытием процесса. Это доказывает, что текущий crash не является только P2P/Ace-проблемой.

Эти результаты подтверждают работоспособность основных playback routes, но stable release пока блокируется memory safety и последующим playback hardening.

## Текущий главный блокер: EPG OOM на обычном IPTV

Пользовательские журналы от 12 августа фиксируют `OutOfMemoryError` в `OkHttp TaskRunner` на устройстве/окружении с heap около 256 MiB. В одном воспроизведении непосредственно перед crash обычный IPTV-канал был успешно запущен, а heap достиг примерно 252/256 MiB. В другом прогоне после серии прямых IPTV channel switches приложение снова завершилось из-за OOM/fragmentation.

До PR #100 один и тот же malformed XMLTV (`XmlPullParserException: unterminated entity ref`) мог повторно скачиваться и разбираться при последовательном переключении каналов, потому что failed EPG не имел negative-cache.

До PR #100 production XMLTV path загружал полный ответ через `ResponseBody.bytes()`, одновременно удерживал крупный `ByteArray` и строил EPG maps/lists, а затем создавал дополнительные нормализованные collections. В текущем head PR #100 этот path заменён на bounded streaming parse из `ResponseBody.byteStream()`, добавлены retained-data/cache limits, serialized heavy load, negative-cache/backoff и low-memory guard. Полноценная приёмка всё ещё требует зелёного exact-head CI и ручного rapid-zap smoke на 256-MiB/аналогичном устройстве.

Подробный разбор и точный scope исправления находятся в [`testing/playback-log-analysis-2026-08-12.md`](testing/playback-log-analysis-2026-08-12.md).

## Известные проблемы после фикса текущего crash

После устранения EPG OOM остаются отдельные playback-hardening задачи:

- часть Ace Live `content_id` всё ещё может исчерпывать полный startup budget, если direct swarm не стартует, а transport metadata недоступна;
- часть P2P-потоков воспроизводится с торможениями или зависает после старта;
- отдельные swarm закрывают соединения, имеют пустой/устаревший live-window либо перестают публиковать новые pieces;
- MPEG-TS output пока не имеет полного PAT/PMT/random-access/IDR gating и decoder/discontinuity hardening;
- после memory fix нужно повторно измерить rapid-zap latency обычного IPTV и при необходимости добавить узкое coalescing/debounce для промежуточных channel requests;
- не завершены длительные тесты, слабая сеть и матрица реальных ARM TV Box.

Поэтому текущая сборка остаётся тестовой. Критерий «любой выбранный рабочий IPTV/Torrent TV канал стабильно включается, воспроизводится со звуком, переключается и корректно останавливается» ещё не выполнен.

## Текущий этап: memory safety → playback hardening

Кодовая часть EPG OOM hotfix в PR #100 реализована; остаются CI и runtime acceptance:

1. ✅ production XMLTV path больше не использует `ResponseBody.bytes()` и разбирает XMLTV непосредственно из bounded `byteStream()`;
2. ✅ ограничен максимальный объём читаемого XMLTV и retained EPG data;
3. ✅ исключена вторая полная копия program/display-name collections после parsing;
4. ✅ тяжёлые EPG load/parse операции сериализованы, чтобы rapid-zap не создавал несколько параллельных XMLTV загрузок;
5. ✅ добавлен bounded negative-cache/backoff для malformed/HTTP/parser failures;
6. ✅ перед тяжёлой EPG network/parse операцией и во время parsing проверяется heap headroom; low-memory путь возвращает controlled `EPG unavailable/deferred`;
7. ✅ positive EPG RAM cache ограничен и инвалидируется при смене EPG URL;
8. ✅ per-channel EPG load отложен и отменяется при новом channel request, чтобы промежуточные rapid-zap каналы не запускали тяжёлую guide работу;
9. ⏳ получить полностью зелёный Android CI на exact PR head;
10. ⏳ вручную переключить 20–30 обычных IPTV-каналов на 256-MiB/аналогичном устройстве и убедиться, что malformed/oversized EPG максимум отключает телепрограмму, но не playback process.

После успешного memory hotfix продолжается обычный playback/P2P hardening:

1. измерить остаточное время старта и zapping обычного IPTV;
2. обеспечить непрерывную подкачку Ace Live с достаточным запасом данных и bounded recovery;
3. не возвращать serial startup wait, устранённый PR #98;
4. разделить в диагностике unavailable source, dead swarm, insufficient buffer, stall, decoder/demux error и user cancellation;
5. выполнить MPEG-TS/decoder hardening отдельным PR после стабилизации memory path;
6. прогнать одинаковую матрицу обычных IPTV и Torrent TV каналов на эмуляторе, слабом и среднем ARM TV Box;
7. после короткой матрицы выполнить двухчасовой и восьмичасовой soak-тесты.

## Критерий завершения

Playback hardening можно считать завершённым только когда:

- рабочие контрольные IPTV и Torrent TV источники стартуют повторяемо без внешнего Ace Engine;
- переключение укладывается в зафиксированный бюджет времени либо быстро возвращает понятную ошибку;
- malformed, oversized или временно недоступный EPG не может вызвать crash/ANR и не блокирует воспроизведение канала;
- rapid-zap обычного IPTV не создаёт неконтролируемого роста heap и не закрывает процесс;
- live-буфер непрерывно пополняется и удерживает измеримый запас без постоянной повторной буферизации;
- звук слышен и корректно выбирается на всех контрольных каналах;
- переключение и остановка не оставляют старую сессию или локальный HTTP stream;
- временная потеря peers или сети приводит к ограниченному восстановлению либо к понятной ошибке, но не к бесконечному ожиданию;
- на поддерживаемых устройствах нет crash/ANR, неконтролируемого роста памяти и постоянной повторной буферизации;
- заполнен отчёт приёмки, а CI и релизные проверки зелёные.
