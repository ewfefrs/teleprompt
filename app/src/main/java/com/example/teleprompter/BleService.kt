package com.example.teleprompter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Состояние подключения для UI (отдаётся через LocalBinder.connState). */
data class ConnState(val statusText: String, val isReady: Boolean)

/**
 * Режим прокрутки на очках. В протоколе G2 НЕТ поля скорости встроенной
 * авто-прокрутки (проверено по btsnoop и открытой документации), поэтому
 * регулируемая скорость реализована листанием со стороны приложения (APP_AUTO)
 * — только проверенными на железе кадрами.
 */
enum class ScrollMode {
    APP_AUTO,      // приложение листает само; скорость — ползунком, меняется на лету
    GLASSES_AUTO,  // встроенная плавная прокрутка прошивки очков (скорость фиксированная)
    MANUAL,        // листание кнопками «Назад/Вперёд»
    EVENHUB        // ОПЕРАТОР: текст двигает только палец по превью (EvenHub 0xE0-20, без мигания)
}

/**
 * BLE-сервис для очков Even Realities **G2** (кастомный протокол 0x5401/0x5402).
 *
 * Поток как в проверенном эталоне (i-soxi/even-g2-protocol): connect → notify →
 * READY. По «Старт» одним непрерывным потоком уходит ВСЁ: 7 пакетов
 * аутентификации, затем display-config → init → страницы → marker → sync.
 * Аутентификация и контент ОБЯЗАНЫ идти подряд (как в эталоне), иначе очки не
 * показывают текст.
 *
 * Пейсинг: каждый следующий пакет шлём только после onCharacteristicWrite
 * предыдущего (плюс пауза) — иначе Android молча теряет write-without-response.
 */
class BleService : Service() {

    companion object {
        private const val TAG = "BleService"

        // Кастомный сервис Even G2: запись 0x5401, нотификации 0x5402.
        private val CHAR_WRITE  = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5401")
        private val CHAR_NOTIFY = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5402")
        private val CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Параллельный NUS-канал жестов тачбара (openCFW: без конверта/CRC/авторизации).
        private val NUS_RX      = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private const val DEVICE_MATCH        = "Even G2"
        private const val SCAN_TIMEOUT_MS     = 15_000L
        private const val RECONNECT_DELAY_MS  = 3_000L
        private const val KEEPALIVE_MS        = 6_000L // период keepalive-маркеров показа
        private const val EVENHUB_HB_MS       = 1_000L // период EvenHub-heartbeat (cmd=0xc)
        private const val HUB_TOUCH_STEP      = 3      // строк за свайп по тачбару очков
        private const val EXIT_FLUSH_MS       = 800L   // ожидание отправки кадров выхода перед разрывом
        private const val HUB_TICK_MS         = 200L   // период тика авто-хода (накопление позиции)
        private const val HUB_MIN_MOVE_PX     = 4f     // мин. сдвиг px, чтобы слать новый кадр (защита канала)
        private const val CONN_HEARTBEAT_MS   = 4_000L // connection-heartbeat (реком. 3-5с)
        private const val DEFAULT_BRIGHTNESS  = 60     // яркость на подключении (0..100), чтобы экран светился
        // АВТО-РЕЖИМ = HOST-ЛИСТАНИЕ. По логам очков: ОДНА страница (5 строк) на экран НЕ
        // рисуется — прошивке нужен МНОГОСТРАНИЧНЫЙ показ (ручной показ из 12 страниц
        // рисуется). Поэтому каждый авто-кадр = ПОЛНАЯ последовательность
        // (config+init+страницы+marker+sync) для ОКНА в AUTO_WINDOW_LINES строк, начиная
        // с текущей позиции (очки показывают ВЕРХ окна). По таймеру двигаем позицию вниз
        // на AUTO_STEP_LINES строк → верх окна ползёт вниз = прокрутка. Если BLE ещё
        // занят предыдущим кадром — ждём (синхрон кадров с темпом BLE-пакетов).
        private const val AUTO_WINDOW_LINES = 100      // строк в окне кадра (≥ порога отрисовки; ~10 страниц)
        private const val AUTO_STEP_LINES   = 5        // на сколько строк листаем за кадр (≈ видимая область)
        private const val SCROLL_MIN_MS       = 2_500L // мин. период кадра
        private const val SCROLL_MAX_MS       = 30_000L

        // NUS-жесты тачбара (кадр 0xF5 <код>, из openCFW nus-protocol.md).
        private const val GESTURE_SINGLE_TAP  = 0x01
        private const val GESTURE_DOUBLE_TAP  = 0x00
        private const val GESTURE_SLIDE_FWD   = 0x02
        private const val GESTURE_SLIDE_BACK  = 0x03
        private const val WRITE_RETRY_MS      = 60L   // пауза перед повтором, если стек занят
        private const val WRITE_MAX_RETRY     = 40    // ~2.4с на пакет, дальше пропускаем

        // Стартовые счётчики контента (после auth seq 1..7), как в эталоне.
        private const val CONTENT_SEQ_START = 0x08
        private const val CONTENT_MSG_START = 0x14

        // Ширина текстовой колонки в СИМВОЛАХ. По умолчанию 44 — как у ОФИЦИАЛЬНОГО
        // приложения: в захвате ofc.log самая длинная строка = 43 символа (size=12).
        // Строки фрагментируются в BLE-пакеты по 232 байта, поэтому широкая колонка
        // (кириллица = 2 байта/символ) помещается без проблем.
        private const val PRESO_LINE_BYTES = 44
        // Строк в СТРАНИЦЕ протокола — 10, как у официалки на текущей прошивке.
        private const val PRESO_LINES = 10
        // Окно ручной прокрутки: сколько строк грузим с позиции (≥ порога отрисовки ~8 стр.).
        private const val MANUAL_SCROLL_WINDOW = 80

        const val ACTION_READY        = "teleprompter.READY"
        const val ACTION_DISCONNECTED = "teleprompter.DISCONNECTED"
        const val ACTION_STATUS       = "teleprompter.STATUS"
        const val ACTION_DISCONNECT   = "teleprompter.DISCONNECT"   // кнопка «Отключить» в уведомлении
        const val EXTRA_STATUS        = "status"

        private const val NOTIF_ID   = 1
        private const val CHANNEL_ID = "ble_ch"
    }

    enum class State { IDLE, SCANNING, CONNECTING, READY }

    inner class LocalBinder : Binder() {
        fun getService() = this@BleService
    }

    private val binder        = LocalBinder()
    private val handler       = Handler(Looper.getMainLooper())
    private var state         = State.IDLE
    private var autoReconnect = true
    private var tearingDown   = false

    // МАСТЕР — ПРАВАЯ дужка: официалка шлёт ВЕСЬ протокол (auth, разогрев, телесуфлёр)
    // на правую (btsnoop handle→MAC: все TX на e4:7b:b8 = _R_). Контент в левую
    // принимается и ACK'ается, но НЕ рисуется — правая владеет конвейером дисплея.
    private var gatt      : BluetoothGatt? = null   // ЛЕВАЯ дужка (подключена + пинг + события)
    private var writeChar : BluetoothGattCharacteristic? = null   // 0x5401 левой (для пинга)
    private var gattR     : BluetoothGatt? = null   // ПРАВАЯ дужка (МАСТЕР: все записи)
    private var writeCharR: BluetoothGattCharacteristic? = null   // 0x5401 правой
    private var nusCharL  : BluetoothGattCharacteristic? = null
    private var nusCharR  : BluetoothGattCharacteristic? = null
    private var lastGestureCode = -1                // дедуп жестов (приходят с обеих дужек)
    private var lastGestureMs = 0L
    private var candidate : BluetoothDevice? = null
    private var leftDevice : BluetoothDevice? = null
    private var rightDevice: BluetoothDevice? = null
    private var lReady = false   // левая: нотификации включены
    private var rReady = false   // правая: нотификации включены (или R не нужна)
    private var authStarted = false
    private var mtu       = 23

    // Пейсинг-очередь отправки: пакеты уходят по ТАЙМЕРУ (как официальное приложение,
    // ~45мс между пакетами по btsnoop), НЕ по onCharacteristicWrite. Ack-пейсинг ломался:
    // при задержке подтверждения >700мс вотчдог продолжал вслепую, подтверждения
    // рассинхронизировались и стек молча терял пакеты — очки получали кадры с дырками
    // и не показывали текст.
    private val outboxLock = Any()
    private val outbox     = ArrayDeque<EvenG2Protocol.Item>()
    private var draining   = false
    private var retryCount = 0

    // Пустой статус → UI показывает локализованный «Отключено» (st_disconnected).
    private val _connState = MutableStateFlow(ConnState("", false))
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    /** События с очков для синхронизации UI: "started" / "resumed" / "stopped". */
    private val _glassesEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val glassesEvents: SharedFlow<String> = _glassesEvents.asSharedFlow()

    val isReady get() = state == State.READY

    // Текущий показ (весь текст загружается на очки целиком).
    private var presoLines: List<String> = emptyList()
    private var presoLinesPerScreen = 5
    private var presoActive = false
    private var presoPaused = false
    private var presoMode = ScrollMode.APP_AUTO
    private var presoBig = false         // размер текста: false=мелкий (size 12, официальная широкая колонка), true=крупный (size 16)
    private var presoSourceText: String? = null
    private var presoWpm = 130f          // скорость чтения, слов/мин (WPM)
    private var presoLps = 0.3f          // строк/сек, вычисляется из WPM и слов-в-строке текста
    private var presoPos = 0             // верхняя строка текущего авто-кадра
    private var scrollIntervalMs = 3_000L // период авто-кадра (из скорости)
    // РУЧНАЯ прокрутка С ТЕЛЕФОНА: верхняя видимая строка + подавление «закрытия» показа
    // на время пере-присылки окна (пере-инициализация шлёт событие 0xA1, его игнорируем).
    private var manualTop = 0
    private var suppressCloseUntil = 0L

    // ПЛАВНЫЙ показ через EvenHub-контейнеры (сервис 0xE0-20). Страница создаётся ОДИН раз
    // (buildEvenHubCreate), дальше каждый шаг = textContainerUpgrade НА МЕСТЕ (без мигания).
    // Одна логическая EvenHub-команда = один seq (фрагменты делят seq), после неё seq/msg +1.
    private var presoHub = false
    private var hubLines: List<String> = emptyList()  // весь текст, разбитый на строки
    private var hubTop = 0                             // верхняя видимая строка окна
    private var hubAuto = false                        // идёт ли авто-сдвиг
    private var hubNarrow = false                      // узкий экран (полоса в 4 строки по центру)
    private var hubMenuMode = false                    // на очках открыто меню выбора текста (не показ)
    private var hubBlanked = false                     // «сон»: экран погашен одиночным тапом (авто на паузе)
    // appId нашего пункта в меню очков (хеш имени пакета). Только для РЕГИСТРАЦИИ пункта
    // (0x03-20) и опознания события выбора cmd=17 — как владелец страницы (f5) НЕ годится.
    private val menuAppId: Int by lazy { EvenG2Protocol.menuAppId(packageName) }
    private var hubPosPx = 0f                          // позиция авто-хода в ПИКСЕЛЯХ (27px = строка)
    private var hubSubPx = 0                           // суб-строчное смещение 0..26 (плавный ход)
    private var nativePage = 0                         // позиция нативного показа (отчёты 0xA4)

    /** Строк в окне показа: широкий = 9, узкий = 4. */
    private val hubWindowLines: Int
        get() = if (hubNarrow) EvenG2Protocol.EH_NARROW_ROWS else EvenG2Protocol.EH_WIDE_ROWS

