# Наручный фото-ассистент для очков G2 — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** По кнопке на наручном ESP32-CAM снять фото, передать его по WiFi в Android-приложение Whisprompt, объяснить его через DeepSeek и вывести короткий текст в очки G2.

**Architecture:** Два независимых подсистемы, связанные одним HTTP-контрактом. (1) **Прошивка ESP32-CAM** держит WiFi к точке доступа телефона и по нажатию кнопки делает `POST /photo` с JPEG. (2) **Android-приложение** поднимает локальный HTTP-сервер, принимает JPEG, шлёт его в DeepSeek (OpenAI-совместимый API), а ответ отдаёт в существующий движок вывода текста в очки (`BleService.startPresentation`).

**Tech Stack:** ESP32-CAM (Arduino/`esp_camera.h`, C++); Android Kotlin + Coroutines + Compose; встроенные `HttpURLConnection`, `org.json`, `java.util.Base64` (без новых runtime-зависимостей); DeepSeek `deepseek-v4-flash-vision-exp`.

## Global Constraints

- **Android package:** `com.example.teleprompter` (applicationId `com.whisprompt.app`).
- **Toolchain:** Kotlin 2.0.21, JDK 17, `minSdk 26`, `compileSdk 35`.
- **Никаких новых runtime-зависимостей.** Только `HttpURLConnection`, `org.json` (в рантайме Android есть), `java.util.Base64` (доступен с API 26). Новые библиотеки допускаются ТОЛЬКО как `testImplementation`.
- **HTTP-контракт ESP32 → телефон:** `POST /photo HTTP/1.0`, заголовок `Content-Type: image/jpeg`, `Content-Length: N`, тело — сырые байты JPEG. Ответ телефона: `HTTP/1.0 200 OK\r\n\r\n`.
- **Порт приёмника фото по умолчанию:** `8080` (и в прошивке, и в приложении).
- **IP телефона (шлюз точки доступа Android) по умолчанию:** `192.168.43.1` (настраивается в прошивке одним `#define`).
- **DeepSeek:** endpoint `https://api.deepseek.com/chat/completions`, модель `deepseek-v4-flash-vision-exp`, изображение — как `data:image/jpeg;base64,...` в блоке `image_url`, заголовок `Authorization: Bearer <key>`.
- **Вывод в очки:** только через `BleService.startPresentation(text, ScrollMode.MANUAL, speed, big)`. Предусловие — очки уже подключены (state READY). Ассистент НЕ пропускает ответ через `ContentPolicy` (это личный вывод пользователя, не публикуемый контент).
- **Сборка/тесты Android** (из memory `build-procedure`, gradlew отсутствует):
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  $g = "C:\Users\bronl\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
  & $g :app:testDebugUnitTest --console=plain     # юнит-тесты
  & $g compileDebugKotlin --console=plain          # проверка компиляции
  & $g assembleDebug --console=plain               # сборка APK
  ```
  Установка APK: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
- **Прошивка** собирается в Arduino IDE (пакет плат `esp32`, плата «AI Thinker ESP32-CAM»). Юнит-тестов у прошивки нет — верификация ручная (Serial Monitor + mock-приёмник).

## File Structure

**Phase A — прошивка (новая, автономная):**
- Create `firmware/palm_cam/config.h` — все настройки (`#define`): WiFi SSID/пароль, IP/порт телефона, пин кнопки, пины камеры AI-Thinker.
- Create `firmware/palm_cam/palm_cam.ino` — основной скетч: WiFi, камера, кнопка, HTTP POST.

