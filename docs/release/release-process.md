# Процесс релиза

## Автоматическая проверка

Ветка должна пройти основной Android CI: lint, unit-тесты, debug и release сборки.

## Подписанная сборка

Настройте переменные окружения:

```text
IPTV_RELEASE_STORE_FILE
IPTV_RELEASE_STORE_PASSWORD
IPTV_RELEASE_KEY_ALIAS
IPTV_RELEASE_KEY_PASSWORD
```

Затем выполните:

```bash
./gradlew --no-daemon :app:assembleRelease
```

Проверьте подпись и SHA-256. APK хранится в GitHub Release, а не в Git-репозитории.

## Release gate

- [ ] Android CI зелёный;
- [ ] тест на слабом и среднем TV Box;
- [ ] двухчасовой тест;
- [ ] восьмичасовой soak-тест;
- [ ] слабая сеть;
- [ ] подпись APK;
- [ ] заполненный отчёт приёмки.