    // Сценарий, «подготовленный» из UI: тап по тачбару очков запускает его без телефона.
    private var preparedText: String? = null
    private var preparedMode = ScrollMode.APP_AUTO
    private var preparedSpeed = 5f
    private var preparedBig = false
    private var lastTouchMs = 0L         // дебаунс свайпов тачбара
    // Выбор скрипта С ОЧКОВ: список (заголовок, текст) + текущий индекс. Свайп по
    // тачбару (когда показа нет) листает скрипты, тап — запускает выбранный.
    private var preparedScripts: List<Pair<String, String>> = emptyList()
    private var preparedIndex = 0

    /** Текст текущего выбранного скрипта (для синхронизации UI телефона). */
    val selectedScript: String? get() = preparedText
    // «Ручки» под физический дисплей очков (узкий и низкий). Подбираются по виду на очках:
    //   PRESO_LINE_BYTES — макс. байт UTF-8 на строку (кириллица = 2 байта/символ),
    //   PRESO_LINES      — сколько строк на один экран.
    private var presoLineByteLimit = PRESO_LINE_BYTES
    private var contentSeq = CONTENT_SEQ_START
    private var contentMsg = CONTENT_MSG_START

    // Авторизация challenge-response: msg_id из ответа очков (0x8001) и флаги этапа.
    private var lastChallengeMsg = -1
    private var authDone = false
    private var authArmed = false   // true после кадра2: challenge → сразу шлём кадр3/4
    private val completeAuthRunnable = Runnable { completeAuth() }

    // ---------------------------------------------------------------------------------------------
    // Публичный API для UI
    // ---------------------------------------------------------------------------------------------

    /**
     * Начать показ сценария [text] ЦЕЛИКОМ (весь текст уходит на очки одним показом):
     *  - [ScrollMode.APP_AUTO] (и GLASSES_AUTO) — mode=AUTO: очки плавно прокручивают
     *        весь текст сами; скорость = [speed] (1..10) через поле scrollIntervalMs,
     *        меняется на лету одиночным init-кадром ([setScrollSpeed]);
     *  - [ScrollMode.MANUAL] — mode=MANUAL: текст стоит, листается свайпами по
     *        тачбару силами прошивки (без участия телефона).
     */
    fun startPresentation(text: String, mode: ScrollMode, speed: Float, big: Boolean = true) {
        if (state != State.READY) {
            Log.w(TAG, "Не READY (state=$state), пропускаем startPresentation")
            return
        }
        if (!authDone) {
            Log.w(TAG, "Авторизация ещё идёт — подождите пару секунд и повторите «Старт»")
            report(locStr(R.string.st_authorizing), ready = true)
            return
        }
        presoActive = false
        presoPaused = false
        presoHub = false
        handler.removeCallbacks(scrollTick)
        handler.removeCallbacks(hubAutoTick)
        handler.removeCallbacks(hubKeepaliveTick)
        handler.removeCallbacks(keepaliveTick)
        synchronized(outboxLock) { outbox.clear() }

        presoMode = mode
        presoBig = big
        // Политика тарифа (лимит слов + промо в конце) применяется ОДИН раз здесь; дальше
        // presoSourceText уже обработан, и повторные рендеры (обновление после сна) не дублируют.
        val shown = ContentPolicy.apply(LocaleManager.wrap(this), text).text
        presoSourceText = shown

        // ВСЕ режимы — EvenHub (0xE0-20). АВТО — сам ход, Ручной/Оператор — палец.
        startEvenHub(shown, speed, auto = (mode == ScrollMode.APP_AUTO || mode == ScrollMode.GLASSES_AUTO))
    }

    /** Период авто-кадра из скорости: время на прочтение шага строк при текущем темпе. */
    private fun updateScrollInterval() {
        scrollIntervalMs = ((AUTO_STEP_LINES / presoLps.coerceAtLeast(0.1f)) * 1000f)
            .toLong().coerceIn(SCROLL_MIN_MS, SCROLL_MAX_MS)
    }

    /**
     * Показать текущий кадр (авто-режим): ПОЛНАЯ последовательность
     * (config→init→страницы→marker→sync) для ОКНА [AUTO_WINDOW_LINES] строк с позиции
     * [presoPos] (добито пустыми до окна — прошивке нужен многостраничный показ, одна
     * страница не рисуется). Очки показывают ВЕРХ окна = строку presoPos. Счётчики
     * МОНОТОННЫЕ (наша авторизация уходит за 0x14; сброс слал бы дубли → пустой экран).
     * Верхний отступ центрирования = 0 (не съедаем видимые строки в авто).
     */
    private fun sendCurrentFrame() {
        if (!presoActive || presoLines.isEmpty()) return
        val start = presoPos.coerceIn(0, (presoLines.size - 1).coerceAtLeast(0))
        val window = ArrayList<String>(AUTO_WINDOW_LINES)
        var i = start
        while (i < presoLines.size && window.size < AUTO_WINDOW_LINES) { window.add(presoLines[i]); i++ }
        while (window.size < AUTO_WINDOW_LINES) window.add(" ")   // добивка до порога отрисовки
        val built = EvenG2Protocol.buildScript(
            window.joinToString("\n"), contentSeq, contentMsg, presoLineByteLimit, presoLinesPerScreen,
            manualMode = true, big = presoBig, topMargin = 0
        )
        contentSeq = built.nextSeq
        contentMsg = built.nextMsg
        Log.i(TAG, "Авто-кадр: с строки $start из ${presoLines.size}, ${built.items.size} пакетов, seq→$contentSeq")
        enqueue(built.items)
    }

    /** Тик авто-листания: двигаем позицию вниз на шаг. Если BLE ещё занят предыдущим
     *  кадром — ждём (синхрон кадров с темпом BLE-пакетов), не копим очередь. */
    private val scrollTick: Runnable = object : Runnable {
        override fun run() {
            if (!presoActive || presoPaused || presoMode != ScrollMode.APP_AUTO || state != State.READY) return
            if (synchronized(outboxLock) { draining }) {
                handler.postDelayed(this, 1_000L)
                return
            }
            val nextPos = presoPos + AUTO_STEP_LINES
            if (nextPos >= presoLines.size) {
                Log.i(TAG, "Авто-листание: конец текста")
                presoActive = false
                _glassesEvents.tryEmit("stopped")
                return
            }
            presoPos = nextPos
            sendCurrentFrame()
            handler.postDelayed(this, scrollIntervalMs)
        }
    }

    /** Пауза показа: в EvenHub-режимах останавливаем авто-тик (текст остаётся на экране). */
    fun pausePresentation() {
        presoPaused = true
        handler.removeCallbacks(scrollTick)
        handler.removeCallbacks(hubAutoTick)
        if (presoHub) return
        if (state == State.READY) enqueueSingle { s, m -> EvenG2Protocol.buildControl(s, m, EvenG2Protocol.TP_PAUSE) }
    }

    /** Продолжить показ: в авто-режиме снова заводим тик с текущей позиции. */
    fun resumePresentation() {
        presoPaused = false
        if (presoHub) {
            if (state == State.READY && presoActive && hubAuto) {
                handler.removeCallbacks(hubAutoTick)
                handler.postDelayed(hubAutoTick, hubIntervalMs())
            }
            return
        }
        if (state == State.READY && presoActive) enqueueSingle { s, m -> EvenG2Protocol.buildControl(s, m, EvenG2Protocol.TP_PLAY) }
    }

    /**
     * Полностью остановить показ. Кадр «08 01 10 msg 1a 02 08 04» — на прошивке
     * 2.2.5.102 закрывает телесуфлёр (проверено логами: после него очки → idle).
     */
    fun stopPresentation() {
        presoActive = false
        presoPaused = false
        hubMenuMode = false
        hubBlanked = false
        handler.removeCallbacks(scrollTick)
        handler.removeCallbacks(hubAutoTick)
        handler.removeCallbacks(hubKeepaliveTick)
        handler.removeCallbacks(keepaliveTick)
        // Выбрасываем недоотправленные кадры, чтобы СТОП ушёл очкам сразу, а не после них.
        synchronized(outboxLock) { outbox.clear() }
        if (presoHub) {
            // EvenHub-показ закрываем shutDownPageContainer — ДВАЖДЫ, как buildStop
            // (write-without-response может потеряться; по логам один кадр закрывал
            // показ не всегда, после двух подряд очки шлют 0d01 idle).
            presoHub = false
            hubAuto = false
            Log.i(TAG, "Стоп EvenHub: shutdown x2")
            enqueueHubMsg(gap = 250L) { s, m -> listOf(EvenG2Protocol.buildEvenHubShutdown(s, m)) }
            enqueueHubMsg(gap = 250L) { s, m -> listOf(EvenG2Protocol.buildEvenHubShutdown(s, m)) }
            return
        }
        // Дважды подряд — как раньше (кадр 100% закрывает показ).
        enqueueSingle { s, m -> EvenG2Protocol.buildStop(s, m) }
        enqueueSingle { s, m -> EvenG2Protocol.buildStop(s, m) }
    }

    /**
     * ЧИСТЫЙ ВЫХОД на очках перед отключением: закрываем ВСЁ (EvenHub-страницу и
     * телесуфлёр), чтобы очки вернулись на домашний экран, а не висели с последним кадром
     * / «соединение потеряно». BLE не рвём — вызывающий ждёт [EXIT_FLUSH_MS] на отправку
     * кадров и только потом отключается.
     */
    fun exitGlasses() {
        presoActive = false
        presoPaused = false
        handler.removeCallbacks(scrollTick)
        handler.removeCallbacks(hubAutoTick)
        handler.removeCallbacks(hubKeepaliveTick)
        handler.removeCallbacks(keepaliveTick)
        if (state != State.READY) return
        synchronized(outboxLock) { outbox.clear() }
        Log.i(TAG, "Выход на очках: shutdown EvenHub + stop телесуфлёра")
        // EvenHub: закрыть страницу (возврат на домашний экран очков), дважды.
        enqueueHubMsg(gap = 200L) { s, m -> listOf(EvenG2Protocol.buildEvenHubShutdown(s, m)) }
        enqueueHubMsg(gap = 200L) { s, m -> listOf(EvenG2Protocol.buildEvenHubShutdown(s, m)) }
        // Телесуфлёр: на случай, если открыт был он.
        enqueueSingle(120L) { s, m -> EvenG2Protocol.buildStop(s, m) }
        presoHub = false
        hubAuto = false
    }

    /** Сколько ждать отправки кадров выхода перед разрывом BLE. */
    val exitFlushMs: Long get() = EXIT_FLUSH_MS

    /** Keepalive показа: маркер каждые ~6с (официалка шлёт его, пока телесуфлёр открыт)
     *  — без него прошивка закрывает показ по таймауту простоя (~12с). Нужен для ОБОИХ
     *  режимов (в авто нативная прокрутка тоже не считается «активностью» протокола). */
    private val keepaliveTick: Runnable = object : Runnable {
        override fun run() {
            if (!presoActive || state != State.READY) return
            enqueueSingle(45L) { s, m -> EvenG2Protocol.buildKeepalive(s, m) }
            handler.postDelayed(this, KEEPALIVE_MS)
        }
    }

    /** Connection-heartbeat (0x8000 type=14) каждые ~4с всё время подключения —
     *  официальное приложение делает так же (реком. интервал 3-5с, openCFW). */
    private val connHeartbeatTick: Runnable = object : Runnable {
        override fun run() {
            if (state != State.READY || !authDone) return
            enqueueSingle(45L) { s, m -> EvenG2Protocol.buildHeartbeat(s, m) }
            handler.postDelayed(this, CONN_HEARTBEAT_MS)
        }
    }