**Phase B — Android (в существующем модуле `app`):**
- Create `app/src/main/java/com/example/teleprompter/assistant/HttpPhotoParser.kt` — чистый парсер HTTP-POST → байты JPEG.
- Create `app/src/main/java/com/example/teleprompter/assistant/PhotoReceiverServer.kt` — `ServerSocket`-цикл на корутине.
- Create `app/src/main/java/com/example/teleprompter/assistant/DeepSeekClient.kt` — сборка запроса, парсинг ответа, сетевой вызов.
- Create `app/src/main/java/com/example/teleprompter/assistant/AssistantController.kt` — склейка: фото → DeepSeek → очки.
- Modify `app/src/main/java/com/example/teleprompter/Prefs.kt` — новые ключи настроек.
- Modify `app/src/main/java/com/example/teleprompter/SettingsScreen.kt` — поля «Ключ DeepSeek» и «Инструкция».
- Modify `app/src/main/java/com/example/teleprompter/MainActivity.kt` — старт/стоп контроллера, показ ответа в очки.
- Modify `app/build.gradle.kts` — `testImplementation` для юнит-тестов.
- Create тесты в `app/src/test/java/com/example/teleprompter/assistant/`.

---

## Phase A — Прошивка ESP32-CAM

### Task A1: Скелет скетча + WiFi

**Files:**
- Create: `firmware/palm_cam/config.h`
- Create: `firmware/palm_cam/palm_cam.ino`

**Interfaces:**
- Produces: `config.h` с макросами `WIFI_SSID`, `WIFI_PASS`, `PHONE_IP`, `PHONE_PORT`, `BUTTON_PIN` и пинами камеры AI-Thinker; `palm_cam.ino` с функцией `ensureWifi()`.

- [ ] **Step 1: Создать `config.h`**

```c
#pragma once

// --- Сеть (точка доступа телефона) ---
#define WIFI_SSID   "PHONE_HOTSPOT_SSID"   // заменить на имя своей точки доступа
#define WIFI_PASS   "PHONE_HOTSPOT_PASS"   // заменить на пароль точки доступа
#define PHONE_IP    "192.168.43.1"          // шлюз hotspot Android (обычно этот)
#define PHONE_PORT  8080

// --- Кнопка спуска ---
#define BUTTON_PIN  13                      // тактовая кнопка на GND, INPUT_PULLUP

// --- Пины камеры OV2640 для платы AI Thinker ESP32-CAM ---
#define PWDN_GPIO_NUM   32
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM    0
#define SIOD_GPIO_NUM   26
#define SIOC_GPIO_NUM   27
#define Y9_GPIO_NUM     35
#define Y8_GPIO_NUM     34
#define Y7_GPIO_NUM     39
#define Y6_GPIO_NUM     36
#define Y5_GPIO_NUM     21
#define Y4_GPIO_NUM     19
#define Y3_GPIO_NUM     18
#define Y2_GPIO_NUM      5
#define VSYNC_GPIO_NUM  25
#define HREF_GPIO_NUM   23
#define PCLK_GPIO_NUM   22
```

- [ ] **Step 2: Создать `palm_cam.ino` с WiFi**

```cpp
#include <WiFi.h>
#include "config.h"

void ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) return;
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("WiFi connecting");
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) {
    delay(250);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("WiFi OK, IP=");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("WiFi FAILED");
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);
  ensureWifi();
}

void loop() {
  ensureWifi();     // авто-переподключение, если точка доступа пропала
  delay(1000);
}
```

- [ ] **Step 3: Собрать и прошить (ручная проверка)**

В Arduino IDE выбрать плату «AI Thinker ESP32-CAM», порт, `Upload`. Открыть Serial Monitor на 115200.
Ожидаемо: строка `WiFi OK, IP=192.168.43.xxx` (при включённой точке доступа телефона).

- [ ] **Step 4: Commit**

```bash
git add firmware/palm_cam/config.h firmware/palm_cam/palm_cam.ino
git commit -m "feat(fw): ESP32-CAM scaffold with WiFi connect"
```

---

### Task A2: Инициализация камеры и захват кадра

**Files:**
- Modify: `firmware/palm_cam/palm_cam.ino`

**Interfaces:**
- Consumes: `ensureWifi()`, пины камеры из `config.h`.
- Produces: `bool initCamera()`, `camera_fb_t* captureJpeg()` (кадр вернуть через `esp_camera_fb_return`).

- [ ] **Step 1: Добавить инициализацию камеры**

В начало файла добавить `#include "esp_camera.h"`. Добавить функцию:

