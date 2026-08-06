# Установка APK на Android TV / TV Box

GitHub Actions публикует два устанавливаемых debug APK без x86-библиотек и без unsigned release:

- `Rinat-IPTV-TV-arm64-v8a-debug.apk` — основной вариант для большинства современных 64-битных TV Box;
- `Rinat-IPTV-TV-armeabi-v7a-debug.apk` — вариант для старых 32-битных ARM-устройств.

## Как определить архитектуру

Через ADB:

```bash
adb shell getprop ro.product.cpu.abi
```

Результат `arm64-v8a` означает, что нужно устанавливать arm64 APK. Результат `armeabi-v7a` означает, что нужен 32-битный APK.

## Проверка контрольной суммы

Артефакт также содержит `SHA256SUMS.txt`:

```bash
sha256sum -c SHA256SUMS.txt
```

## Установка

```bash
adb install -r Rinat-IPTV-TV-arm64-v8a-debug.apk
```

Сборочные каталоги Gradle не публикуются. В APK-артефакт входят только два установочных файла и контрольные суммы.