    /**
     * Яркость дисплея очков (0..100). Канал 0x0920, из логов офиц. приложения.
     * Меняется в любой момент, показ трогать не нужно.
     */
    fun setBrightness(level: Int) {
        enqueueSingle { s, m -> EvenG2Protocol.buildBrightness(s, m, level) }
    }

    /** Ширина колонки текста на очках в СИМВОЛАХ (сетка text.md: до 50 столбцов на
     *  весь экран). В EvenHub-показе применяется СРАЗУ (пере-разбивка строк + rebuild
     *  окна с сохранением позиции); в остальных режимах — со следующим показом/кадром. */
    fun setColumnWidth(chars: Int) {
        presoLineByteLimit = chars.coerceIn(10, 44)
        if (presoHub && presoActive && state == State.READY) {
            val src = presoSourceText ?: return
            val frac = if (hubLines.isNotEmpty()) hubTop.toFloat() / hubLines.size else 0f
            hubLines = EvenG2Protocol.wrapLines(src, presoLineByteLimit, center = false)
            hubTop = (frac * hubLines.size).toInt().coerceIn(0, hubMaxTop)
            hubPosPx = hubTop * 27f
            hubSubPx = 0
            Log.i(TAG, "EvenHub: ширина колонки → $presoLineByteLimit, строк ${hubLines.size}")
            sendHubWindow(gap = 150L, force = true)
        }
    }

    /** Полное число строк текущего текста (для ползунка позиции на телефоне). */
    val presoTotalLines: Int get() = presoLines.size
    /** Текущая верхняя строка (для UI). */
    val presoTopLine: Int get() = manualTop

    /**
     * ПРОКРУТКА С ТЕЛЕФОНА к строке [topLine]. Пере-присылка ТОЛЬКО init (startPage/
     * startLine) позицию НЕ двигала — прошивка применяет их лишь при открытии показа.
     * Поэтому физически ПЕРЕ-ЗАГРУЖАЕМ окно контента с позиции [topLine]: config→init→
     * страницы окна. Очки показывают верх окна = topLine. Пере-инициализация шлёт
     * «закрытие» (0xA1) — игнорируем его ~2.5с (suppressCloseUntil).
     */
    fun scrollTo(topLine: Int) {
        if (state != State.READY || presoLines.isEmpty()) return
        manualTop = topLine.coerceIn(0, (presoLines.size - 1).coerceAtLeast(0))
        presoActive = true
        val end = minOf(manualTop + MANUAL_SCROLL_WINDOW, presoLines.size)
        val window = presoLines.subList(manualTop, end).joinToString("\n")
        suppressCloseUntil = SystemClock.elapsedRealtime() + 2500L
        synchronized(outboxLock) { outbox.clear() }
        val built = EvenG2Protocol.buildScript(
            window, contentSeq, contentMsg, presoLineByteLimit, presoLinesPerScreen,
            manualMode = true, big = presoBig, topMargin = 0
        )
        contentSeq = built.nextSeq
        contentMsg = built.nextMsg
        Log.i(TAG, "Прокрутка (окно) → строка $manualTop из ${presoLines.size}, ${built.items.size} пакетов")
        enqueue(built.items)
        handler.removeCallbacks(keepaliveTick)
        handler.postDelayed(keepaliveTick, KEEPALIVE_MS)
    }

    /** Сдвинуть позицию ручной прокрутки на [delta] строк (кнопки ▲/▼ на телефоне). */
    fun scrollBy(delta: Int) = scrollTo(manualTop + delta)

    // ---------------------------------------------------------------------------------------------
    // EvenHub плавная прокрутка (сервис 0xE0-20) — createStartUpPage ОДИН раз, дальше
    // textContainerUpgrade НА МЕСТЕ. Тот же путь для авто (таймер) и телефона (hubScrollToPage).
    // ---------------------------------------------------------------------------------------------

    /** Отправить одну EvenHub-команду (с фрагментацией) и продвинуть seq/msg на 1.
     *  КРИТИЧНО: фрагменты одного сообщения идут ВПЛОТНУЮ (25мс) — прошивка собирает кадр
     *  с коротким таймаутом, пауза между фрагментами рвёт сборку. [gap] — задержка ПОСЛЕ
     *  последнего фрагмента (пауза операции: create ~500мс, upgrade ~200мс). */
    private fun enqueueHubMsg(gap: Long, build: (Int, Int) -> List<ByteArray>) {
        if (state != State.READY) return
        val frags = build(contentSeq, contentMsg)
        contentSeq = (contentSeq + 1) and 0xFF
        contentMsg = (contentMsg + 1) and 0xFF   // msgId — БАЙТ с заворотом (как MentraOS):
        // при переходе 255→256 варинт стал бы 2-байтным (80 02), и прошивка НАГЛУХО клинит
        // EvenHub-обработчик (проверено btsnoop 2026-07-14 — это и была «смерть на 63с»).
        val last = frags.size - 1
        enqueue(frags.mapIndexed { i, f -> EvenG2Protocol.Item(f, if (i == last) gap else 25L) })
    }

    /** Запустить EvenHub-показ: создать страницу «текст + полоса прогресса». При [auto]
     *  таймер двигает окно на строку с темпом из скорости; иначе двигают палец/тачбар. */
    private fun startEvenHub(text: String, speed: Float, auto: Boolean, startTop: Int = 0, pageExists: Boolean = false) {
        presoHub = true
        presoActive = true
        presoPaused = false
        hubMenuMode = false
        hubBlanked = false
        hubLines = EvenG2Protocol.wrapLines(text, presoLineByteLimit, center = false)
        hubTop = startTop.coerceIn(0, hubMaxTop)
        hubPosPx = hubTop * 27f
        hubLastSentPx = hubPosPx
        hubSubPx = 0
        hubAuto = auto
        setScrollSpeed(speed)
        hubLastSentTop = -1; hubLastSentSub = -1; hubHbCount = 0
        Log.i(TAG, "EvenHub старт: ${hubLines.size} строк, окно $hubWindowLines, колонка $presoLineByteLimit, авто=$auto, узкий=$hubNarrow")
        // Обратный отсчёт 3-2-1 перед авто-стартом (только новый показ и если включён в настройках).
        val countdown = auto && !pageExists && Prefs.getBool(this, Prefs.KEY_COUNTDOWN, true)
        if (pageExists) {
            // Переход из меню выбора: страница УЖЕ создана — просто перерисовываем в контент
            // показа через rebuild (второй create на живую страницу = no-op).
            sendHubWindow(gap = 200L, force = true)
        } else {
            // ПРОБУЖДАЕМ ЭКРАН (0x04-20) — иначе create уходит в спящий дисплей и текст не
            // появляется (частая причина «зависло» / «повторный старт не отсылается»).
            enqueueSingle(80L) { s, m -> EvenG2Protocol.buildDisplayWake(s, m) }
            // Яркость поверх — гарантируем светящийся экран.
            enqueueSingle(80L) { s, m -> EvenG2Protocol.buildBrightness(s, m, DEFAULT_BRIGHTNESS) }
            // ВХОД В РЕЖИМ РЕНДЕРА: display-config 0x0E-20 (тот же 138-байтный, что перед
            // телесуфлёром). Официалка шлёт его перед create — без него обработчик EvenHub
            // молчит (проверено: даже heartbeat без него не получал ответа). Пауза 400мс.
            enqueueSingle(300L) { s, m -> EvenG2Protocol.buildDisplayConfig(s, m) }
            // CreateStartUpPage (cmd=0) — открывает EvenHub-сессию (с окна текущей позиции).
            enqueueHubMsg(gap = 350L) { s, m ->
                EvenG2Protocol.buildEvenHubCreate(
                    s, m, if (countdown) " " else hubWindow(hubTop), presoLineByteLimit, hubFilledRows(), hubNarrow
                )
            }
        }
        // ВНИМАНИЕ: cmd=9 {0} сюда слать НЕЛЬЗЯ — это «скрыть страницу» (проверено на
        // железе: после него текст пропадает, а rebuild возвращает код 7 = FAILD).
        report(locStr(R.string.st_showing), ready = true)
        // Heartbeat раз в ~1с — держит сессию (стартуем после Create).
        handler.removeCallbacks(hubKeepaliveTick)
        handler.postDelayed(hubKeepaliveTick, 1_200L)
        if (auto) {
            if (countdown) {
                handler.postDelayed({ runCountdown(3) }, 700L)   // старт после отрисовки create-страницы
            } else {
                handler.removeCallbacks(hubAutoTick)
                handler.postDelayed(hubAutoTick, hubIntervalMs() + 700L)   // старт после Create
            }
        }
    }

    /** Текст окна из [hubWindowLines] строк с верхней строки [top]. */
    private fun hubWindow(top: Int): String {
        if (hubLines.isEmpty()) return " "
        val t = top.coerceIn(0, (hubLines.size - 1).coerceAtLeast(0))
        val end = minOf(t + hubWindowLines, hubLines.size)
        return hubLines.subList(t, end).joinToString("\n")
    }

    /** Максимальная верхняя строка (дальше окно упирается в конец текста). */
    private val hubMaxTop: Int get() = (hubLines.size - hubWindowLines).coerceAtLeast(0)

    /** Заполнение полосы прогресса: сколько строк из EH_BAR_ROWS «прочитано». */
    private fun hubFilledRows(): Int =
        if (hubMaxTop <= 0) 0 else (hubTop * EvenG2Protocol.EH_BAR_ROWS) / hubMaxTop

    /** Rebuild текущего окна (текст + прогресс + суб-строчное смещение) на месте.
     *  Пропускаем, если позиция не изменилась (тот же top+subPx) — не грузим канал зря. */
    private fun sendHubWindow(gap: Long = 120L, force: Boolean = false) {
        if (!force && hubTop == hubLastSentTop && hubSubPx == hubLastSentSub) return
        hubLastSentTop = hubTop
        hubLastSentSub = hubSubPx
        enqueueHubMsg(gap) { s, m ->
            EvenG2Protocol.buildEvenHubRebuild(
                s, m, hubWindow(hubTop), presoLineByteLimit, hubFilledRows(), hubNarrow, hubSubPx
            )
        }
    }

    /** Показать произвольный текст в EvenHub-окне (для обратного отсчёта 3-2-1). */
    private fun sendHubText(content: String, gap: Long = 60L) {
        enqueueHubMsg(gap) { s, m ->
            EvenG2Protocol.buildEvenHubRebuild(s, m, content, presoLineByteLimit, hubFilledRows(), hubNarrow)
        }
    }

    /** Обратный отсчёт 3-2-1: показываем цифру и планируем следующую через ФИКСИРОВАННЫЙ
     *  интервал (равные паузы независимо от нагрузки канала). По достижении 0 — показываем
     *  текст и запускаем авто-ход. */
    private fun runCountdown(from: Int) {
        if (!presoActive || !presoHub || state != State.READY) return
        if (from <= 0) {
            hubLastSentTop = -1; hubLastSentSub = -1
            sendHubWindow(gap = 60L, force = true)
            handler.removeCallbacks(hubAutoTick)
            handler.postDelayed(hubAutoTick, hubIntervalMs())
            return
        }
        sendHubText("\n\n$from")
        handler.postDelayed({ runCountdown(from - 1) }, 900L)
    }