```cpp
bool initCamera() {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer   = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM;  c.pin_d1 = Y3_GPIO_NUM;
  c.pin_d2 = Y4_GPIO_NUM;  c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM;  c.pin_d5 = Y7_GPIO_NUM;
  c.pin_d6 = Y8_GPIO_NUM;  c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM;   c.pin_pclk = PCLK_GPIO_NUM;
  c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM;   c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  // DeepSeek всё равно ужимает до ~800x800 — SVGA (800x600) достаточно и экономит трафик/память.
  c.frame_size = psramFound() ? FRAMESIZE_SVGA : FRAMESIZE_VGA;
  c.jpeg_quality = 12;          // 10..15: меньше число = лучше качество
  c.fb_count = psramFound() ? 2 : 1;
  c.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  esp_err_t err = esp_camera_init(&c);
  if (err != ESP_OK) { Serial.printf("camera init failed: 0x%x\n", err); return false; }
  return true;
}

camera_fb_t* captureJpeg() {
  return esp_camera_fb_get();   // вызывающий ОБЯЗАН вызвать esp_camera_fb_return()
}
```

- [ ] **Step 2: Вызвать в `setup()` и снять один тестовый кадр**

В `setup()` после `ensureWifi()` добавить:

```cpp
  if (!initCamera()) { Serial.println("NO CAMERA"); return; }
  camera_fb_t* fb = captureJpeg();
  if (fb) { Serial.printf("captured %u bytes\n", fb->len); esp_camera_fb_return(fb); }
  else    { Serial.println("capture FAILED"); }
```

- [ ] **Step 3: Прошить и проверить (ручная проверка)**

Ожидаемо в Serial Monitor: `captured NNNNN bytes` (обычно 10 000–40 000 байт).
Если `camera init failed` — проверить питание (см. §3 спеки: boost до 5В) и посадку шлейфа.

- [ ] **Step 4: Commit**

```bash
git add firmware/palm_cam/palm_cam.ino
git commit -m "feat(fw): OV2640 init and single JPEG capture"
```

---

### Task A3: Кнопка + HTTP POST фото на телефон

**Files:**
- Modify: `firmware/palm_cam/palm_cam.ino`

**Interfaces:**
- Consumes: `ensureWifi()`, `captureJpeg()`, `PHONE_IP`, `PHONE_PORT`, `BUTTON_PIN`.
- Produces: `bool postPhoto(camera_fb_t* fb)`, обработка нажатия в `loop()`.

- [ ] **Step 1: Реализовать POST**

Добавить `#include <WiFiClient.h>`. Функция:

```cpp
bool postPhoto(camera_fb_t* fb) {
  WiFiClient client;
  if (!client.connect(PHONE_IP, PHONE_PORT)) { Serial.println("connect failed"); return false; }
  client.printf("POST /photo HTTP/1.0\r\n");
  client.printf("Host: %s\r\n", PHONE_IP);
  client.printf("Content-Type: image/jpeg\r\n");
  client.printf("Content-Length: %u\r\n\r\n", fb->len);
  size_t sent = client.write(fb->buf, fb->len);
  client.flush();
  // прочитать статусную строку, чтобы дождаться ответа
  unsigned long t0 = millis();
  while (client.connected() && !client.available() && millis() - t0 < 5000) delay(10);
  String status = client.readStringUntil('\n');
  client.stop();
  Serial.printf("sent %u/%u bytes, resp: %s\n", (unsigned)sent, fb->len, status.c_str());
  return sent == fb->len;
}
```

- [ ] **Step 2: Кнопка с антидребезгом в `loop()`**

В `setup()`: `pinMode(BUTTON_PIN, INPUT_PULLUP);` (убрать тестовый захват из A2).
Заменить `loop()`:

```cpp
void loop() {
  ensureWifi();
  static int last = HIGH;
  int cur = digitalRead(BUTTON_PIN);
  if (last == HIGH && cur == LOW) {          // фронт нажатия
    delay(30);                                // антидребезг
    if (digitalRead(BUTTON_PIN) == LOW) {
      camera_fb_t* fb = captureJpeg();
      if (fb) {
        if (!postPhoto(fb)) { delay(200); postPhoto(fb); }  // одна повторная попытка
        esp_camera_fb_return(fb);
      }
      while (digitalRead(BUTTON_PIN) == LOW) delay(10);      // ждать отпускания
    }
  }
  last = cur;
  delay(10);
}
```

