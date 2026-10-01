# Teleprompter for Even G2

Android-телесуфлёр для очков **Even Realities G2**. Подключается к очкам по
Bluetooth Low Energy, проходит аутентификацию и отправляет текст сценария на
дисплей очков по реальному кадрированному протоколу G2 (см. ниже).

- Package: `com.example.teleprompter`
- Язык: Kotlin, UI: Jetpack Compose (Material3)
- compileSdk / targetSdk: **35**, minSdk: **26**
- Тестовое устройство: Samsung Galaxy A52 (Android 13/14)

## Версии тулчейна (зафиксированы, не менять по отдельности)

| Компонент           | Версия   |
|---------------------|----------|
| Gradle              | 8.13     |
| Android Gradle Plugin | 8.13.2 |
| Kotlin              | 2.0.21   |
| Compose Compiler    | 2.0.21   |
| Compose BOM         | 2024.10.01 |
| JDK для сборки      | 17       |

## Структура проекта

```
settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.properties
app/
  build.gradle.kts
  proguard-rules.pro
  src/main/
    AndroidManifest.xml
    res/values/strings.xml
    res/values/themes.xml
    java/com/example/teleprompter/
      MainActivity.kt           # Compose UI: статус, ввод, слайдеры, Старт/Пауза/Стоп, экран разрешений
      TeleprompterViewModel.kt  # состояние сценария + прокрутка превью на телефоне
      BleService.kt             # foreground BLE-сервис: scan → connect (0x5401/0x5402) → auth(7) → ready
      EvenG2Protocol.kt         # реальный кадрированный протокол G2 (0xAA-кадры, CRC16, протобаф)
```

> **Про `BleConstants.kt`:** константы протокола вынесены отдельно — в
> `EvenG2Protocol.kt`. UUID и параметры подключения оставлены в `BleService.kt`.

## Как собрать

Проект рассчитан на открытие в **Android Studio** (Ladybug 2024.2.1 или новее —
там есть совместимый с AGP 8.7 тулчейн).

1. `File → Open…` и выберите папку проекта.
2. Android Studio по `gradle-wrapper.properties` сам скачает Gradle 8.9 и
   до-создаст файлы Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`)
   при первой синхронизации.
3. `Build → Make Project` или из терминала:
   ```
   ./gradlew assembleDebug        # macOS/Linux
   gradlew.bat assembleDebug      # Windows
   ```

> Если собираете из командной строки без Android Studio и у вас не оказалось
> Gradle Wrapper — один раз сгенерируйте его установленным Gradle:
> `gradle wrapper --gradle-version 8.9`. Это единственный двоичный артефакт
> (`gradle-wrapper.jar`), который не входит в текстовую поставку.

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

## Проверка по критериям приёмки

1. **Сборка без ошибок:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
   Все deprecated-вызовы BLE заключены в ветки `Build.VERSION.SDK_INT` с
   `@Suppress("DEPRECATION")` на функции; `getDefaultAdapter()` заменён на
   `BluetoothManager.adapter`.

2. **Запуск на Android 13/14 без краша:** установите APK, выдайте запрошенные
   разрешения (Bluetooth, Уведомления; на Android ≤11 — Геолокация).

3. **Подключение к реальным очкам:** включите очки G2, нажмите «Подключиться».
   В Logcat по тегу `BleService`:
   ```
   Сканируем 'Even G2…'
   Найдена левая дужка: 'Even G2_..._L_...' [CC:C4:F9:..]
   GATT подключён → запрашиваем MTU 512
   MTU=... → discoverServices
   Характеристики G2 найдены. Включаем нотификации (0x5402)
   Нотификации включены → аутентификация (7 пакетов)
   ✅ Even G2 готовы к работе!
   ```
   Статус в UI сменится на **«Подключено ✓»**.

4. **Отправка текста:** нажмите «Старт». В Logcat:
   ```
   → Сценарий: N пакетов (текст M симв.)
   ```
   Весь сценарий уходит на дужку: display-config → init → страницы → marker → sync.
   Текст появляется на дисплее очков; листание — тачбаром на дужке (manual mode).

5. **Авто-переподключение:** выключите и снова включите очки — сервис сам
   перезапустит scan→connect→auth без перезапуска приложения.

6. **Чистота:** в коде нет `TODO`/`FIXME`, пустых тел функций и заглушек.

## Реальный протокол G2 (почему предыдущие версии не показывали текст)

G2 — это НЕ простой G1-протокол. Главные отличия, из-за которых раньше текст не
появлялся, хотя соединение было:

1. **Другая характеристика.** Писать надо в `00002760-08c2-11e1-9073-0e8ac72e5401`
   (нотификации `…5402`), а не в Nordic UART `6e400002`. Мы писали не туда — поэтому
   очки ничего не показывали.
2. **Кадрированный протокол с CRC.** Каждый пакет —
   `[0xAA 0x21 seq len 01 01 svc_hi svc_lo] + protobuf-payload + CRC16/CCITT`.
3. **7-пакетная аутентификация** сразу после включения нотификаций — это и есть
   настоящий «handshake» (а не выдуманный `0xC8 0x21 0xF9 0x0A`).
4. **Показ текста — это последовательность команд** (display-config → init →
   страницы контента → marker → sync), payload'ы — протобаф, паузы ~100 мс.
   Текст разбивается на страницы по 25 симв./строка, 10 строк/страница.

Всё это портировано 1:1 из рабочего примера-телесуфлёра
**i-soxi/even-g2-protocol** (`examples/teleprompter/teleprompter.py`).

## Прочие решения

- Подключаемся к ОДНОЙ дужке (левой `_L_`), как в эталоне — этого достаточно для
  показа текста. Запись — write-without-response, heartbeat не нужен.
- Прокрутка на телефоне (слайдер «Скорость») — это превью для оператора; очки
  получают весь текст разом и листаются тачбаром.
- «Стоп» очищает экран, отправляя пустой сценарий.