    /** УЗКИЙ/ШИРОКИЙ экран (как в официалке). Применяется сразу, даже во время показа. */
    fun setScreenNarrow(narrow: Boolean) {
        if (hubNarrow == narrow) return
        hubNarrow = narrow
        Log.i(TAG, "Экран: ${if (narrow) "узкий (${EvenG2Protocol.EH_NARROW_ROWS} строк)" else "широкий (${EvenG2Protocol.EH_WIDE_ROWS} строк)"}")
        if (presoHub && presoActive && state == State.READY) {
            hubTop = hubTop.coerceIn(0, hubMaxTop)
            hubPosPx = hubTop * 27f
            hubSubPx = 0
            sendHubWindow(gap = 150L, force = true)
        }
    }

    /** «СОН» по одиночному тапу: гасим экран (пустая страница с живым слоем захвата
     *  событий), авто-прокрутку ставим на паузу. Пробуждение — следующим одиночным тапом.
     *  Позиция [hubTop]/[hubPosPx] сохраняется, поэтому при пробуждении показ на том же месте. */
    fun sleepHub() {
        if (!presoHub || !presoActive || state != State.READY || hubBlanked) return
        hubBlanked = true
        handler.removeCallbacks(hubAutoTick)               // авто на паузу
        hubLastSentTop = -1; hubLastSentSub = -1           // сбрасываем дедуп (после пустой страницы)
        Log.i(TAG, "Очки: одиночный тап — СОН (экран погашен, top=$hubTop)")
        enqueueHubMsg(gap = 150L) { s, m -> EvenG2Protocol.buildEvenHubBlankRebuild(s, m) }
        _glassesEvents.tryEmit("sleep")
    }

    /** ПРОБУЖДЕНИЕ по одиночному тапу: перерисовываем окно текста на текущей позиции и
     *  возобновляем авто-ход (если он был). */
    fun wakeHub() {
        if (!presoHub || !presoActive || state != State.READY) return
        hubBlanked = false
        Log.i(TAG, "Очки: одиночный тап — ПРОБУЖДЕНИЕ (top=$hubTop)")
        sendHubWindow(gap = 150L, force = true)            // вернуть текст на место
        if (hubAuto && !presoPaused) {
            handler.removeCallbacks(hubAutoTick)
            handler.postDelayed(hubAutoTick, hubIntervalMs() + 400L)
        }
        _glassesEvents.tryEmit("wake")
    }

    /** Выполнить действие, назначенное на жест тачбара (кастомизация в настройках). */
    private fun performGesture(action: Customization.GestureAction) {
        // Во «сне» реагируем только на пробуждение (SLEEP_WAKE), остальные жесты игнорируем.
        if (hubBlanked && action != Customization.GestureAction.SLEEP_WAKE) return
        when (action) {
            Customization.GestureAction.NONE -> {}
            Customization.GestureAction.SLEEP_WAKE -> if (hubBlanked) wakeHub() else sleepHub()
            Customization.GestureAction.SCROLL_FWD -> hubScrollFromGlasses(HUB_TOUCH_STEP)
            Customization.GestureAction.SCROLL_BACK -> hubScrollFromGlasses(-HUB_TOUCH_STEP)
            Customization.GestureAction.PAUSE_RESUME -> if (presoPaused) resumePresentation() else pausePresentation()
            Customization.GestureAction.EXIT -> exitToHome()
        }
    }

    /** Интервал между тиками при ожидании занятого BLE. */
    private fun hubIntervalMs(): Long = HUB_TICK_MS

    /** Тик авто-хода: позиция в пикселях КОПИТСЯ каждый тик по времени, но полный rebuild
     *  шлётся ТОЛЬКО когда сдвиг ≥ [HUB_MIN_MOVE_PX] И BLE свободен. Иначе на медленной
     *  скорости мы слали ~5 полных кадров/с ради 1-2px — канал захлёбывался, heartbeat
     *  задерживался, прошивка закрывала сессию и рвала связь. Теперь ~2 кадра/с максимум. */
    private var hubLastSentPx = 0f
    private var hubLastSentTop = -1     // дедуп: не слать одинаковый кадр (та же позиция)
    private var hubLastSentSub = -1
    private var hubDiagMs = 0L          // троттлинг диагностического лога авто-тика
    private val hubAutoTick: Runnable = object : Runnable {
        override fun run() {
            if (!presoActive || presoPaused || !presoHub || !hubAuto || state != State.READY) return
            val maxPx = hubMaxTop * 27f
            if (hubPosPx >= maxPx && hubLastSentPx >= maxPx) {
                Log.i(TAG, "Авто: конец текста")
                _glassesEvents.tryEmit("stopped")
                // Авто-скрытие: через N секунд закрываем показ (0 = никогда — текст остаётся).
                val hideSec = Customization.autoHideSec(this@BleService)
                if (hideSec > 0) handler.postDelayed({
                    if (presoHub && presoActive) { Log.i(TAG, "Авто-скрытие текста после конца"); stopPresentation() }
                }, hideSec * 1000L)
                return   // heartbeat живёт до скрытия — последний кадр остаётся на очках
            }
            // Копим позицию по времени всегда — ход равномерный, даже если кадр не шлём.
            val stepPx = presoLps.coerceAtLeast(0.02f) * 27f * (HUB_TICK_MS / 1000f)
            hubPosPx = (hubPosPx + stepPx).coerceAtMost(maxPx)
            val moved = hubPosPx - hubLastSentPx
            val busy = synchronized(outboxLock) { draining }
            if (!busy && (moved >= HUB_MIN_MOVE_PX || hubPosPx >= maxPx)) {
                hubTop = (hubPosPx / 27f).toInt().coerceIn(0, hubMaxTop)
                hubSubPx = (hubPosPx - hubTop * 27f).toInt()
                hubLastSentPx = hubPosPx
                sendHubWindow(gap = 60L)
            }
            val nowD = SystemClock.elapsedRealtime()
            if (nowD - hubDiagMs > 1000L) {
                hubDiagMs = nowD
                Log.i(TAG, "авто-тик pos=${hubPosPx.toInt()}/${maxPx.toInt()} top=$hubTop lps=${"%.2f".format(presoLps)} busy=$busy moved=${moved.toInt()} outbox=${synchronized(outboxLock){outbox.size}}")
            }
            handler.postDelayed(this, HUB_TICK_MS)
        }
    }

    /** EvenHub heartbeat cmd=0xc — держит сессию живой (официалка шлёт раз в ~1с). Без него
     *  прошивка закрывает EvenHub-показ по таймауту простоя. */
    private var hubHbCount = 0
    private val hubKeepaliveTick: Runnable = object : Runnable {
        override fun run() {
            if (!presoHub || !presoActive || state != State.READY) return
            enqueueSingle(45L) { s, m -> EvenG2Protocol.buildEvenHubHeartbeat(s, m) }
            // Каждые ~8с — wake экрана (0x04-20). Во «сне» НЕ будим дисплей — пусть гаснет.
            if (hubHbCount % 8 == 0 && !hubBlanked) enqueueSingle(45L) { s, m -> EvenG2Protocol.buildDisplayWake(s, m) }
            hubHbCount++
            handler.postDelayed(this, EVENHUB_HB_MS)
        }
    }

    /** Всего строк EvenHub-показа (для ползунка/жеста на телефоне). */
    val hubTotalLines: Int get() = hubLines.size
    /** Текущая верхняя строка EvenHub-показа. */
    val hubTopLine: Int get() = hubTop
    /** Идёт ли EvenHub-показ (для UI). */
    val isHubShow: Boolean get() = presoHub && presoActive
    /** Позиция показа 0..1 (реальная, для превью и полосы на телефоне). Берём СУБ-СТРОЧНУЮ
     *  позицию [hubPosPx] (не квантованный [hubTop]) — тогда превью на телефоне следует за
     *  плавным ходом очков непрерывно, а не рывками раз в строку. */
    val hubProgress: Float
        get() = if (hubMaxTop <= 0) 0f else (hubPosPx / (hubMaxTop * 27f)).coerceIn(0f, 1f)

    /** Позиция ЛЮБОГО показа 0..1: EvenHub — по окну; нативный — по отчётам страниц 0xA4. */
    val showProgress: Float
        get() = when {
            presoHub -> hubProgress
            presoLines.isNotEmpty() -> {
                val totalPages = ((presoLines.size + PRESO_LINES - 1) / PRESO_LINES - 1).coerceAtLeast(1)
                (nativePage.toFloat() / totalPages).coerceIn(0f, 1f)
            }
            else -> 0f
        }

    /** ПРОКРУТКА к строке [top] (палец по превью / тачбар; плавно, rebuild на месте).
     *  В авто-режиме протяжка отодвигает следующий тик — палец не перебивается, после
     *  отпускания авто продолжает с новой позиции. */
    fun hubScrollToLine(top: Int) {
        if (!presoHub || state != State.READY || hubLines.isEmpty()) return
        val nt = top.coerceIn(0, hubMaxTop)
        if (nt == hubTop) return
        hubTop = nt
        hubPosPx = nt * 27f
        hubLastSentPx = hubPosPx
        hubSubPx = 0
        sendHubWindow(gap = 100L)
        if (hubAuto && !presoPaused) {
            handler.removeCallbacks(hubAutoTick)
            handler.postDelayed(hubAutoTick, hubIntervalMs() + 800L)
        }
    }

    // Отложенная отправка кадра прокрутки пальцем: если BLE занят, шлём СВЕЖУЮ позицию,
    // когда канал освободится (коалесинг — очередь не копится, хвост не отстаёт).
    private val hubSendLatest: Runnable = object : Runnable {
        override fun run() {
            if (!presoHub || !presoActive || state != State.READY) return
            if (synchronized(outboxLock) { draining }) {
                handler.postDelayed(this, 80L)
            } else {
                sendHubWindow(gap = 50L)
            }
        }
    }

    /**
     * НЕПРЕРЫВНАЯ прокрутка пальцем: позиция как доля 0..1 с СУБ-СТРОЧНОЙ точностью
     * (пиксели, не строки). Кадры коалесируются под темп BLE — палец не обгоняет канал.
     */
    fun hubScrollToFraction(frac: Float) {
        if (!presoHub || state != State.READY || hubLines.isEmpty()) return
        val maxPx = hubMaxTop * 27f
        var npx = frac.coerceIn(0f, 1f) * maxPx
        // ПРИЛИПАНИЕ К КРАЯМ: у самого конца/начала фильтр шума съедал последние пиксели —
        // позиция застревала в 1-2px от максимума, int(pos/27) давал окно на строку раньше,
        // и последняя строка не доматывалась. Крайние позиции применяем всегда и точно.
        if (frac >= 0.995f) npx = maxPx
        if (frac <= 0.005f) npx = 0f
        val edge = npx <= 0f || npx >= maxPx
        if (edge) {
            if (npx == hubPosPx) return
        } else if (kotlin.math.abs(npx - hubPosPx) < 3f) return   // сдвиг меньше 3px — шум
        hubPosPx = npx
        hubTop = (npx / 27f).toInt().coerceIn(0, hubMaxTop)
        hubSubPx = (npx - hubTop * 27f).toInt()
        hubLastSentPx = npx   // авто продолжит с этой позиции без рывка
        if (synchronized(outboxLock) { draining }) {
            handler.removeCallbacks(hubSendLatest)
            handler.postDelayed(hubSendLatest, 80L)
        } else {
            sendHubWindow(gap = 50L)
        }
        if (hubAuto && !presoPaused) {
            handler.removeCallbacks(hubAutoTick)
            handler.postDelayed(hubAutoTick, hubIntervalMs() + 800L)
        }
    }

    /** Сдвинуть EvenHub-показ на [delta] строк. */
    fun hubScrollBy(delta: Int) = hubScrollToLine(hubTop + delta)