- [ ] **Step 3: Проверка с mock-приёмником (ручная)**

На ПК в той же сети (или временно указать в `config.h` IP ПК) запустить приёмник и сохранить кадр:

```bash
python -c "import http.server,sys;
class H(http.server.BaseHTTPRequestHandler):
 def do_POST(s):
  n=int(s.headers['Content-Length']); d=s.rfile.read(n); open('shot.jpg','wb').write(d);
  s.send_response(200); s.end_headers(); print('got',n,'bytes')
http.server.HTTPServer(('0.0.0.0',8080),H).serve_forever()"
```

Нажать кнопку → в консоли `got NNNNN bytes`, файл `shot.jpg` открывается как фото. В Serial Monitor: `resp: HTTP/1.0 200 OK`.

- [ ] **Step 4: Commit**

```bash
git add firmware/palm_cam/palm_cam.ino
git commit -m "feat(fw): button-triggered JPEG POST to phone"
```

---

## Phase B — Android-приложение

### Task B1: Парсер HTTP-POST → байты JPEG

**Files:**
- Create: `app/src/main/java/com/example/teleprompter/assistant/HttpPhotoParser.kt`
- Modify: `app/build.gradle.kts` (добавить `testImplementation` — нужно всем задачам фазы B)
- Test: `app/src/test/java/com/example/teleprompter/assistant/HttpPhotoParserTest.kt`

**Interfaces:**
- Produces: `object HttpPhotoParser { fun parsePhotoPost(input: java.io.InputStream): ByteArray? }` — возвращает тело JPEG, если это `POST /photo`, иначе `null`.

- [ ] **Step 1: Добавить тестовые зависимости в `app/build.gradle.kts`**

В блок `dependencies { ... }` добавить (только для тестов, не в рантайм):

```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

- [ ] **Step 2: Написать падающий тест**

```kotlin
package com.example.teleprompter.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpPhotoParserTest {
    private fun request(method: String, path: String, body: ByteArray): ByteArray {
        val head = "$method $path HTTP/1.0\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    @Test fun `parses POST photo body`() {
        val body = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val bytes = request("POST", "/photo", body)
        val out = HttpPhotoParser.parsePhotoPost(ByteArrayInputStream(bytes))
        assertArrayEquals(body, out)
    }

    @Test fun `ignores GET`() {
        val out = HttpPhotoParser.parsePhotoPost(ByteArrayInputStream(request("GET", "/photo", ByteArray(0))))
        assertNull(out)
    }
}
```

- [ ] **Step 3: Запустить — убедиться, что падает**

Run: `& $g :app:testDebugUnitTest --tests "*HttpPhotoParserTest*" --console=plain`
Expected: FAIL — `HttpPhotoParser` не существует / unresolved reference.

- [ ] **Step 4: Реализовать парсер**

```kotlin
package com.example.teleprompter.assistant

import java.io.InputStream

