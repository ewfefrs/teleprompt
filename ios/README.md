# Whisprompt — iOS (Swift/SwiftUI)

iOS-порт телесуфлёра для Even Realities **G2**. Ядро протокола (`EvenG2Protocol.swift`),
лицензия (`LicenseManager.swift`, CryptoKit) и анти-реверс (`IntegrityGuard.swift`) —
перенесены 1:1 из Android. Подключение к очкам (CoreBluetooth) и экран телесуфлёра
добавляются по фазам.

> ⚠️ **Держи этот код ПРИВАТНЫМ.** Здесь лежит реверс-протокол G2 — та же причина, по которой
> мы НЕ публиковали исходники Android. Пуш в приватный репозиторий, не в публичный `whisprompt`.

## Что уже есть (Фаза 1)
- `Support/EvenG2Protocol.swift` — полный протокол (CRC, varint, авторизация, EvenHub, форматирование).
- `Support/LicenseManager.swift` — проверка device-bound ключа (тот же публичный ключ, что в Android; бот @Whispromptbot выдаёт совместимые ключи).
- `Support/PremiumManager.swift`, `Support/Prefs.swift`, `Support/IntegrityGuard.swift` — премиум, хранилище, защита.
- `UI/ContentView.swift` — стартовый экран: бренд, Device ID, активация ключа.
- `project.yml` — конфиг XcodeGen (генерирует `.xcodeproj`).

## Сборка без своего Mac — через GitHub Actions
CI на macOS-раннере компилирует проект и ловит все ошибки:
1. Создай **приватный** GitHub-репозиторий и запушь туда содержимое (минимум папку `ios/`
   и `.github/workflows/ios.yml`).
2. Открой вкладку **Actions** → workflow **iOS build** запустится сам (или **Run workflow**).
3. Лог сборки покажет ошибки компиляции — правим по нему до зелёного.

Локально на Mac (если появится):
```bash
brew install xcodegen
cd ios && xcodegen generate
open Whisprompt.xcodeproj   # Cmd+R
```

## Установка на iPhone с Windows (без Mac)
1. Забери собранный `.ipa` (добавим шаг архивации в CI, когда появится Apple-аккаунт) —
   либо собери на облачном маке.
2. Поставь на iPhone через **Sideloadly** (есть под Windows) своим Apple ID.
   - Бесплатный Apple ID: переподпись каждые 7 дней, лимит приложений.
   - Apple Developer ($99/год): подпись на год + возможность TestFlight.

> ⚠️ Симулятор iOS **не умеет Bluetooth** — реальное подключение к G2 проверяется только на
> физическом iPhone.

## Фазы дальше
- **Фаза 2:** `BLE/BleManager.swift` — CoreBluetooth: скан, GATT 0x5401/0x5402, авторизация, показ текста, тачбар-события.
- **Фаза 3:** экран телесуфлёра (превью «как на очках», авто-скролл WPM, ручной скролл, ширина/яркость/узкий режим), настройки, FAQ.
- **Фаза 4:** локализация (7 языков), режимы public/rental, лимиты бесплатной версии.