    /**
     * Запомнить сценарий и настройки из UI. Тап по тачбару очков (NUS-жест или
     * телеметрия 0x0601) запускает этот сценарий — телефон можно не доставать.
     */
    fun prepare(text: String, mode: ScrollMode, speed: Float, big: Boolean) {
        preparedText = text
        preparedMode = mode
        preparedSpeed = speed
        preparedBig = big
    }

    /**
     * Список скриптов для выбора С ОЧКОВ (заголовок, текст) + режим/скорость/размер.
     * Свайп по тачбару листает список, тап запускает выбранный. Текущий индекс
     * сохраняется между обновлениями списка.
     */
    fun prepareScripts(scripts: List<Pair<String, String>>, mode: ScrollMode, speed: Float, big: Boolean) {
        preparedScripts = scripts
        preparedMode = mode
        preparedSpeed = speed
        preparedBig = big
        if (preparedIndex >= scripts.size) preparedIndex = 0
        if (scripts.isNotEmpty()) preparedText = scripts[preparedIndex].second
    }

    /** Пролистнуть выбор скрипта (свайп по тачбару очков). Сообщаем UI через событие. */
    private fun cycleScript() {
        if (preparedScripts.size < 2) return
        preparedIndex = (preparedIndex + 1) % preparedScripts.size
        val (title, body) = preparedScripts[preparedIndex]
        preparedText = body
        Log.i(TAG, "Очки: выбран скрипт #$preparedIndex «$title»")
        _glassesEvents.tryEmit("picked:$preparedIndex:$title")
    }

    // ---------------------------------------------------------------------------------------------
    // МЕНЮ ВЫБОРА ТЕКСТА НА ОЧКАХ (после запуска нашего пункта из меню очков, событие cmd=17).
    // Отдельная EvenHub-страница со списком заголовков: свайп двигает выбор, одиночный тап —
    // старт выбранного, двойной — на домашний экран. Старт переходит в показ через rebuild
    // (страница меню уже создана — второй create был бы no-op).
    // ---------------------------------------------------------------------------------------------

    /** Выбор нашего пункта в меню очков (cmd=17) — открываем меню выбора текста. */
    private fun onMenuSelected(appId: Int) {
        Log.i(TAG, "Меню очков: выбран appId=$appId (наш=$menuAppId)")
        if (appId != menuAppId) return
        handler.post { openScriptMenu() }
    }

    /**
     * Текст страницы-меню: локализованный заголовок «Выберите текст», окно заголовков
     * вокруг выбранного (маркер «> ») и предполагаемое время чтения выбранного в авто-режиме.
     * Язык меню — как выбран в приложении (locStr → выбранная локаль).
     */
    private fun scriptMenuContent(): String {
        val heading = locStr(R.string.menu_select_text)
        if (preparedScripts.isEmpty()) return heading + "\n" + locStr(R.string.menu_no_texts)
        // Заголовок сверху и строка времени снизу забирают по строке из окна.
        val rows = (hubWindowLines - 2).coerceAtLeast(1)
        val n = preparedScripts.size
        val start = (preparedIndex - rows / 2).coerceIn(0, (n - rows).coerceAtLeast(0))
        val end = minOf(start + rows, n)
        val list = (start until end).joinToString("\n") { idx ->
            val title = preparedScripts[idx].first.ifBlank { locStr(R.string.menu_untitled) }.take(36)
            (if (idx == preparedIndex) "> " else "  ") + title
        }
        // Время прочтения выбранного текста при текущей скорости (WPM ≥ 40 — значит задан).
        val body = preparedScripts[preparedIndex].second
        val time = if (preparedSpeed >= 40f) ReadingTime.shortLabel(body, preparedSpeed) else ""
        val footer = if (time.isNotEmpty()) "\n" + locStr(R.string.est_time_label).format(time) else ""
        return heading + "\n" + list + footer
    }

    /** Открыть на очках меню выбора текста (создаёт EvenHub-страницу со списком). */
    private fun openScriptMenu() {
        if (state != State.READY || !authDone) return
        handler.removeCallbacks(hubAutoTick)
        synchronized(outboxLock) { outbox.clear() }
        presoHub = true; presoActive = true; presoPaused = false; hubAuto = false
        hubMenuMode = true
        if (preparedIndex >= preparedScripts.size) preparedIndex = 0
        hubLastSentTop = -1; hubLastSentSub = -1; hubHbCount = 0
        Log.i(TAG, "Меню выбора текста на очках: ${preparedScripts.size} пунктов")
        enqueueSingle(80L) { s, m -> EvenG2Protocol.buildDisplayWake(s, m) }
        enqueueSingle(80L) { s, m -> EvenG2Protocol.buildBrightness(s, m, DEFAULT_BRIGHTNESS) }
        enqueueSingle(300L) { s, m -> EvenG2Protocol.buildDisplayConfig(s, m) }
        enqueueHubMsg(gap = 350L) { s, m ->
            EvenG2Protocol.buildEvenHubCreate(s, m, scriptMenuContent(), presoLineByteLimit, 0, hubNarrow)
        }
        report(locStr(R.string.st_showing), ready = true)
        _glassesEvents.tryEmit("menu")
        handler.removeCallbacks(hubKeepaliveTick)
        handler.postDelayed(hubKeepaliveTick, 1_200L)
    }

    /** Сдвинуть выбор в меню (свайп) и перерисовать список. */
    private fun moveScriptMenu(delta: Int) {
        if (!hubMenuMode || preparedScripts.isEmpty()) return
        preparedIndex = (preparedIndex + delta).coerceIn(0, preparedScripts.size - 1)
        preparedText = preparedScripts[preparedIndex].second
        Log.i(TAG, "Меню: #$preparedIndex «${preparedScripts[preparedIndex].first}»")
        _glassesEvents.tryEmit("picked:$preparedIndex:${preparedScripts[preparedIndex].first}")
        hubLastSentTop = -1
        enqueueHubMsg(gap = 120L) { s, m ->
            EvenG2Protocol.buildEvenHubRebuild(s, m, scriptMenuContent(), presoLineByteLimit, 0, hubNarrow, 0)
        }
    }

    /** Запустить выбранный в меню текст (одиночный тап) — переход в показ через rebuild. */
    private fun startSelectedScript() {
        if (preparedScripts.isEmpty()) { exitToHome(); return }
        val (title, body) = preparedScripts[preparedIndex]
        if (body.isBlank()) { exitToHome(); return }
        hubMenuMode = false
        preparedText = body   // сырой — для синка с телефоном (превью применит политику само)
        // Для очков применяем политику тарифа (лимит слов + промо) один раз.
        val shown = ContentPolicy.apply(LocaleManager.wrap(this), body).text
        presoMode = preparedMode; presoBig = preparedBig; presoSourceText = shown
        Log.i(TAG, "Меню: запуск «$title»")
        _glassesEvents.tryEmit("started")
        val auto = preparedMode == ScrollMode.APP_AUTO || preparedMode == ScrollMode.GLASSES_AUTO
        startEvenHub(shown, preparedSpeed, auto = auto, pageExists = true)
    }

    /** Отправить один кадр через очередь, продвинув счётчики seq/msg контента. */
    private fun enqueueSingle(gap: Long = 60L, build: (Int, Int) -> ByteArray) {
        if (state != State.READY) return
        val pkt = build(contentSeq, contentMsg)
        contentSeq = (contentSeq + 1) and 0xFF
        contentMsg = (contentMsg + 1) and 0xFF   // msgId — БАЙТ с заворотом (как MentraOS):
        // при переходе 255→256 варинт стал бы 2-байтным (80 02), и прошивка НАГЛУХО клинит
        // EvenHub-обработчик (проверено btsnoop 2026-07-14 — это и была «смерть на 63с»).
        enqueue(listOf(EvenG2Protocol.Item(pkt, gap)))
    }

    /** Скорость чтения в WPM (слов/мин). Строк/сек считаем из реального числа слов на строку
     *  текущего текста — точный, привязанный к содержимому темп. Меняется на лету. */
    fun setScrollSpeed(wpm: Float) {
        presoWpm = wpm.coerceIn(40f, 400f)
        recomputeLps()
        updateScrollInterval()
    }

    /** Пересчитать строк/сек из WPM и среднего числа слов в строке загруженного текста. */
    private fun recomputeLps() {
        val lines = if (hubLines.isNotEmpty()) hubLines else presoLines
        val words = lines.sumOf { line -> line.trim().split(Regex("\\s+")).count { it.isNotBlank() } }
        val n = lines.size.coerceAtLeast(1)
        val wordsPerLine = (words.toFloat() / n).coerceAtLeast(0.8f)
        presoLps = ((presoWpm / 60f) / wordsPerLine).coerceIn(0.02f, 20f)
    }

    /**
     * Сменить размер текста «на лету» (крупный A ↔ мелкий B). В авто-режиме размер
     * применится со следующим кадром (каждый кадр — полный показ с текущим presoBig).
     * В ручном шлём одиночный init-кадр с новым пресетом, без пере-отправки контента.
     */
    fun setTextSize(big: Boolean) {
        presoBig = big
        if (state != State.READY || !presoActive) return
        if (presoHub) return   // EvenHub: шрифт фиксирован прошивкой (LVGL), размера нет
        if (presoMode == ScrollMode.APP_AUTO) return   // применится со следующим авто-кадром
        if (synchronized(outboxLock) { draining }) return
        val totalLines = presoLines.size.coerceAtLeast(1)
        val built = EvenG2Protocol.buildInitOnly(
            contentSeq, contentMsg, totalLines, big = big, manualMode = true
        )
        contentSeq = built.nextSeq
        contentMsg = built.nextMsg
        Log.i(TAG, "Смена размера текста → ${if (big) "крупный (A)" else "мелкий (B)"}")
        enqueue(built.items)
    }

    // ---------------------------------------------------------------------------------------------
    // Жизненный цикл
    // ---------------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        val notif = buildNotif(locStr(R.string.st_scanning))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground не удался: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        autoReconnect = true
        if (state == State.IDLE) startScan()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        autoReconnect = false
        cleanup()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Разрешения и адаптер
    // ---------------------------------------------------------------------------------------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        else
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        else
            true

    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private fun BluetoothDevice.safeName(): String? =
        try { name } catch (e: SecurityException) { null }

    private fun stopScanSafely(scanner: BluetoothLeScanner?) {
        scanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при stopScan: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Сканирование (ищем дужку, предпочтительно левую `_L_`)
    // ---------------------------------------------------------------------------------------------

    private fun startScan() {
        if (state != State.IDLE) return

        if (!hasScanPermission()) {
            Log.e(TAG, "Нет разрешения на сканирование BLE — стоп")
            report(locStr(R.string.st_no_permission))
            return
        }
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Bluetooth недоступен"); scheduleReconnect(); return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner недоступен (Bluetooth выключен?)"); scheduleReconnect(); return
        }