object HttpPhotoParser {
    /** Читает один HTTP-запрос. Если это POST /photo — возвращает тело (JPEG), иначе null. */
    fun parsePhotoPost(input: InputStream): ByteArray? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.trim().split(" ")
        if (parts.size < 2 || parts[0].uppercase() != "POST" || parts[1] != "/photo") {
            return null
        }
        var contentLength = -1
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break                       // пустая строка = конец заголовков
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength <= 0) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    /** Читает одну строку до CRLF/LF как ISO-8859-1 (заголовки не UTF). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }
}
```

- [ ] **Step 5: Запустить — убедиться, что проходит**

Run: `& $g :app:testDebugUnitTest --tests "*HttpPhotoParserTest*" --console=plain`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/example/teleprompter/assistant/HttpPhotoParser.kt app/src/test/java/com/example/teleprompter/assistant/HttpPhotoParserTest.kt
git commit -m "feat(app): HTTP POST /photo parser with tests"
```

---

### Task B2: Локальный сервер приёма фото

**Files:**
- Create: `app/src/main/java/com/example/teleprompter/assistant/PhotoReceiverServer.kt`
- Test: `app/src/test/java/com/example/teleprompter/assistant/PhotoReceiverServerTest.kt`

**Interfaces:**
- Consumes: `HttpPhotoParser.parsePhotoPost`.
- Produces: `class PhotoReceiverServer(port: Int, onPhoto: (ByteArray) -> Unit) { fun start(scope: CoroutineScope); fun stop() }`.

- [ ] **Step 1: Написать падающий интеграционный тест (реальные сокеты localhost)**

```kotlin
package com.example.teleprompter.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhotoReceiverServerTest {
    @Test fun `receives posted jpeg`() {
        val body = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 42, 0xFF.toByte(), 0xD9.toByte())
        val latch = CountDownLatch(1)
        var got: ByteArray? = null
        val port = 18080
        val server = PhotoReceiverServer(port) { got = it; latch.countDown() }
        server.start(CoroutineScope(Dispatchers.IO))
        Thread.sleep(300)                                   // дать серверу подняться
        Socket("127.0.0.1", port).use { s ->
            val head = "POST /photo HTTP/1.0\r\nContent-Length: ${body.size}\r\n\r\n"
            s.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1) + body)
            s.getOutputStream().flush()
            s.getInputStream().read()                        // дождаться ответа
        }
        latch.await(3, TimeUnit.SECONDS)
        server.stop()
        assertArrayEquals(body, got)
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `& $g :app:testDebugUnitTest --tests "*PhotoReceiverServerTest*" --console=plain`
Expected: FAIL — `PhotoReceiverServer` не существует.

- [ ] **Step 3: Реализовать сервер**

```kotlin
package com.example.teleprompter.assistant

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket

class PhotoReceiverServer(
    private val port: Int,
    private val onPhoto: (ByteArray) -> Unit,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(port).also { socket = it }
                while (isActive) {
                    val client = ss.accept()
                    try {
                        val body = HttpPhotoParser.parsePhotoPost(client.getInputStream())
                        client.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\n".toByteArray())
                        client.getOutputStream().flush()
                        if (body != null) onPhoto(body)
                    } catch (e: Exception) {
                        Log.w("PhotoReceiver", "client error: ${e.message}")
                    } finally {
                        client.close()
                    }
                }
            } catch (e: Exception) {
                Log.w("PhotoReceiver", "server stopped: ${e.message}")
            }
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        job?.cancel()
        job = null
        socket = null
    }
}
```

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `& $g :app:testDebugUnitTest --tests "*PhotoReceiverServerTest*" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/assistant/PhotoReceiverServer.kt app/src/test/java/com/example/teleprompter/assistant/PhotoReceiverServerTest.kt
git commit -m "feat(app): local ServerSocket photo receiver with test"
```

---

### Task B3: Клиент DeepSeek

**Files:**
- Create: `app/src/main/java/com/example/teleprompter/assistant/DeepSeekClient.kt`
- Test: `app/src/test/java/com/example/teleprompter/assistant/DeepSeekClientTest.kt`

**Interfaces:**
- Produces:
  - `object DeepSeekClient`
  - `fun buildRequestBody(jpeg: ByteArray, instruction: String): String`
  - `fun parseAnswer(responseJson: String): String`
  - `suspend fun explain(jpeg: ByteArray, instruction: String, apiKey: String): Result<String>`
  - `const val ENDPOINT`, `const val MODEL`

- [ ] **Step 1: Написать падающие тесты для чистых функций**

```kotlin
package com.example.teleprompter.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    @Test fun `request body has model, instruction and base64 image`() {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val json = JSONObject(DeepSeekClient.buildRequestBody(jpeg, "Объясни фото"))
        assertEquals(DeepSeekClient.MODEL, json.getString("model"))
        val content = json.getJSONArray("messages")
            .getJSONObject(0).getJSONArray("content")
        assertEquals("Объясни фото", content.getJSONObject(0).getString("text"))
        val url = content.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertTrue(url.endsWith(java.util.Base64.getEncoder().encodeToString(jpeg)))
    }

    @Test fun `parseAnswer extracts assistant content`() {
        val resp = """{"choices":[{"message":{"role":"assistant","content":"Это яблоко."}}]}"""
        assertEquals("Это яблоко.", DeepSeekClient.parseAnswer(resp))
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `& $g :app:testDebugUnitTest --tests "*DeepSeekClientTest*" --console=plain`
Expected: FAIL — `DeepSeekClient` не существует.

- [ ] **Step 3: Реализовать клиент**

```kotlin
package com.example.teleprompter.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object DeepSeekClient {
    const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    const val MODEL = "deepseek-v4-flash-vision-exp"

    fun buildRequestBody(jpeg: ByteArray, instruction: String): String {
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", instruction))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return JSONObject().put("model", MODEL).put("messages", messages).toString()
    }

    fun parseAnswer(responseJson: String): String =
        JSONObject(responseJson).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")

    suspend fun explain(jpeg: ByteArray, instruction: String, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                conn.outputStream.use { it.write(buildRequestBody(jpeg, instruction).toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) error("DeepSeek HTTP $code: $text")
                parseAnswer(text).trim()
            }
        }
}
```

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `& $g :app:testDebugUnitTest --tests "*DeepSeekClientTest*" --console=plain`
Expected: PASS (2 tests). Метод `explain()` сетевой — проверяется в E2E (Task B7).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/assistant/DeepSeekClient.kt app/src/test/java/com/example/teleprompter/assistant/DeepSeekClientTest.kt
git commit -m "feat(app): DeepSeek vision client (request/parse tested)"
```

---

### Task B4: Настройки в Prefs

**Files:**
- Modify: `app/src/main/java/com/example/teleprompter/Prefs.kt`

**Interfaces:**
- Consumes: существующие `Prefs.getString/putString/getInt`.
- Produces: константы `Prefs.KEY_DS_API_KEY`, `Prefs.KEY_DS_INSTRUCTION`, `Prefs.KEY_ASSIST_PORT`, `Prefs.DEFAULT_INSTRUCTION`.

- [ ] **Step 1: Добавить ключи и дефолтную инструкцию**

В `Prefs.kt` рядом с другими `const val KEY_...` добавить:

```kotlin
    const val KEY_DS_API_KEY = "ds_api_key"          // ключ DeepSeek API
    const val KEY_DS_INSTRUCTION = "ds_instruction"  // системная инструкция для фото
    const val KEY_ASSIST_PORT = "assist_port"        // порт приёмника фото (по умолчанию 8080)

    const val DEFAULT_INSTRUCTION =
        "Объясни простыми словами, что на фото. Коротко, 1–2 предложения, чтобы уместилось в очки."
    const val DEFAULT_ASSIST_PORT = 8080
```

- [ ] **Step 2: Проверка компиляции**

Run: `& $g compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/Prefs.kt
git commit -m "feat(app): assistant prefs keys and default instruction"
```

---

### Task B5: UI настроек (ключ + инструкция)

**Files:**
- Modify: `app/src/main/java/com/example/teleprompter/SettingsScreen.kt`

**Interfaces:**
- Consumes: `Prefs.KEY_DS_API_KEY`, `Prefs.KEY_DS_INSTRUCTION`, `Prefs.DEFAULT_INSTRUCTION`.

- [ ] **Step 1: Добавить секцию «Ассистент (DeepSeek)»**

В `SettingsScreen` (следуя существующему стилю секций/`OutlinedTextField` в файле) добавить два поля, сохраняющие значения в `Prefs` по изменению:

```kotlin
// --- Секция «Ассистент (DeepSeek)» ---
var dsKey by remember { mutableStateOf(Prefs.getString(context, Prefs.KEY_DS_API_KEY, "")) }
var dsInstr by remember {
    mutableStateOf(Prefs.getString(context, Prefs.KEY_DS_INSTRUCTION, Prefs.DEFAULT_INSTRUCTION))
}
OutlinedTextField(
    value = dsKey,
    onValueChange = { dsKey = it; Prefs.putString(context, Prefs.KEY_DS_API_KEY, it) },
    label = { Text("Ключ DeepSeek API") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)
OutlinedTextField(
    value = dsInstr,
    onValueChange = { dsInstr = it; Prefs.putString(context, Prefs.KEY_DS_INSTRUCTION, it) },
    label = { Text("Инструкция для фото") },
    modifier = Modifier.fillMaxWidth(),
)
```

> Примечание: имя параметра контекста и импорты (`androidx.compose.material3.OutlinedTextField`, `androidx.compose.runtime.*`, `androidx.compose.foundation.layout.fillMaxWidth`) взять как в остальном файле `SettingsScreen.kt`.

- [ ] **Step 2: Проверка компиляции**

Run: `& $g compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Ручная проверка UI**

Собрать `assembleDebug`, установить, открыть настройки → появились «Ключ DeepSeek API» и «Инструкция для фото», значения сохраняются после перезахода.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/SettingsScreen.kt
git commit -m "feat(app): settings UI for DeepSeek key and instruction"
```

---

### Task B6: Контроллер-склейка (фото → DeepSeek → очки)

**Files:**
- Create: `app/src/main/java/com/example/teleprompter/assistant/AssistantController.kt`
- Test: `app/src/test/java/com/example/teleprompter/assistant/AssistantControllerTest.kt`

**Interfaces:**
- Consumes: `PhotoReceiverServer`, `Prefs`, инъекции `explainFn` и `showOnGlasses`.
- Produces:
  ```kotlin
  class AssistantController(
      port: Int,
      instructionProvider: () -> Pair<String, String>,           // (apiKey, instruction)
      explainFn: suspend (ByteArray, String, String) -> Result<String>,
      showOnGlasses: (String) -> Unit,
  ) { fun start(scope: CoroutineScope); fun stop(); suspend fun handlePhoto(jpeg: ByteArray) }
  ```

- [ ] **Step 1: Написать падающий тест на маршрутизацию**

```kotlin
package com.example.teleprompter.assistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantControllerTest {
    @Test fun `shows answer on success`() = runTest {
        val shown = mutableListOf<String>()
        val c = AssistantController(
            port = 0,
            instructionProvider = { "KEY" to "instr" },
            explainFn = { _, _, _ -> Result.success("Это книга.") },
            showOnGlasses = { shown.add(it) },
        )
        c.handlePhoto(byteArrayOf(1, 2, 3))
        assertEquals(listOf("Это книга."), shown)
    }

    @Test fun `shows hint when api key is blank`() = runTest {
        val shown = mutableListOf<String>()
        val c = AssistantController(
            port = 0,
            instructionProvider = { "" to "instr" },
            explainFn = { _, _, _ -> Result.success("не должно вызваться") },
            showOnGlasses = { shown.add(it) },
        )
        c.handlePhoto(byteArrayOf(1))
        assertEquals(1, shown.size)
        assert(shown[0].contains("ключ", ignoreCase = true))
    }

    @Test fun `shows error on failure`() = runTest {
        val shown = mutableListOf<String>()
        val c = AssistantController(
            port = 0,
            instructionProvider = { "KEY" to "instr" },
            explainFn = { _, _, _ -> Result.failure(RuntimeException("timeout")) },
            showOnGlasses = { shown.add(it) },
        )
        c.handlePhoto(byteArrayOf(1))
        assert(shown[0].contains("Ошибка", ignoreCase = true))
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `& $g :app:testDebugUnitTest --tests "*AssistantControllerTest*" --console=plain`
Expected: FAIL — `AssistantController` не существует.

- [ ] **Step 3: Реализовать контроллер**

```kotlin
package com.example.teleprompter.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AssistantController(
    port: Int,
    private val instructionProvider: () -> Pair<String, String>,
    private val explainFn: suspend (ByteArray, String, String) -> Result<String>,
    private val showOnGlasses: (String) -> Unit,
) {
    private val server = PhotoReceiverServer(port) { jpeg ->
        scope?.launch { handlePhoto(jpeg) }
    }
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) { this.scope = scope; server.start(scope) }
    fun stop() { server.stop(); scope = null }

    suspend fun handlePhoto(jpeg: ByteArray) {
        val (apiKey, instruction) = instructionProvider()
        if (apiKey.isBlank()) { showOnGlasses("Настрой ключ DeepSeek в настройках"); return }
        explainFn(jpeg, instruction, apiKey)
            .onSuccess { showOnGlasses(it) }
            .onFailure { showOnGlasses("Ошибка: ${it.message ?: "нет ответа"}") }
    }
}
```

- [ ] **Step 4: Запустить — убедиться, что проходит**

Run: `& $g :app:testDebugUnitTest --tests "*AssistantControllerTest*" --console=plain`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/assistant/AssistantController.kt app/src/test/java/com/example/teleprompter/assistant/AssistantControllerTest.kt
git commit -m "feat(app): assistant controller wiring photo->DeepSeek->glasses"
```

---

### Task B7: Подключение к MainActivity + сквозная проверка

**Files:**
- Modify: `app/src/main/java/com/example/teleprompter/MainActivity.kt`

**Interfaces:**
- Consumes: `AssistantController`, `DeepSeekClient.explain`, `Prefs`, `BleService.startPresentation`, `ScrollMode.MANUAL`.

- [ ] **Step 1: Создать и запускать контроллер, когда очки готовы**

Там, где в `MainActivity` есть привязанный `service: BleService?` и известно, что связь READY (рядом с существующими вызовами `service?.startPresentation(...)` на строке ~926), добавить создание контроллера один раз и его старт:

```kotlin
val assistant = remember {
    AssistantController(
        port = Prefs.getInt(context, Prefs.KEY_ASSIST_PORT, Prefs.DEFAULT_ASSIST_PORT),
        instructionProvider = {
            Prefs.getString(context, Prefs.KEY_DS_API_KEY, "") to
                Prefs.getString(context, Prefs.KEY_DS_INSTRUCTION, Prefs.DEFAULT_INSTRUCTION)
        },
        explainFn = { jpeg, instr, key -> DeepSeekClient.explain(jpeg, instr, key) },
        showOnGlasses = { text ->
            service?.startPresentation(text, ScrollMode.MANUAL, viewModel.wpm, viewModel.bigText)
        },
    )
}
DisposableEffect(ready) {
    if (ready) assistant.start(scope)     // scope = rememberCoroutineScope() выше
    onDispose { assistant.stop() }
}
```

> Импорты: `com.example.teleprompter.assistant.AssistantController`, `com.example.teleprompter.assistant.DeepSeekClient`, `androidx.compose.runtime.DisposableEffect`, `androidx.compose.runtime.rememberCoroutineScope`. Имена `service`, `ready`, `viewModel`, `context` — как в существующем коде вокруг строки 926.

- [ ] **Step 2: Проверка компиляции + сборка**

Run:
```
& $g compileDebugKotlin --console=plain
& $g assembleDebug --console=plain
```
Expected: BUILD SUCCESSFUL, APK в `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Сквозная ручная проверка (E2E)**

1. Установить APK: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
2. В настройках ввести реальный ключ DeepSeek.
3. Включить точку доступа телефона; в `config.h` прошивки прописать её SSID/пароль; прошить ESP32.
4. Подключить очки G2 в приложении, дождаться READY.
5. Навести камеру на предмет, нажать кнопку.
6. Ожидаемо: через 1–3 с в очках появляется короткий текст-объяснение.
7. Негатив: пустой ключ → «Настрой ключ DeepSeek»; выключенный интернет → «Ошибка: …».

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/MainActivity.kt
git commit -m "feat(app): wire assistant controller into MainActivity"
```

---

## Notes / отложено (из спеки §6, §9)

- Переключатель провайдера на Gemini для мелкого текста — не в v1 (интерфейс `explainFn` уже это позволит добавить позже без переделки).
- Несколько пресетов инструкции, deep sleep/энергосбережение, индикация заряда — потом.
- Уточнить при сборке: свободный GPIO под кнопку на конкретной ревизии платы; реальный IP шлюза точки доступа Android (если не `192.168.43.1`); реальное время работы от 500 мА·ч.
