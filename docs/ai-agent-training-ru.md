# Обучение AI-помощника сканера (практика для `myscanerIPTV`)

Документ описывает, как улучшать локальный AI-помощник поиска в вашем приложении на основе реальных логов.

Важно: AI в этом проекте улучшает **формулировку запросов и ранжирование**, но не может обойти сетевые проблемы (`DNS`, `timeout`, блокировки провайдера).

## 1) Что именно обучаем

В текущей архитектуре AI-помощник находится в:
- `feature/scanner/src/main/java/com/iptv/tv/feature/scanner/LocalAiQueryAssistant.kt`

Он влияет на:
- подбор intent-ключей (`inferIntentKeywords`);
- генерацию вариантов запроса (`buildAiVariants`);
- построение плана шагов в:
  - `feature/scanner/src/main/java/com/iptv/tv/feature/scanner/ScannerViewModel.kt`

Поэтому “обучение” для MVP делаем не как тяжелый fine-tune модели, а как:
- data-driven обновление словарей/шаблонов;
- отбор успешных AI-вариантов из логов;
- регулярная проверка качества до/после.

## 2) Материалы (первичные источники)

### Query rewrite / retrieval
- Microsoft Azure AI Search: Query rewriting:
  - https://learn.microsoft.com/azure/search/semantic-how-to-query-rewrite
- OpenSearch: Hybrid search + RRF (идея для ранжирования результатов):
  - https://docs.opensearch.org/docs/latest/vector-search/ai-search/hybrid-search/index/
- DPR paper (Dense Passage Retrieval, базовая идея retrieval-ранжирования):
  - https://arxiv.org/abs/2004.04906

### Интеграция поиска и API
- Google Programmable Search Engine (JSON API):
  - https://developers.google.com/custom-search/v1/overview
- Google Programmable Search Engine (документация):
  - https://developers.google.com/custom-search/docs/overview

### On-device / локальные модели
- ONNX Runtime Mobile for Android:
  - https://onnxruntime.ai/docs/get-started/with-mobile.html
- TensorFlow Lite on-device training (если захотите реальное дообучение на устройстве):
  - https://www.tensorflow.org/lite/examples/on_device_training/overview

## 3) Рабочий цикл обучения в вашем проекте

## Шаг A. Собрать датасет из реальных логов

Добавлен скрипт:
- `tools/ai/build_scanner_training_dataset.py`

Он парсит экспорт логов `scanner_*` и строит:
- `training_dataset.jsonl` (одна попытка поиска = одна запись);
- `training_summary.md` (сводка: успешные запросы, типы ошибок).

Запуск:

```powershell
python tools/ai/build_scanner_training_dataset.py --input "D:\myscanerIPTV-logs-xxxx.txt"
```

## Шаг B. Разделить “ошибки сети” и “ошибки формулировки”

Обучаем AI только по случаям, где:
- `label=success` (нашел результаты),
- или `label=empty` при рабочей сети.

Не обучаем по:
- `dns`,
- `timeout`,
- `network_io`.

Сначала правим сеть/прокси/DNS, потом обучаем AI.

## Шаг C. Обновить словари и шаблоны AI

В `LocalAiQueryAssistant.kt`:
- расширяйте `markers` для интентов (RU/WORLD/SPORT/MOVIE и т.д.);
- добавляйте 3-5 лучших шаблонов из `training_summary.md`;
- удаляйте шаблоны, которые стабильно дают `empty`.

## Шаг D. Оценить качество до/после

Считайте KPI минимум на 50-100 запусков:
- `success_rate = attempts with found>0 / total attempts`;
- `median_time_to_first_result`;
- `avg_found_per_attempt`.

Только если KPI выросли, оставляйте изменения.

## 4) Что добавить следующим этапом (рекомендация)

1. Запоминание успешных запросов в DataStore/Room.
2. Rerank найденных кандидатов:
   - +баллы за совпадения по intent;
   - +баллы за проверенную доступность URL;
   - штраф за повторные невалидные источники.
3. Ограничение AI-плана:
   - первые 4-6 самых вероятных шагов;
   - не запускать 14 шагов подряд при явном `dns_unavailable`.

## 5) Почему это сработает

- Вы перестаете “угадывать” запросы и начинаете обучать AI по своим реальным попыткам.
- Сеть и AI разделены: сетевые аварии не портят качество AI.
- Любое улучшение можно измерить цифрами, а не субъективно.