        tearingDown = false
        candidate = null
        leftDevice = null
        rightDevice = null
        state = State.SCANNING
        Log.i(TAG, "Сканируем '$DEVICE_MATCH…' (обе дужки)")
        report(locStr(R.string.st_scanning))

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при startScan: ${e.message}")
            state = State.IDLE; scheduleReconnect(); return
        }

        handler.postDelayed({
            if (state == State.SCANNING) {
                stopScanSafely(scanner)
                if (leftDevice != null || rightDevice != null) {
                    Log.w(TAG, "Тайм-аут скана: подключаемся к найденному (L=${leftDevice!=null} R=${rightDevice!=null})")
                    connectBoth()
                } else {
                    Log.w(TAG, "Очки не найдены, повтор через ${RECONNECT_DELAY_MS / 1000}с")
                    state = State.IDLE; scheduleReconnect()
                }
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (state != State.SCANNING) return
            val name = result.device.safeName() ?: return
            if (!name.contains(DEVICE_MATCH)) return

            when {
                name.contains("_L_") && leftDevice == null -> {
                    Log.i(TAG, "Найдена левая дужка: '$name'")
                    leftDevice = result.device
                }
                name.contains("_R_") && rightDevice == null -> {
                    Log.i(TAG, "Найдена правая дужка: '$name'")
                    rightDevice = result.device
                }
                !name.contains("_L_") && !name.contains("_R_") && candidate == null -> {
                    candidate = result.device
                }
            }
            // Как только нашли ОБЕ дужки — подключаемся сразу (не ждём тайм-аута).
            if (leftDevice != null && rightDevice != null) {
                stopScanSafely(bluetoothAdapter?.bluetoothLeScanner)
                handler.removeCallbacksAndMessages(null)
                connectBoth()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Сканирование провалилось: errorCode=$errorCode")
            state = State.IDLE; scheduleReconnect()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Подключение
    // ---------------------------------------------------------------------------------------------

    /** Подключаемся к ОБЕИМ дужкам (как офиц. приложение). Левая — мастер (auth+контент),
     *  правая — просто должна быть подключена, иначе очки не завершают авторизацию. */
    private fun connectBoth() {
        if (!hasConnectPermission()) {
            teardownAndReconnect("Нет разрешения BLUETOOTH_CONNECT")
            return
        }
        state = State.CONNECTING
        lReady = false
        authStarted = false
        // Если левой нет — берём запасной вариант как «левую» (мастер).
        val left = leftDevice ?: candidate
        val right = rightDevice
        rReady = (right == null)   // если правой нет — не ждём её
        report(locStr(R.string.st_connecting))
        try {
            if (left != null) {
                Log.i(TAG, "Подключаемся к левой [${left.address}]")
                gatt = left.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            if (right != null) {
                Log.i(TAG, "Подключаемся к правой [${right.address}]")
                gattR = right.connectGatt(this, false, gattCallbackR, BluetoothDevice.TRANSPORT_LE)
            }
        } catch (e: SecurityException) {
            teardownAndReconnect("SecurityException при connectGatt: ${e.message}")
        }
    }

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        gatt.services.forEach { svc ->
            svc.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState state=$state")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                teardownAndReconnect("Ошибка соединения: status=$status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT подключён → запрашиваем MTU 512")
                    try {
                        gatt.requestMtu(512)
                    } catch (e: SecurityException) {
                        teardownAndReconnect("SecurityException requestMtu: ${e.message}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    teardownAndReconnect("Очки отключились")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@BleService.mtu = mtu
            Log.i(TAG, "MTU=$mtu (статус=$status) → discoverServices")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                teardownAndReconnect("SecurityException discoverServices: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                teardownAndReconnect("discoverServices fail: status=$status")
                return
            }

            val wc = findCharacteristic(gatt, CHAR_WRITE) ?: run {
                Log.e(TAG, "=== Найденные сервисы/характеристики ===")
                gatt.services.forEach { s ->
                    Log.e(TAG, "Сервис ${s.uuid}")
                    s.characteristics.forEach { c -> Log.e(TAG, "  └─ ${c.uuid} props=0x${c.properties.toString(16)}") }
                }
                teardownAndReconnect("Характеристика записи 0x5401 не найдена")
                return
            }
            writeChar = wc
            Log.i(TAG, "Char записи 0x5401 найдена, props=0x${wc.properties.toString(16)}")

            val notify = findCharacteristic(gatt, CHAR_NOTIFY) ?: run {
                teardownAndReconnect("Характеристика нотификаций 0x5402 не найдена")
                return
            }

            // NUS-канал жестов — опциональный (без него просто нет управления с тачбара).
            nusCharL = findCharacteristic(gatt, NUS_RX)
            Log.i(TAG, "Левая: NUS ${if (nusCharL != null) "найден" else "отсутствует"}")

            Log.i(TAG, "Включаем нотификации (0x5402)")
            try {
                gatt.setCharacteristicNotification(notify, true)
            } catch (e: SecurityException) {
                teardownAndReconnect("SecurityException setCharacteristicNotification: ${e.message}")
                return
            }
            val descriptor = notify.getDescriptor(CCCD) ?: run {
                teardownAndReconnect("CCCD не найден")
                return
            }
            writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // CCCD пишутся строго по одному: сначала 0x5402, затем (опционально) NUS.
            when (descriptor.characteristic.uuid) {
                CHAR_NOTIFY -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        teardownAndReconnect("Ошибка записи CCCD (L): status=$status")
                        return
                    }
                    Log.i(TAG, "Левая дужка: нотификации включены")
                    val nus = nusCharL
                    if (nus != null && enableNotify(gatt, nus)) return  // ждём CCCD NUS
                    lReady = true
                    maybeStartAuth()
                }
                NUS_RX -> {
                    Log.i(TAG, "Левая дужка: NUS-жесты ${if (status == BluetoothGatt.GATT_SUCCESS) "включены" else "недоступны (status=$status)"}")
                    lReady = true
                    maybeStartAuth()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Пейсинг таймерный — подтверждение только логируем.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite status=$status (не SUCCESS)")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Левая — МАСТЕР (пишем в неё): основной разбор ответов/событий.
            if (characteristic.uuid == NUS_RX) handleNusEvent(value) else handleRx(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                if (characteristic.uuid == NUS_RX) handleNusEvent(value) else handleRx(value)
            }
        }
    }

    /** Минимальный колбэк ПРАВОЙ дужки: только подключение + включение нотификаций.
     *  Весь протокол (auth, контент) идёт через левую; правая должна быть просто на связи. */
    private val gattCallbackR = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                teardownAndReconnect("Правая: ошибка соединения status=$status"); return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    try { g.requestMtu(512) } catch (e: SecurityException) { teardownAndReconnect("R requestMtu: ${e.message}") }
                BluetoothProfile.STATE_DISCONNECTED ->
                    teardownAndReconnect("Правая дужка отключилась")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            try { g.discoverServices() } catch (e: SecurityException) { teardownAndReconnect("R discover: ${e.message}") }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { teardownAndReconnect("R discover fail: $status"); return }
            val notify = findCharacteristic(g, CHAR_NOTIFY) ?: run { teardownAndReconnect("R: notify 5402 не найдена"); return }
            writeCharR = findCharacteristic(g, CHAR_WRITE) ?: run { teardownAndReconnect("R: запись 5401 не найдена"); return }
            nusCharR = findCharacteristic(g, NUS_RX)
            Log.i(TAG, "Правая: NUS ${if (nusCharR != null) "найден" else "отсутствует"}")
            try { g.setCharacteristicNotification(notify, true) }
            catch (e: SecurityException) { teardownAndReconnect("R setNotify: ${e.message}"); return }
            val cccd = notify.getDescriptor(CCCD) ?: run { teardownAndReconnect("R: CCCD не найден"); return }
            writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            when (descriptor.characteristic.uuid) {
                CHAR_NOTIFY -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) { teardownAndReconnect("R: ошибка CCCD status=$status"); return }
                    Log.i(TAG, "Правая дужка: нотификации включены")
                    val nus = nusCharR
                    if (nus != null && enableNotify(g, nus)) return  // ждём CCCD NUS
                    rReady = true
                    maybeStartAuth()
                }
                NUS_RX -> {
                    Log.i(TAG, "Правая дужка: NUS-жесты ${if (status == BluetoothGatt.GATT_SUCCESS) "включены" else "недоступны (status=$status)"}")
                    rReady = true
                    maybeStartAuth()
                }
            }
        }
        // Правая — вторичная: только лог + события 0601/жесты.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == NUS_RX) handleNusEvent(value) else handleRxSecondary(value)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = c.value ?: return
                if (c.uuid == NUS_RX) handleNusEvent(value) else handleRxSecondary(value)
            }
        }
    }

    /** Прямая запись в ЛЕВУЮ дужку (вне очереди; очередь пишет в правую-мастер). */
    @Suppress("DEPRECATION")
    private fun writeToLeft(value: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = value
                g.writeCharacteristic(ch)
            }
            Log.d(TAG, "→L ${value.size}б")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writeToLeft: ${e.message}")
        }
    }

    /** Включить нотификации на характеристике; true = запись CCCD запущена (ждём колбэк). */
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        return try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: return false
            writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при enableNotify: ${e.message}")
            false
        }
    }

    /** Уведомления ЛЕВОЙ (вторичной) дужки: лог + события 0x0601 / 0xE0-01. */
    private fun handleRxSecondary(value: ByteArray) {
        if (value.size < 10 || (value[0].toInt() and 0xFF) != 0xAA) return
        Log.d(TAG, "←L %02x%02x %s".format(
            value[6].toInt() and 0xFF, value[7].toInt() and 0xFF,
            value.joinToString("") { "%02x".format(it) }))
        if (value.size >= 11 &&
            (value[6].toInt() and 0xFF) == 0x06 && (value[7].toInt() and 0xFF) == 0x01
        ) {
            handleGlassesEvent(value)
        }
        if (value.size >= 11 &&
            (value[6].toInt() and 0xFF) == 0xE0 && (value[7].toInt() and 0xFF) == 0x01
        ) {
            handleHubEvent(value)
        }
    }

    /**
     * События EvenHub-страницы (0xE0-01): жесты по контейнеру с isEventCapture=1.
     * ДВА конверта внутри f13 (0x6a) — подтверждено логами на железе 2026-07-10:
     *   textEvent f2 (0x12): `08 <cid> 12 <len> <имя> 18 <type>` — СВАЙПЫ по тачбару
     *     (type: 1=к началу/назад, 2=к концу/вперёд);
     *   sysEvent  f3 (0x1a): `08 <type> …` (3=двойной тап, 7=страница скрыта).
     */
    private fun handleHubEvent(value: ByteArray) {
        val payload = value.copyOfRange(8, value.size - 2)
        // cmd=17 — выбор нашего пункта в МЕНЮ ОЧКОВ: `08 11 … a2 01 { 08 <appId> }` (f20={appId}).
        // Приходит с обеих дужек — запускает наш вход (меню выбора текста на очках).
        if (payload.size >= 4 && (payload[0].toInt() and 0xFF) == 0x08 &&
            (payload[1].toInt() and 0xFF) == 0x11) {
            var p = 2
            while (p + 2 < payload.size) {
                if ((payload[p].toInt() and 0xFF) == 0xA2 && (payload[p + 1].toInt() and 0xFF) == 0x01) {
                    val end = minOf(p + 3 + (payload[p + 2].toInt() and 0xFF), payload.size)
                    var v = 0; var shift = 0; var r = p + 4
                    while (r < end) {
                        val b = payload[r].toInt() and 0xFF
                        v = v or ((b and 0x7F) shl shift); shift += 7; r++
                        if (b < 0x80) break
                    }
                    onMenuSelected(v)
                    return
                }
                p++
            }
            return
        }
        var type = -1
        var i = 0
        while (i + 1 < payload.size) {
            if ((payload[i].toInt() and 0xFF) == 0x6A) {
                val outerEnd = minOf(i + 2 + (payload[i + 1].toInt() and 0xFF), payload.size)
                var j = i + 2
                while (j + 1 < outerEnd) {
                    val tag = payload[j].toInt() and 0xFF
                    if (tag == 0x12 || tag == 0x1A) {
                        val subEnd = minOf(j + 2 + (payload[j + 1].toInt() and 0xFF), outerEnd)
                        // sysEvent (0x1A) БЕЗ явного f1 = одиночный CLICK (protobuf опускает f1=0).
                        if (tag == 0x1A && type == -1) type = 0
                        var k = j + 2
                        while (k + 1 < subEnd) {
                            when (payload[k].toInt() and 0xFF) {
                                0x08 -> { if (tag == 0x1A) type = payload[k + 1].toInt() and 0xFF; k += 2 }
                                0x18 -> { type = payload[k + 1].toInt() and 0xFF; k += 2 }   // textEvent type
                                0x10, 0x20 -> k += 2
                                0x12 -> k += 2 + (payload[k + 1].toInt() and 0xFF)           // имя — пропустить
                                else -> k++
                            }
                        }
                        j = subEnd
                    } else j++
                }
                break
            }
            i++
        }
        Log.i(TAG, "EvenHub-событие type=$type (${payload.joinToString(" ") { "%02x".format(it) }})")
        handler.post {
            // РЕЖИМ МЕНЮ ВЫБОРА ТЕКСТА (после запуска с очков): свайп двигает выбор, тап — старт.
            if (hubMenuMode) {
                when (type) {
                    0 -> startSelectedScript()                // одиночный тап = запустить выбранный
                    1 -> moveScriptMenu(-1)                    // свайп назад = выше по списку
                    2 -> moveScriptMenu(1)                     // свайп вперёд = ниже по списку
                    3 -> exitToHome()                          // двойной тап = на домашний экран
                }
                return@post
            }
            if (!presoHub || !presoActive) return@post
            when (type) {
                0 -> performGesture(Customization.action(this@BleService, Customization.Gesture.TAP))
                1 -> performGesture(Customization.action(this@BleService, Customization.Gesture.SWIPE_BACK))
                2 -> performGesture(Customization.action(this@BleService, Customization.Gesture.SWIPE_FWD))
                3 -> performGesture(Customization.action(this@BleService, Customization.Gesture.DOUBLE))
                7 -> reestablishHub()                         // SYSTEM_EXIT — восстановить показ
            }
        }
    }

    /** Выход показа на домашний экран очков (двойной тап). */
    private fun exitToHome() {
        Log.i(TAG, "Очки: двойной тап — выход на домашний экран")
        hubMenuMode = false
        stopPresentation()
        _glassesEvents.tryEmit("stopped")
    }

    /** SYSTEM_EXIT (type=7): очки забрали передний план (пользователь открыл дашборд/другое
     *  приложение) и убили нашу EvenHub-страницу. Штатной «смерти на 63с» больше нет — она
     *  была багом переполнения msgId (см. enqueueSingle). Здесь восстанавливаемся при
     *  ГЕНУИННОМ SYSTEM_EXIT: страница УЖЕ мертва, поэтому не нужны shutdown/wake/brightness
     *  (экран горит) — только display-config + create заново с той же позиции. Зазор ~250мс. */
    private var lastReestablishMs = 0L
    private fun reestablishHub() {
        if (!presoHub || !presoActive || state != State.READY) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReestablishMs < 1200L) return
        lastReestablishMs = now
        hubBlanked = false
        Log.i(TAG, "SYSTEM_EXIT: пересоздаю показ с позиции top=$hubTop")
        handler.removeCallbacks(hubAutoTick)
        hubLastSentTop = -1; hubLastSentSub = -1
        synchronized(outboxLock) { outbox.clear() }
        enqueueSingle(120L) { s, m -> EvenG2Protocol.buildDisplayConfig(s, m) }
        enqueueHubMsg(gap = 150L) { s, m ->
            EvenG2Protocol.buildEvenHubCreate(
                s, m, hubWindow(hubTop), presoLineByteLimit, hubFilledRows(), hubNarrow
            )
        }
        // Возобновить авто быстро — зазор минимальный.
        if (hubAuto && !presoPaused) handler.postDelayed(hubAutoTick, 500L)
    }

    /** Авторизация ДОСЛОВНО как в референсе teleprompter.py: 7 статических auth-пакетов,
     *  затем контент продолжаем с seq=0x08, msg=0x14. Никакого динамического challenge,
     *  никакого «разогрева» — референс их не делает. */
    private fun maybeStartAuth() {
        if (!lReady || !rReady || authStarted) return
        authStarted = true
        state = State.READY
        authDone = false
        authArmed = false
        lastChallengeMsg = -1

        // 7 статических auth-пакетов (build_auth_packets из референса) через очередь.
        enqueue(EvenG2Protocol.buildAuthItems())
        // Контент начинаем с seq=0x08, msg=0x14 (как в референсе). Далее счётчики растут
        // монотонно на всё подключение (без сброса per-show — иначе повтор msg_id).
        contentSeq = CONTENT_SEQ_START
        contentMsg = CONTENT_MSG_START
        Log.i(TAG, "✅ Авторизация (7 пакетов, референс)…")
        report(locStr(R.string.st_authorizing))

        // Готово после отправки auth (пауза как в референсе ~0.5с + запас).
        handler.postDelayed({
            if (state != State.READY) return@postDelayed
            authDone = true
            report(locStr(R.string.st_connected), ready = true)
            sendBroadcast(Intent(ACTION_READY).setPackage(packageName))
            handler.removeCallbacks(connHeartbeatTick)
            handler.postDelayed(connHeartbeatTick, CONN_HEARTBEAT_MS)
            // Регистрируем пункт «Teleprompter G2» в меню очков — чтобы приложение можно
            // было открыть С ОЧКОВ (выбор пункта → событие cmd=17 → меню выбора текста).
            enqueueSingle(100L) { s, m ->
                EvenG2Protocol.buildMenuInfo(s, m, locStr(R.string.app_name), menuAppId)
            }
            Log.i(TAG, "✅ Готово к показу (menu appId=$menuAppId)")
        }, 1500L)
    }

    /** Разбор нотификаций очков: события тачбара/состояния (0x0601) и auth-challenge (0x8001). */
    private fun handleRx(value: ByteArray) {
        if (value.size >= 10 && (value[0].toInt() and 0xFF) == 0xAA) {
            // Диагностика: канал + весь кадр (ACK 8000/0600/0e00, состояние 0d01 и т.д.)
            Log.d(TAG, "← %02x%02x %s".format(
                value[6].toInt() and 0xFF, value[7].toInt() and 0xFF,
                value.joinToString("") { "%02x".format(it) }))
        }
        if (value.size >= 11 &&
            (value[0].toInt() and 0xFF) == 0xAA && (value[1].toInt() and 0xFF) == 0x12 &&
            (value[6].toInt() and 0xFF) == 0x06 && (value[7].toInt() and 0xFF) == 0x01
        ) {
            handleGlassesEvent(value)
            return
        }
        if (value.size >= 11 &&
            (value[0].toInt() and 0xFF) == 0xAA &&
            (value[6].toInt() and 0xFF) == 0xE0 && (value[7].toInt() and 0xFF) == 0x01
        ) {
            handleHubEvent(value)
            return
        }
        if (value.size >= 12 &&
            (value[0].toInt() and 0xFF) == 0xAA && (value[1].toInt() and 0xFF) == 0x12 &&
            (value[6].toInt() and 0xFF) == 0x80 && (value[7].toInt() and 0xFF) == 0x01 &&
            (value[8].toInt() and 0xFF) == 0x08 && (value[10].toInt() and 0xFF) == 0x10
        ) {
            // payload: 08 04 10 <msg varint> 1a 02 08 01 — msg начинается с индекса 11
            var idx = 11; var shift = 0; var v = 0
            while (idx < value.size) {
                val b = value[idx].toInt() and 0xFF
                v = v or ((b and 0x7F) shl shift)
                idx++
                if (b and 0x80 == 0) break
                shift += 7
            }
            lastChallengeMsg = v
            Log.d(TAG, "auth challenge msg=$v")
            // Отвечаем на challenge МГНОВЕННО: офиц. приложение отвечает за ~28мс,
            // challenge, похоже, быстро «протухает». Без дебаунса, сразу на первый.
            if (authArmed && !authDone) {
                handler.removeCallbacks(completeAuthRunnable)
                handler.post(completeAuthRunnable)
            }
        }
    }

    /**
     * События канала 0x0601 (телеметрия очков). Типы кадров — из btsnoop реальных сессий:
     *   0xa5 + «5a 02 10 <n>» — СВАЙП по тачбару (одиночные кадры с человеческим темпом);
     *   0xa1 + «3a 02 08 04» — телесуфлёр ЗАКРЫТ со стороны очков (state 4 = STOP);
     *   0xa4 + «52 …»        — машинные отчёты дисплея пачками по 8 — игнорируем,
     *                          они приходят и без касаний (иначе были бы ложные старты).
     */
    private fun handleGlassesEvent(value: ByteArray) {
        val payload = value.copyOfRange(8, value.size - 2)
        if (payload.isEmpty() || payload[0].toInt() != 0x08) return
        // Первый varint после 0x08 — тип события.
        var idx = 1; var type = 0; var shift = 0
        while (idx < payload.size) {
            val b = payload[idx].toInt() and 0xFF
            type = type or ((b and 0x7F) shl shift)
            idx++
            if (b and 0x80 == 0) break
            shift += 7
        }
        val hex = payload.joinToString(" ") { "%02x".format(it) }
        // Колбэк BLE приходит в binder-потоке — всё состояние трогаем на главном.
        when (type) {
            0xA5 -> {
                // Направление свайпа — в подсообщении f11 (0x5a), поле f1: 1=вперёд, 2=назад
                // (по btsnoop: «5a 04 08 01 10 02» / «5a 04 08 02 10 03»; отсутствует = 0).
                var dir = 0
                var i = idx
                while (i + 2 < payload.size) {
                    if ((payload[i].toInt() and 0xFF) == 0x5A) {
                        val s = i + 2
                        if ((payload[i + 1].toInt() and 0xFF) >= 2 && s + 1 < payload.size &&
                            (payload[s].toInt() and 0xFF) == 0x08
                        ) dir = payload[s + 1].toInt() and 0xFF
                        break
                    }
                    i++
                }
                Log.i(TAG, "Тачбар: свайп dir=$dir ($hex)")
                handler.post { onTouchSwipe(dir) }
            }
            0xA1 -> if (hex.contains("3a 02 08 04")) {
                // Во время пере-присылки окна (ручная прокрутка) пере-инициализация сама
                // шлёт это «закрытие» — игнорируем, иначе оборвём собственную прокрутку.
                if (presoHub) {
                    // Показ EvenHub-движка телесуфлёрные события не закрывают (это другой
                    // сервис прошивки) — иначе ложный 0xA1 глушил бы прокрутку с тачбара.
                    Log.d(TAG, "0xA1 close при EvenHub-показе — игнор")
                } else if (SystemClock.elapsedRealtime() < suppressCloseUntil) {
                    Log.d(TAG, "0xA1 close во время пере-присылки — игнор")
                } else {
                    Log.i(TAG, "Очки: телесуфлёр закрыт со стороны очков")
                    handler.post {
                        presoActive = false
                        presoPaused = false
                        handler.removeCallbacks(scrollTick)
                        handler.removeCallbacks(keepaliveTick)
                        synchronized(outboxLock) { outbox.clear() }
                        _glassesEvents.tryEmit("stopped")
                    }
                }
            }
            0xA4 -> {
                // Отчёт дисплея «52 02 08 <n>» — текущая страница нативного показа.
                // Используем как позицию для превью/полосы на телефоне.
                var i = idx
                while (i + 3 < payload.size) {
                    if ((payload[i].toInt() and 0xFF) == 0x52 && (payload[i + 1].toInt() and 0xFF) >= 2 &&
                        (payload[i + 2].toInt() and 0xFF) == 0x08
                    ) {
                        nativePage = payload[i + 3].toInt() and 0xFF
                        break
                    }
                    i++
                }
                Log.d(TAG, "0601 отчёт позиции: страница $nativePage ($hex)")
            }
            else -> Log.d(TAG, "0601 type=0x${type.toString(16)} ($hex) — игнор")
        }
    }

    /** Запуск подготовленного сценария по действию с очков. @return true если запустили. */
    private fun startPreparedFromGlasses(): Boolean {
        val text = preparedText ?: return false
        if (text.isBlank()) return false
        Log.i(TAG, "Очки: запуск показа")
        startPresentation(text, preparedMode, preparedSpeed, preparedBig)
        _glassesEvents.tryEmit("started")
        return true
    }

    /** Свайп по тачбару (телеметрия 0x0601). ВО ВРЕМЯ показа — прокрутка с очков в любом
     *  режиме: dir=2 назад, иначе вперёд (по HUB_TOUCH_STEP строк, дебаунс 250мс). Вне
     *  показа — листаем выбор скрипта / запускаем подготовленный; на паузе — продолжить. */
    private fun onTouchSwipe(dir: Int = 0) {
        if (!authDone || state != State.READY) return
        val now = SystemClock.elapsedRealtime()
        if (presoActive && presoHub && !presoPaused) {
            // Прокрутку при EvenHub-показе ведут события 0xE0-01 (textEvent 18 01/18 02) —
            // телеметрия 0xA5 здесь шлёт dir=9/10 с другой семантикой, её игнорируем.
            return
        }
        if (now - lastTouchMs < 1000L) return
        lastTouchMs = now
        when {
            !presoActive -> if (preparedScripts.size > 1) cycleScript() else startPreparedFromGlasses()
            presoPaused -> {
                Log.i(TAG, "Тачбар: продолжить после паузы")
                resumePresentation()
                _glassesEvents.tryEmit("resumed")
            }
            else -> { /* показ не-EvenHub — листает прошивка */ }
        }
    }

    /** Прокрутка С ОЧКОВ с дедупом: свайп может прийти и телеметрией 0xA5, и NUS-жестом,
     *  и событием 0xE0-01 — исполняем не чаще раза в 250мс. */
    private var lastGlassScrollMs = 0L
    private fun hubScrollFromGlasses(delta: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGlassScrollMs < 250L) return
        lastGlassScrollMs = now
        Log.i(TAG, "Очки: прокрутка на $delta строк")
        hubScrollBy(delta)
    }

    /** NUS-жест тачбара: кадр «F5 <код>» (без конверта/CRC, openCFW nus-protocol.md). */
    private fun handleNusEvent(value: ByteArray) {
        if (value.size < 2 || (value[0].toInt() and 0xFF) != 0xF5) return
        val code = value[1].toInt() and 0xFF
        Log.i(TAG, "NUS жест: 0x%02x".format(code))
        handler.post { onGesture(code) }
    }

    /**
     * Жесты (могут прийти с ОБЕИХ дужек — дедуп по коду+времени). ВО ВРЕМЯ показа
     * тачбар принадлежит ПРОШИВКЕ (двойной тап открывает её меню выхода, свайпы
     * листают) — наши действия поверх конфликтовали с ней. Мы реагируем только на
     * тап при ОСТАНОВЛЕННОМ показе — запуск подготовленного сценария без телефона.
     */
    private fun onGesture(code: Int) {
        if (!authDone || state != State.READY) return
        val now = SystemClock.elapsedRealtime()
        if (code == lastGestureCode && now - lastGestureMs < 500L) return
        lastGestureCode = code
        lastGestureMs = now
        // ВО ВРЕМЯ EvenHub-показа слайды по тачбару = прокрутка с очков (любой режим).
        if (presoActive && presoHub && !presoPaused) {
            when (code) {
                GESTURE_SLIDE_FWD  -> hubScrollFromGlasses(HUB_TOUCH_STEP)
                GESTURE_SLIDE_BACK -> hubScrollFromGlasses(-HUB_TOUCH_STEP)
            }
            return
        }
        if (code == GESTURE_SINGLE_TAP && !presoActive) {
            startPreparedFromGlasses()
        }
    }

    /** Кадр2 авторизации + «взвод» ожидания challenge (вызывается через ~2.3с после кадра1). */
    private fun sendAuthFrame2() {
        if (state != State.READY || authDone) return
        enqueue(listOf(EvenG2Protocol.Item(EvenG2Protocol.buildAuth8000(0x02, 0x04), 100L)))
        authArmed = true
        // Фолбэк: если очки почему-то не пришлют challenge — всё равно попробуем завершить.
        handler.postDelayed(completeAuthRunnable, 2500L)
    }

    /**
     * Завершение авторизации: кадр3 (msg = challenge от очков) и кадр4 (challenge+1) на
     * 0x8020. Дальше контент продолжаем с challenge+2 (монотонно). Синхронизация msg по
     * challenge обязательна — иначе очки не подтверждают эти кадры и телесуфлёр молчит.
     */
    private fun completeAuth() {
        if (state != State.READY || authDone || !authArmed) return
        val cm = if (lastChallengeMsg in 0..0x7FFFFF) lastChallengeMsg else 0x05
        Log.i(TAG, "Завершение авторизации: challenge msg=$cm")
        enqueue(listOf(
            EvenG2Protocol.Item(EvenG2Protocol.buildAuth8020c(0x03, cm), 200L),
            EvenG2Protocol.Item(EvenG2Protocol.buildAuth8020d(0x04, cm + 1), 200L)
        ))
        contentSeq = 0x05
        contentMsg = (cm + 2) and 0xFF
        authDone = true
        authArmed = false
        // РАЗОГРЕВ УСТРОЙСТВА как официалка: device-info, яркость, дашборд, погода,
        // конфиг дисплея. БЕЗ него страницы подтверждаются и state=6 приходит, но
        // экран ЧЁРНЫЙ (пользователь: наше приложение показывало текст только ПОСЛЕ
        // сессии официалки — та разогревала очки за нас). Шлём один раз при подключении.
        val di = EvenG2Protocol.buildDeviceInitItems(contentSeq, contentMsg)
        contentSeq = di.nextSeq
        contentMsg = di.nextMsg
        enqueue(di.items)
        // Яркость поверх разогрева — гарантируем светящийся экран для показа.
        enqueueSingle(80L) { s, m -> EvenG2Protocol.buildBrightness(s, m, DEFAULT_BRIGHTNESS) }
        report(locStr(R.string.st_connected), ready = true)
        sendBroadcast(Intent(ACTION_READY).setPackage(packageName))
        // Держим связь живой и в простое между показами.
        handler.removeCallbacks(connHeartbeatTick)
        handler.postDelayed(connHeartbeatTick, CONN_HEARTBEAT_MS)
    }

    // ---------------------------------------------------------------------------------------------
    // Пейсинг-очередь: следующий пакет — только после подтверждения предыдущего
    // ---------------------------------------------------------------------------------------------

    private fun enqueue(items: List<EvenG2Protocol.Item>) {
        if (items.isEmpty()) return
        synchronized(outboxLock) {
            outbox.addAll(items)
            if (!draining) {
                draining = true
                handler.post(drainStep)
            }
        }
    }

    private val drainStep = Runnable { drainNext() }

    private fun drainNext() {
        val item = synchronized(outboxLock) {
            if (outbox.isEmpty()) { draining = false; null } else outbox.first()
        }
        if (item == null) {
            Log.i(TAG, "✅ Очередь отправлена полностью")
            if (state == State.READY && authDone) report(locStr(R.string.st_connected), ready = true)
            return
        }
        if (writeNoResponse(item.packet)) {
            val pkt = item.packet
            if (pkt.size > 9) {
                Log.d(TAG, "→ %02x%02x t=%02x ${pkt.size}б".format(
                    pkt[6].toInt() and 0xFF, pkt[7].toInt() and 0xFF, pkt[9].toInt() and 0xFF))
            }
            // Пакет принят стеком — снимаем его и ждём паузу «как в эталоне».
            synchronized(outboxLock) { if (outbox.isNotEmpty()) outbox.removeFirst() }
            retryCount = 0
            handler.postDelayed(drainStep, item.gapMs.coerceAtLeast(45L))
        } else {
            // Стек занят — повторяем ТОТ ЖЕ пакет (пропуск = кадр с дыркой,
            // который очки молча игнорируют).
            retryCount++
            if (retryCount > WRITE_MAX_RETRY) {
                Log.e(TAG, "Пакет не ушёл после $retryCount попыток — пропускаем")
                synchronized(outboxLock) { if (outbox.isNotEmpty()) outbox.removeFirst() }
                retryCount = 0
            }
            handler.postDelayed(drainStep, WRITE_RETRY_MS)
        }
    }

    /** Пишем в ЛЕВУЮ дужку (характеристика 0x5401) — как референс teleprompter.py
     *  по умолчанию (левый глаз).
     *  @return true, если стек ПРИНЯЛ пакет (или слать некуда — чтобы не зациклиться). */
    @Suppress("DEPRECATION")
    private fun writeNoResponse(value: ByteArray): Boolean {
        val g = gatt ?: return true
        val ch = writeChar ?: return true
        if (value.size > mtu - 3) {
            Log.w(TAG, "⚠ Пакет ${value.size}б > MTU-3 (${mtu - 3}) — может не дойти")
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                if (res != BluetoothStatusCodes.SUCCESS) Log.w(TAG, "  write ${value.size}б → res=$res (повтор)")
                res == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = value
                val ok = g.writeCharacteristic(ch)
                if (!ok) Log.w(TAG, "  write ${value.size}б → false (повтор)")
                ok
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при writeNoResponse: ${e.message}")
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value)
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при writeDescriptor: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Teardown / reconnect / уведомления
    // ---------------------------------------------------------------------------------------------

    private fun teardownAndReconnect(reason: String) {
        if (tearingDown) return
        tearingDown = true
        Log.w(TAG, "Teardown: $reason")
        cleanup()
        report(locStr(R.string.st_disconnected))
        sendBroadcast(Intent(ACTION_DISCONNECTED).setPackage(packageName))
        scheduleReconnect()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        presoActive = false
        presoPaused = false
        authDone = false
        lastChallengeMsg = -1
        synchronized(outboxLock) {
            outbox.clear()
            draining = false
        }
        retryCount = 0
        try {
            gatt?.close()
            gattR?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при gatt.close: ${e.message}")
        }
        gatt = null
        gattR = null
        writeChar = null
        writeCharR = null
        nusCharL = null
        nusCharR = null
        candidate = null
        leftDevice = null
        rightDevice = null
        lReady = false
        rReady = false
        authStarted = false
        mtu = 23
        state = State.IDLE
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        Log.i(TAG, "Переподключение через ${RECONNECT_DELAY_MS / 1000}с…")
        handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
    }

    /** Строка в ВЫБРАННОЙ в приложении локали (сервис иначе берёт системную). */
    private fun locStr(resId: Int): String = LocaleManager.wrap(this).getString(resId)

    private fun report(text: String, ready: Boolean = false) {
        _connState.value = ConnState(text, ready)
        updateNotif(text)
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, text))
    }

    private fun buildNotif(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BLE Teleprompter", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Тап по уведомлению — открыть приложение.
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            piFlags
        )
        // Кнопка «Отключить» — открывает MainActivity с ACTION_DISCONNECT, который вызывает
        // тот же проверенный disconnectGlasses(), что и кнопка в приложении. Через Activity
        // (а не startService) — надёжно на Android 14, где фоновый старт сервиса ограничен.
        val disconnectPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_DISCONNECT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisprompt")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    locStr(R.string.disconnect), disconnectPi
                ).build()
            )
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }
}
