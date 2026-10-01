package com.example.teleprompter

/**
 * Протокол дисплея очков Even Realities **G2**.
 *
 * G2 НЕ использует простой G1-протокол (Nordic UART + команда 0x4E). Это другой,
 * кадрированный протокол поверх кастомного GATT-сервиса. Формат портирован 1:1 из
 * рабочего примера-телесуфлёра проекта i-soxi/even-g2-protocol (examples/teleprompter).
 *
 * Запись идёт в характеристику 0x5401, нотификации — 0x5402 (см. [BleService]):
 *   UUID = 00002760-08c2-11e1-9073-0e8ac72e54xx
 *
 * Кадр пакета:
 *   [0xAA, 0x21, seq, len(payload)+2, 0x01, 0x01, svc_hi, svc_lo] + payload + CRC16_LE
 *   CRC-16/CCITT (init=0xFFFF, poly=0x1021) считается ТОЛЬКО по payload (после 8-байтового заголовка).
 *
 * Чтобы показать текст, нужно выполнить последовательность (после 7-пакетной
 * аутентификации): display-config → teleprompter-init → страницы контента →
 * marker → sync. Payload'ы — протобаф (поэтому здесь свой varint-энкодер).
 */
object EvenG2Protocol {

    /** Один отправляемый пакет + задержка ПОСЛЕ него (мс), как в эталонном примере. */
    data class Item(val packet: ByteArray, val gapMs: Long)

    /** Результат сборки сценария: пакеты + следующие значения счётчиков seq/msg_id. */
    data class Built(val items: List<Item>, val nextSeq: Int, val nextMsg: Int)

    // ---------------------------------------------------------------------------------------------
    // CRC-16/CCITT и кодирование
    // ---------------------------------------------------------------------------------------------

    private fun crc16Ccitt(data: ByteArray, init: Int = 0xFFFF): Int {
        var crc = init
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Дописывает CRC (по payload = всё после 8-байтового заголовка), little-endian. */
    private fun addCrc(packet: ByteArray): ByteArray {
        val crc = crc16Ccitt(packet.copyOfRange(8, packet.size))
        return packet + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    private fun encodeVarint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        while (v > 0x7F) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add((v and 0x7F).toByte())
        return out.toByteArray()
    }

    private fun encodeVarint(value: Int): ByteArray = encodeVarint(value.toLong())

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** Кадр: заголовок 0xAA 0x21 ... + payload + CRC. */
    private fun buildPacket(seq: Int, svcHi: Int, svcLo: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            0xAA.toByte(), 0x21,
            (seq and 0xFF).toByte(),
            ((payload.size + 2) and 0xFF).toByte(),
            0x01, 0x01,
            (svcHi and 0xFF).toByte(), (svcLo and 0xFF).toByte()
        )
        return addCrc(header + payload)
    }

    // Размер куска payload в одном BLE-пакете при фрагментации (как у официального
    // приложения: все фрагменты в btsnoop ровно по 232 байта + последний остаток).
    private const val FRAG_CHUNK = 232

    /**
     * Кадры сообщения с ФРАГМЕНТАЦИЕЙ (формат официального приложения, снят с btsnoop):
     * payload + один CRC (по всему payload) нарезаются на куски по 232 байта; у всех
     * фрагментов ОДИН И ТОТ ЖЕ seq, байт4 = всего фрагментов, байт5 = номер (1..N).
     * Однофрагментное сообщение — обычный кадр с 01 01.
     */
    private fun buildPacketFrags(seq: Int, svcHi: Int, svcLo: Int, payload: ByteArray): List<ByteArray> {
        val crc = crc16Ccitt(payload)
        val full = payload + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
        val total = (full.size + FRAG_CHUNK - 1) / FRAG_CHUNK
        if (total <= 1) {
            return listOf(
                byteArrayOf(
                    0xAA.toByte(), 0x21, (seq and 0xFF).toByte(), (full.size and 0xFF).toByte(),
                    0x01, 0x01, (svcHi and 0xFF).toByte(), (svcLo and 0xFF).toByte()
                ) + full
            )
        }
        val out = ArrayList<ByteArray>(total)
        var off = 0
        for (k in 1..total) {
            val chunk = full.copyOfRange(off, minOf(off + FRAG_CHUNK, full.size))
            off += chunk.size
            out.add(
                byteArrayOf(
                    0xAA.toByte(), 0x21, (seq and 0xFF).toByte(), (chunk.size and 0xFF).toByte(),
                    (total and 0xFF).toByte(), (k and 0xFF).toByte(),
                    (svcHi and 0xFF).toByte(), (svcLo and 0xFF).toByte()
                ) + chunk
            )
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Аутентификация (7 пакетов, отправляются сразу после включения нотификаций)
    // ---------------------------------------------------------------------------------------------

    /**
     * Полная последовательность ПОДКЛЮЧЕНИЯ (авторизация + инициализация устройства),
     * снятая ДОСЛОВНО из официального приложения (btsnoop). Именно она переводит очки
     * в состояние, когда телесуфлёр реально показывает текст (очки в ответ шлют
     * 0d01 state=6 и позиции 0601). Без неё кадры контента байт-в-байт верны, но
     * прошивка их игнорирует. seq доходит до 0x3a, msg_id — до 0x3c; контент затем
     * продолжаем с 0x3b/0x3d, чтобы не повторять id (иначе очки глушат повтор).
     */
    private val CONNECT_FRAMES = arrayOf(
        "aa21010c01018000080410031a04080110042f36",
        "aa21020c01018000080410041a04080110046b2f",
        "aa21030a0101802008051005220208015e42",
        "aa21041201018020088001100682080808a3ec93d206100c3b7d",
        "aa21050601010d20080010079772",
        "aa21061401010920080110081a0c4a0a08001000180020002801e67f",
        "aa21073b01010320080010091a33080612040800200612040800200712050800208a021204080020041210080110011a075765617468657220ab521204080020012fa1",
        "aa21080a01011f200800100a1a0208012af7",
        "aa21090c01010c200802100b220408011000409d",
        "aa210a0e01010720080a100c6a06080010202000ce97",
        "aa210b0c010130200801100d1a0408011000ef33",
        "aa210c0a010110200801100e1a020804e896",
        "aa210d0a010109200802100f22020801b41d",
        "aa210e1f010101200802101022171215080410031a0301020320042a040103020230003801b380",
        "aa210f120101012008021011220a1a081206120408001000ec6d",
        "aa211031010101200802101222291a270a250a230d000070411001180220d98286ebf1332a06436c6f75647330013a02316840014a012db8d3",
        "aa211131010101200802101322291a270a250a230d000070411001180220d98286ebf1332a06436c6f75647330013a02316840014a012d5494",
        "aa21120a01010120080710144a020801a750",
        "aa21130a01010120080710154a020801f6fa",
        "aa21140c01010920080110161a040a021002c1db",
        "aa21150a01010120080710174a02080175be",
        "aa21160801018120080110181a00deb4",
        "aa21170a01012020080010191a020800830d",
        "aa211808010120200801101a22008256",
        "aa211922010109200801101b1a1a52180a060800100018000a060800100118000a060800100218001e4b",
        "aa211a0c010109200801101c1a040a02104801c1",
        "aa211b10010104200801101d1a08080110001800280112da",
        "aa211c12010101200802101e220a1a081206120408001000beac",
        "aa211e140101012008021020220c1a0a12081a06080010002001e708",
        "aa211f140101012008021021220c1a0a12081a060800100020011ba6",
        "aa2121140101012008021023220c1a0a12081a06080010002001c2eb",
        "aa21250c01010920080110271a040a02100ce3db",
        "aa212b9301010e200802102d228a0108011215080210904e1d0000000025000000002800300038001215080310ac021d0000000025000000002800300038001214080410001d0000000025000000002800300038001214080510001d0000000025000000002800300038001214080610001d0000000025000000002800300038001214080910001d0000000025000000002800300038001800b10a",
        "aa212d0c010109200801102f1a040a021020a0ad",
        "aa21300c01010920080110321a040a021020d170",
        "aa21330c01010920080110351a040a021022d749",
        "aa21340c01010920080110361a040a02102c9b70",
        "aa21360c01010920080110381a040a02100e3346",
        "aa21370801018000080e10396a008868",
        "aa21380c010109200801103a1a040a02103cc130",
        "aa21390801018000080e103b6a00e806",
        "aa213a0801018000080e103c6a007883"
    )

    /** seq/msg, с которых продолжаем контент ПОСЛЕ [buildConnectItems]. */
    const val CONNECT_NEXT_SEQ = 0x3b
    const val CONNECT_NEXT_MSG = 0x3d

    /** Дословная последовательность подключения как Item'ы (с паузами). Кадр №4
     *  (индекс 3) — авторизация с меткой времени — пересобирается со СВЕЖИМ timestamp,
     *  иначе очки отвергают устаревшую метку и молчат на весь handshake. */
    fun buildConnectItems(): List<Item> = CONNECT_FRAMES.mapIndexed { i, h ->
        val bytes = if (i == 3) buildAuthTimeFrame() else hex(h)
        // Пейсинг КАК В ОФИЦ. ПРИЛОЖЕНИИ: после первого кадра авторизации очки ~2с
        // шлют auth-challenge — надо подождать, иначе авторизация не завершается и
        // очки молчат на весь handshake. Дальше даём каждому запросу время на ответ.
        val gap = when (i) {
            0 -> 2100L        // после первого auth-кадра — большая пауза (challenge)
            in 1..5 -> 250L   // остальная авторизация
            else -> 160L      // запросы инициализации устройства (ждём ответ очков)
        }
        Item(bytes, gap)
    }

    /** Два первых auth-запроса для ЛЕВОЙ дужки: официалка шлёт ей только их,
     *  весь остальной протокол идёт правой (btsnoop bugofc, handle→MAC). */
    fun leftPingFrames(): List<ByteArray> = listOf(hex(CONNECT_FRAMES[0]), hex(CONNECT_FRAMES[1]))

    /** Кадр авторизации №4 (seq 0x04, svc 0x8020) со свежим unix-временем (сек). */
    private fun buildAuthTimeFrame(): ByteArray {
        val ts = encodeVarint(System.currentTimeMillis() / 1000L)   // 5 байт для текущей эпохи
        val payload = byteArrayOf(0x08, 0x80.toByte(), 0x01, 0x10, 0x06, 0x82.toByte(), 0x08, 0x08, 0x08) +
            ts + byteArrayOf(0x10, 0x0C)
        val header = byteArrayOf(
            0xAA.toByte(), 0x21, 0x04, ((payload.size + 2) and 0xFF).toByte(),
            0x01, 0x01, 0x80.toByte(), 0x20
        )
        return addCrc(header + payload)
    }

    // --- Динамическая авторизация (challenge-response, снято из офиц. приложения) ---
    // Порядок: app шлёт кадр1/кадр2 (msg 3/4) на 0x8000 → очки в ответ шлют на 0x8001
    // «challenge» со СВОИМ msg_id (напр. 0x40). Дальше app ОБЯЗАН синхронизировать
    // счётчик: кадр3 (0x8020) с msg = challenge, кадр4 (0x8020) с msg = challenge+1.
    // Иначе очки НЕ подтверждают кадр3/4, авторизация не завершается и телесуфлёр молчит.

    /** Кадр авторизации на 0x8000 (msg 3 и 4): «08 04 10 <msg> 1a 04 08 01 10 04». */
    fun buildAuth8000(seq: Int, msg: Int): ByteArray =
        buildPacket(seq, 0x80, 0x00,
            byteArrayOf(0x08, 0x04, 0x10) + encodeVarint(msg) + byteArrayOf(0x1A, 0x04, 0x08, 0x01, 0x10, 0x04))

    /** Кадр3 авторизации на 0x8020: «08 05 10 <msg> 22 02 08 01» (msg = challenge). */
    fun buildAuth8020c(seq: Int, msg: Int): ByteArray =
        buildPacket(seq, 0x80, 0x20,
            byteArrayOf(0x08, 0x05, 0x10) + encodeVarint(msg) + byteArrayOf(0x22, 0x02, 0x08, 0x01))

    /** Кадр4 авторизации на 0x8020 со свежим временем: «08 8001 10 <msg> 82 08 <len> 08 <ts> 10 0c». */
    fun buildAuth8020d(seq: Int, msg: Int): ByteArray {
        val ts = encodeVarint(System.currentTimeMillis() / 1000L)      // 5 байт для текущей эпохи
        val sub = byteArrayOf(0x08) + ts + byteArrayOf(0x10, 0x0C)     // 8 байт
        val payload = byteArrayOf(0x08, 0x80.toByte(), 0x01, 0x10) + encodeVarint(msg) +
            byteArrayOf(0x82.toByte(), 0x08, (sub.size and 0xFF).toByte()) + sub
        return buildPacket(seq, 0x80, 0x20, payload)
    }

    /**
     * «Открытие сессии показа» — 7 пакетов с ФИКСИРОВАННЫМИ msg 0x0c..0x13 и seq 1..7.
     * По btsnoop официальное приложение шлёт этот блок заново ПЕРЕД КАЖДЫМ запуском
     * телесуфлёра (не только при подключении!) и продолжает контент с seq 0x08/msg 0x14.
     * Без него очки принимают только первый показ после подключения.
     */
    fun buildAuthPackets(): List<ByteArray> {
        val ts = encodeVarint(System.currentTimeMillis() / 1000L)
        val txid = byteArrayOf(
            0xE8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01
        )
        val list = ArrayList<ByteArray>()

        // Auth 1
        list.add(addCrc(byteArrayOf(
            0xAA.toByte(), 0x21, 0x01, 0x0C, 0x01, 0x01, 0x80.toByte(), 0x00,
            0x08, 0x04, 0x10, 0x0C, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04
        )))
        // Auth 2
        list.add(addCrc(byteArrayOf(
            0xAA.toByte(), 0x21, 0x02, 0x0A, 0x01, 0x01, 0x80.toByte(), 0x20,
            0x08, 0x05, 0x10, 0x0E, 0x22, 0x02, 0x08, 0x02
        )))
        // Auth 3 (time sync + txid)
        val p3 = byteArrayOf(0x08, 0x80.toByte(), 0x01, 0x10, 0x0F, 0x82.toByte(), 0x08, 0x11, 0x08) +
            ts + byteArrayOf(0x10) + txid
        list.add(addCrc(byteArrayOf(
            0xAA.toByte(), 0x21, 0x03, ((p3.size + 2) and 0xFF).toByte(), 0x01, 0x01, 0x80.toByte(), 0x20
        ) + p3))
        // Auth 4
        list.add(addCrc(byteArrayOf(
            0xAA.toByte(), 0x21, 0x04, 0x0C, 0x01, 0x01, 0x80.toByte(), 0x00,
            0x08, 0x04, 0x10, 0x10, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04
        )))
        // Auth 5
        list.add(addCrc(byteArrayOf(
            0xAA.toByte(), 0x21, 0x05, 0x0C, 0x01, 0x01, 0x80.toByte(), 0x00,
            0x08, 0x04, 0x10, 0x11, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04
        )))
        // Auth 6
        list.add(addCrc(byteArrayOf(
            0xAA.toByte(), 0x21, 0x06, 0x0A, 0x01, 0x01, 0x80.toByte(), 0x20,
            0x08, 0x05, 0x10, 0x12, 0x22, 0x02, 0x08, 0x01
        )))
        // Auth 7 (time sync + txid)
        val p7 = byteArrayOf(0x08, 0x80.toByte(), 0x01, 0x10, 0x13, 0x82.toByte(), 0x08, 0x11, 0x08) +
            ts + byteArrayOf(0x10) + txid
        list.add(addCrc(byteArrayOf(
            0xAA.toByte(), 0x21, 0x07, ((p7.size + 2) and 0xFF).toByte(), 0x01, 0x01, 0x80.toByte(), 0x20
        ) + p7))

        return list
    }

    // ---------------------------------------------------------------------------------------------
    // Команды телесуфлёра
    // ---------------------------------------------------------------------------------------------

    /**
     * Service 0x0E-20: конфигурация дисплея. Байты ДОСЛОВНО из референса teleprompter.py
     * (i-soxi). ВАЖНО: координаты X/Y здесь НЕНУЛЕВЫЕ (float 1191/1130/68/... в полях
     * 0x1d/0x25) — именно они размещают текст в видимой области. Прежний вариант со
     * всеми нулями рисовал текст в начале координат / за экраном (чёрный экран).
     * 14 фрагментов hex — 1:1 из reference, чтобы исключить ошибку транскрипции.
     */
    fun buildDisplayConfig(seq: Int, msgId: Int): ByteArray {
        // 138-байтовый конфиг С НУЛЕВЫМИ координатами — ТОЧНО из захвата ВАШЕГО
        // официального приложения (ofc.log). Именно он даёт полную ширину текста
        // (строки до 43 символов). Ненулевые координаты teleprompter.py сужали
        // область → «20 букв».
        val config = hex(
            "08011215080210904e1d0000000025000000002800300038001215080310ac02" +
            "1d0000000025000000002800300038001214080410001d000000002500000000" +
            "2800300038001214080510001d0000000025000000002800300038001214080610" +
            "001d0000000025000000002800300038001214080910001d0000000025000000" +
            "0028003000380018" + "00"
        )
        val payload = byteArrayOf(0x08, 0x02, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x22) + encodeVarint(config.size) + config
        return buildPacket(seq, 0x0E, 0x20, payload)
    }

    // Пресеты размера текста, СНЯТЫЕ из логов официального приложения (канал 0x0620,
    // кадр init). Разбор показал точную структуру полей display:
    //   0x20 = уровень размера (12/16/17/20…)
    //   0x28 = константа под размер (f28)      — НЕ зависит от текста
    //   0x30 = высота строки (line_height)     — НЕ зависит от текста
    //   0x38 = ПОЛНАЯ высота контента = строк × line_height  — ЗАВИСИТ от длины текста!
    // Именно 0x38 задаёт, насколько очки прокручивают текст. В прошлой версии он был
    // фиксированным (1817/3113) — поэтому очки листали лишь чуть-чуть и дальше была
    // «пропасть». Теперь считаем 0x38 из числа строк.
    private data class SizePreset(val size: Int, val f28: Int, val lineHeight: Int)
    // Значения строго из btsnoop: 12→(113,567) 16→(152,331) 17→(168,297) 20→(194,264).
    private val PRESET_BIG   = SizePreset(16, 152, 331)   // крупный
    private val PRESET_SMALL = SizePreset(12, 113, 567)   // мелкий

    const val PRESET_A_SIZE = 16   // для справки/совместимости
    const val PRESET_B_SIZE = 12

    /**
     * Service 0x06-20 type=1: инициализация телесуфлёра = protobuf
     * `TelepromptControl{cmd=START, startSettings}` (декомпилировано из офиц.
     * приложения, g2-kit teleprompt_pb.ts). Поля startSettings:
     *   1 (0x08) = mode: 0=AI, 1=MANUAL, 2=AUTO
     *   2/3      = startPageId / startLineId
     *   4..6     = константы пресета (в btsnoop зависят от размера текста)
     *   7 (0x38) = scrollIntervalMs — темп встроенной авто-прокрутки
     *   8/9      = countdownSeconds / useAudio
     * [manualMode]=true → MODE_MANUAL: прошивка НЕ крутит сама (покадровый показ).
     * Раньше всегда слали mode=2 (AUTO, как в авто-сессиях btsnoop) — прошивка сама
     * прокручивала единственную страницу кадра «в пустоту» за ~1.7с: видны 5 строк,
     * дальше пусто, и «ручной» режим вёл себя как автоматический.
     */
    private fun buildTeleprompterInit(
        seq: Int, msgId: Int, totalLines: Int, manualMode: Boolean = true, big: Boolean = false,
        perLineMs: Int = 0, startPage: Int = 0, startLine: Int = 0
    ): ByteArray {
        // Init-кадр телесуфлёра. Структура display подтверждена захватом ВАШЕЙ официалки
        // (ofc_cur.log): 08 01 [10 <startPage>] [18 <startLine>] 20 <size> 28 <contentH>
        //   30 <lineH> 38 <viewport> 40 00 48 <mode>.
        //   f2 (0x10) = startPageId, f3 (0x18) = startLineId — ПОЗИЦИЯ ПРОКРУТКИ.
        //     Официалка листает, пере-присылая ТОЛЬКО этот init с новыми f2/f3 (кадры
        //     18/20/21+ захвата: 10 04 = страница 4, 18 08 = строка 8). Страницы НЕ
        //     пере-присылаются, показ НЕ закрывается.
        //   f5 (0x28) = content_height = (lines × 2665) / 140 (формула RE, эхо очков =140).
        //   f9 (0x48) = scroll mode: 0=manual, 1=auto.
        val preset = if (big) PRESET_BIG else PRESET_SMALL
        val mode: Byte = if (manualMode) 0x00 else 0x01
        val contentHeight = maxOf(1, (maxOf(1, totalLines) * 2665) / 140)
        val display = byteArrayOf(0x08, 0x01, 0x10) + encodeVarint(startPage) +
            byteArrayOf(0x18) + encodeVarint(startLine) +
            byteArrayOf(0x20) + encodeVarint(preset.size) +
            byteArrayOf(0x28) + encodeVarint(contentHeight) +
            byteArrayOf(0x30) + encodeVarint(preset.lineHeight) +
            byteArrayOf(0x38) + encodeVarint(2264) +
            byteArrayOf(0x40, 0x00, 0x48, mode)

        // f7 (0x38) startSettings = scrollIntervalMs — темп нативной прокрутки (мс на
        // строку; из g2-kit teleprompt_pb.ts). 0 = не слать (темп прошивки по умолчанию).
        val speedField = if (perLineMs > 0) byteArrayOf(0x38) + encodeVarint(perLineMs) else ByteArray(0)
        val settings = byteArrayOf(0x08, 0x01, 0x12, (display.size and 0xFF).toByte()) + display + speedField
        val payload = byteArrayOf(0x08, 0x01, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x1A) + encodeVarint(settings.size) + settings
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /**
     * ПРОКРУТКА к позиции: пере-присылка ТОЛЬКО кадра INIT с новыми startPage/startLine
     * (механизм официалки из захвата ofc_cur.log — страницы не трогаем, показ не
     * закрывается). Один лёгкий кадр. Возвращает Built (для монотонных счётчиков).
     */
    fun buildScrollTo(
        seqStart: Int, msgStart: Int, totalLines: Int, startPage: Int, startLine: Int,
        manualMode: Boolean = true, big: Boolean = false
    ): Built {
        val pkt = buildTeleprompterInit(seqStart, msgStart, totalLines, manualMode, big,
            startPage = startPage, startLine = startLine)
        return Built(listOf(Item(pkt, 60L)), (seqStart + 1) and 0xFF, msgStart + 1)
    }

    // ---------------------------------------------------------------------------------------------
    // Управление воспроизведением и яркостью (снято из логов офиц. приложения)
    // ---------------------------------------------------------------------------------------------

    // Команды телесуфлёра — ТИПЫ кадров из firmware RE (TelepromptCommandId, services.md):
    //   5=PAUSE (пауза прокрутки), 6=RESUME (продолжить).
    const val TP_PAUSE: Int = 0x05
    const val TP_PLAY: Int  = 0x06   // RESUME

    /** Команда управления показом: кадр «08 <type> 10 <msg>» (типы 5/6 по firmware RE). */
    fun buildControl(seq: Int, msgId: Int, type: Int): ByteArray {
        val payload = byteArrayOf(0x08, (type and 0xFF).toByte(), 0x10) + encodeVarint(msgId)
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /**
     * СТОП / закрыть показ. Кадр «08 01 10 <msg> 1a 02 08 04» (state={f1=4}). На прошивке
     * 2.2.5.102 он ЗАКРЫВАЕТ телесуфлёр (проверено логами: сразу после него очки шлют
     * 0d01 «1a 00» = idle). Именно этот кадр раньше работал как «Стоп». EXIT (тип 7) на
     * этой прошивке показ НЕ закрывает — поэтому используем этот.
     */
    fun buildStop(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, 0x01, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x1A, 0x02, 0x08, 0x04)
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    // ---------------------------------------------------------------------------------------------
    // EvenHub контейнеры (сервис 0xE0-20) — ПЛАВНАЯ прокрутка через textContainerUpgrade.
    // Все значения ПОДТВЕРЖДЕНЫ захватом btsnoop реального EvenHub-телесуфлёра (bughub.zip,
    // 2026-07-09): create=cmd1 (плоский f4+f6), upgrade=cmd5 sub9, shutdown=cmd6, heartbeat=cmd0xc.
    // Заголовок outer: f1=cmd_type, f2=msg_id. Байты create/upgrade совпали с официалкой один-в-один.
    //   TextContainerProperty (внутри create): f1 x, f2 y, f3 w, f4 h, f6 borderColor,
    //     f8 padding, f9 containerID, f10 name, f11 isEventCapture, f12 content.
    //   TextContainerUpgrade: cmd=5, f3 sub_type=9 (обяз.), f4 id, f5 name, f6 offset,
    //     f7 length, f8 content.
    // ВАЖНО: фрагменты одного сообщения слать ВПЛОТНУЮ (~20-25мс). Прошивка собирает кадр
    // с коротким таймаутом — при паузе 500мс между фрагментами недособранное сообщение
    // отбрасывается МОЛЧА (первый провал порта был именно из-за этого, а не из-за формата).
    // ---------------------------------------------------------------------------------------------
    // Сессию ОТКРЫВАЕТ create cmd=0 (страница-обёртка, поле 3) — именно так в захвате открывался
    // первый показ сразу после display-config. Смена страниц — rebuild cmd=7 (поле 7).
    private const val EH_CMD_CREATE    = 0   // CreateStartUpPage — открывает сессию (подтверждено #1386)
    private const val EH_CMD_REBUILD   = 7   // RebuildPage — смена страницы на месте (подтверждено)
    private const val EH_CMD_SHUTDOWN  = 9   // ShutDownPage — закрыть страницу, возврат на домашний
                                             // экран (btsnoop официалки: `08 09 10 <msg> 5a 02 08 00`,
                                             // MentraOS SHUTDOWN_PAGE=9 поле 11). cmd=6 был НЕВЕРНЫМ.
    private const val EH_CMD_HEARTBEAT = 0xC // heartbeat (подтверждено: 08 0c 10 <msg> 72 00)

    // Средняя ширина символа шрифта EvenHub (LVGL, высота строки 27px) — эмпирически по
    // симулятору/захвату: ~11-13px на символ кириллицы. Берём с запасом 13, чтобы строка
    // колонки гарантированно влезала в контейнер (иначе LVGL сам переносит).
    private const val EH_PX_PER_CHAR = 13
    private const val EH_SCREEN_W = 576
    private const val EH_SCREEN_H = 288
    // Справа зарезервирована полоса прогресса («сколько текста осталось»).
    private const val EH_BAR_W = 24
    private const val EH_BODY_AREA_W = EH_SCREEN_W - EH_BAR_W - 4   // зона текста
    const val EH_BAR_ROWS = 10   // строк в полосе (288 / 27px ≈ 10)

    /** Общий каркас TextContainerProperty (поля как у реального note-list приложения). */
    private fun ehContainer(
        x: Int, y: Int, w: Int, h: Int, id: Int, name: String, eventCapture: Boolean, content: String
    ): ByteArray {
        val nameB = name.toByteArray(Charsets.UTF_8)
        val cB = content.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x08) + encodeVarint(x) +         // f1 X
            byteArrayOf(0x10) + encodeVarint(y) +            // f2 Y
            byteArrayOf(0x18) + encodeVarint(w) +            // f3 width
            byteArrayOf(0x20) + encodeVarint(h) +            // f4 height
            byteArrayOf(0x28, 0x00) +                        // f5 borderWidth=0
            byteArrayOf(0x30, 0x05) +                        // f6 borderColor=5
            byteArrayOf(0x38, 0x00) +                        // f7 borderRadius=0
            byteArrayOf(0x40, 0x02) +                        // f8 padding=2
            byteArrayOf(0x48) + encodeVarint(id) +           // f9 containerID
            byteArrayOf(0x52) + encodeVarint(nameB.size) + nameB +   // f10 name
            byteArrayOf(0x58, if (eventCapture) 0x01 else 0x00) +    // f11 isEventCapture
            byteArrayOf(0x62) + encodeVarint(cB.size) + cB   // f12 content
    }

    /** Полоса прогресса: [filledRows] из [EH_BAR_ROWS] заполнено ('#' = прочитано,
     *  '.' = осталось). ASCII — прочие глифы прошивка молча пропускает. */
    private fun ehBarContent(filledRows: Int): String {
        val f = filledRows.coerceIn(0, EH_BAR_ROWS)
        return (0 until EH_BAR_ROWS).joinToString("\n") { if (it < f) "#" else "." }
    }

    // Узкий экран (как в официалке): полоса текста в EH_NARROW_ROWS строк по центру высоты.
    const val EH_WIDE_ROWS = 9      // строк текста в широком режиме (288px / 27px − запас)
    const val EH_NARROW_ROWS = 4    // строк текста в узком режиме
    private const val EH_LINE_H = 27

    /**
     * Страница из ДВУХ контейнеров: текст по центру зоны [EH_BODY_AREA_W] (чем уже колонка
     * [colChars] — тем ближе к середине; при [narrow] — полоса в EH_NARROW_ROWS строк по
     * центру высоты) + полоса прогресса справа. Ровно один контейнер с isEventCapture=1.
     *
     * [subPx] 0..26 — СУБ-СТРОЧНОЕ смещение для плавной прокрутки: контейнер стоит на
     * y = base + (27 − subPx). При subPx 0→27 текст ползёт вверх на строку; затем окно
     * сдвигается на строку и subPx обнуляется — верхняя граница текста непрерывна.
     */
    private fun ehPage(content: String, colChars: Int, filledRows: Int, narrow: Boolean, subPx: Int = 0): ByteArray {
        val w = (colChars * EH_PX_PER_CHAR + 8).coerceIn(64, EH_BODY_AREA_W)
        val x = (EH_BODY_AREA_W - w) / 2
        val rows = if (narrow) EH_NARROW_ROWS else EH_WIDE_ROWS
        val h = rows * EH_LINE_H + 4
        val yBase = if (narrow) ((EH_SCREEN_H - h) / 2 - EH_LINE_H / 2).coerceAtLeast(0) else 0
        val y = (yBase + EH_LINE_H - subPx.coerceIn(0, EH_LINE_H)).coerceIn(0, EH_SCREEN_H - h)
        val body = ehContainer(x, y, w, h, 1, "body", true, content)
        val bar = ehContainer(EH_SCREEN_W - EH_BAR_W, 0, EH_BAR_W, EH_SCREEN_H, 2, "sbar", false, ehBarContent(filledRows))
        return byteArrayOf(0x08, 0x02) +                     // f1 containerTotalNum=2
            byteArrayOf(0x1A) + encodeVarint(body.size) + body +   // f3 text container (repeated)
            byteArrayOf(0x1A) + encodeVarint(bar.size) + bar
    }

    /** CreateStartUpPage — ОТКРЫВАЕТ EvenHub-сессию. cmd=0, страница в поле 3 (`1a`).
     *  Подтверждено захватом: `08 00 10 <msg> 1a <len> { 08 N 1a <len> <container>... }`. */
    fun buildEvenHubCreate(
        seq: Int, msgId: Int, content: String, colChars: Int = 44, filledRows: Int = 0,
        narrow: Boolean = false, subPx: Int = 0
    ): List<ByteArray> {
        val page = ehPage(content, colChars, filledRows, narrow, subPx)
        val payload = byteArrayOf(0x08, EH_CMD_CREATE.toByte(), 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x1A) + encodeVarint(page.size) + page   // f3 = page (create)
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    /** RebuildPage — сменить страницу НА МЕСТЕ (плавно, без мигания). cmd=7, страница в поле 7
     *  (`3a`). Подтверждено: `08 07 10 <msg> 3a <len> { 08 N 1a <len> <container>... }`. */
    fun buildEvenHubRebuild(
        seq: Int, msgId: Int, content: String, colChars: Int = 44, filledRows: Int = 0,
        narrow: Boolean = false, subPx: Int = 0
    ): List<ByteArray> {
        val page = ehPage(content, colChars, filledRows, narrow, subPx)
        val payload = byteArrayOf(0x08, EH_CMD_REBUILD.toByte(), 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x3A) + encodeVarint(page.size) + page   // f7 = page (rebuild)
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    /** ПУСТАЯ страница (rebuild): один невидимый full-screen контейнер захвата событий
     *  (content=" ") — экран визуально гаснет, но тачбар-события продолжают приходить,
     *  чтобы разбудить показ следующим одиночным тапом. Контейнер id=1 "body" совпадает с
     *  тем, что рисует обычная страница, поэтому events идут тем же путём (0xE0-01). */
    fun buildEvenHubBlankRebuild(seq: Int, msgId: Int): List<ByteArray> {
        val evt = ehContainer(0, 0, EH_SCREEN_W, EH_SCREEN_H, 1, "body", true, " ")
        val page = byteArrayOf(0x08, 0x01) +                         // f1 containerTotalNum=1
            byteArrayOf(0x1A) + encodeVarint(evt.size) + evt         // f3 text container
        val payload = byteArrayOf(0x08, EH_CMD_REBUILD.toByte(), 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x3A) + encodeVarint(page.size) + page       // f7 = page (rebuild)
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    // ---------------------------------------------------------------------------------------------
    // КАРТИНКА в EvenHub (для КРУПНОГО меню — шрифт текст-контейнера фиксирован прошивкой, поэтому
    // меню рисуем битмапом, см. GlassesImage). Формат сверен с эталоном mentraos even_hub.py:
    //   • ImageContainerProperty: f1=x f2=y f3=w f4=h f5=id f6=name; ширина 20..288, высота 20..144.
    //   • Страница: f1=count, image-контейнеры в поле 4 (0x22) ПЕРЕД текстовыми (поле 3, 0x1a) —
    //     позже отражённый контейнер выходит вперёд, поэтому текст-слой событий кладём последним.
    //   • updateImageRawData: EvenHubDataMsg cmd=3, sub в поле 5 (0x2a);
    //     sub = f1 id, f2 name, f3 sessionId, f4 totalSize, f5 compress=0, f6 fragIdx, f7 fragSize,
    //     f8 rawData. BMP шлётся кусками по 4096 байт, ~200мс между кусками.
    // ---------------------------------------------------------------------------------------------
    const val MENU_IMG_W = 288          // = GlassesImage.MENU_W
    const val MENU_IMG_H = 144          // = GlassesImage.MENU_H
    const val MENU_IMG_ID = 10          // containerID картинки меню
    private const val MENU_EVT_ID = 1   // containerID невидимого слоя захвата событий
    const val IMG_FRAG_BYTES = 4096     // размер куска BMP

    /** ImageContainerProperty: f1..f6 (x,y,w,h,id,name). */
    private fun ehImageContainer(x: Int, y: Int, w: Int, h: Int, id: Int, name: String): ByteArray {
        val nameB = name.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x08) + encodeVarint(x) +
            byteArrayOf(0x10) + encodeVarint(y) +
            byteArrayOf(0x18) + encodeVarint(w) +
            byteArrayOf(0x20) + encodeVarint(h) +
            byteArrayOf(0x28) + encodeVarint(id) +
            byteArrayOf(0x32) + encodeVarint(nameB.size) + nameB
    }

    /** Страница меню-картинки: image-контейнер по центру + невидимый full-screen слой захвата
     *  событий (ровно один isEventCapture=1, content=" "). Картинка идёт первой (поле 4). */
    private fun ehImageMenuPage(imgW: Int, imgH: Int): ByteArray {
        val imgX = ((EH_SCREEN_W - imgW) / 2).coerceAtLeast(0)
        val imgY = ((EH_SCREEN_H - imgH) / 2).coerceAtLeast(0)
        val img = ehImageContainer(imgX, imgY, imgW, imgH, MENU_IMG_ID, "img")
        val evt = ehContainer(0, 0, EH_SCREEN_W, EH_SCREEN_H, MENU_EVT_ID, "evt", true, " ")
        return byteArrayOf(0x08, 0x02) +                          // f1 containerTotalNum=2
            byteArrayOf(0x22) + encodeVarint(img.size) + img +    // f4 image container (первым)
            byteArrayOf(0x1A) + encodeVarint(evt.size) + evt      // f3 text container (последним)
    }

    /** CreateStartUpPage со страницей меню-картинки (открывает EvenHub-сессию). */
    fun buildEvenHubImageMenuCreate(
        seq: Int, msgId: Int, imgW: Int = MENU_IMG_W, imgH: Int = MENU_IMG_H
    ): List<ByteArray> {
        val page = ehImageMenuPage(imgW, imgH)
        val payload = byteArrayOf(0x08, EH_CMD_CREATE.toByte(), 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x1A) + encodeVarint(page.size) + page   // f3 = page (create)
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    /** RebuildPage со страницей меню-картинки (пересобрать на месте). */
    fun buildEvenHubImageMenuRebuild(
        seq: Int, msgId: Int, imgW: Int = MENU_IMG_W, imgH: Int = MENU_IMG_H
    ): List<ByteArray> {
        val page = ehImageMenuPage(imgW, imgH)
        val payload = byteArrayOf(0x08, EH_CMD_REBUILD.toByte(), 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x3A) + encodeVarint(page.size) + page   // f7 = page (rebuild)
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    /** updateImageRawData: один кусок BMP в контейнер [containerId]. cmd=3, sub в поле 5. */
    fun buildImageRawData(
        seq: Int, msgId: Int, containerId: Int, name: String,
        sessionId: Int, totalSize: Int, fragIndex: Int, fragData: ByteArray
    ): List<ByteArray> {
        val nameB = name.toByteArray(Charsets.UTF_8)
        val sub = byteArrayOf(0x08) + encodeVarint(containerId) +
            byteArrayOf(0x12) + encodeVarint(nameB.size) + nameB +   // f2 containerName
            byteArrayOf(0x18) + encodeVarint(sessionId) +            // f3 mapSessionId
            byteArrayOf(0x20) + encodeVarint(totalSize) +            // f4 mapTotalSize
            byteArrayOf(0x28, 0x00) +                                // f5 compressMode=0
            byteArrayOf(0x30) + encodeVarint(fragIndex) +            // f6 mapFragmentIndex
            byteArrayOf(0x38) + encodeVarint(fragData.size) +        // f7 mapFragmentPacketSize
            byteArrayOf(0x42) + encodeVarint(fragData.size) + fragData   // f8 mapRawData
        val payload = byteArrayOf(0x08, 0x03, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x2A) + encodeVarint(sub.size) + sub         // f5 = updateImageRawData sub
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    /** ShutDownPage — закрыть EvenHub-страницу, очки возвращаются на домашний экран.
     *  Формат официалки (btsnoop 2026-07-14): `08 09 10 <msg> 5a 02 08 <exitMode>` —
     *  cmd=9, поле 11 (0x5a) {f1=exitMode}. exitMode: 0=выйти сразу, 1=диалог подтверждения. */
    fun buildEvenHubShutdown(seq: Int, msgId: Int, exitMode: Int = 0): ByteArray {
        val payload = byteArrayOf(0x08, EH_CMD_SHUTDOWN.toByte(), 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x5A, 0x02, 0x08, (exitMode and 0xFF).toByte())   // f11 { f1=exitMode }
        return buildPacket(seq, 0xE0, 0x20, payload)
    }

    /** Управляющий кадр cmd=9 (f11={f1=flag}). Официалка шлёт {0} сразу после создания
     *  страницы — в захвате следом появляются события 0xE0-01 (похоже, включает захват
     *  событий тачбара). Ответ очков: cmd=0x0a. */
    fun buildEvenHubControl(seq: Int, msgId: Int, flag: Int = 0): ByteArray {
        val payload = byteArrayOf(0x08, 0x09, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x5A, 0x02, 0x08, (flag and 0xFF).toByte())
        return buildPacket(seq, 0xE0, 0x20, payload)
    }

    /** EvenHub heartbeat — держит сессию живой. Подтверждено: `08 0c 10 <msg> 72 00`
     *  (официалка шлёт раз в ~1с; очки отвечают `08 0c 10 <msg> 7a 02 10 0c`). */
    fun buildEvenHubHeartbeat(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, EH_CMD_HEARTBEAT.toByte(), 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x72, 0x00)                           // f14 (LD пустой)
        return buildPacket(seq, 0xE0, 0x20, payload)
    }

    /** Детерминированный appId из имени пакета (алгоритм MentraOS). Нужен только для
     *  РЕГИСТРАЦИИ пункта в меню очков (0x03-20) — как владелец страницы (f5) НЕ годится. */
    fun menuAppId(packageName: String): Int {
        var h = 0
        for (c in packageName) h = (h shl 5) - h + c.code   // Int переполняется как в Kotlin/JS
        return 10029 + (kotlin.math.abs(h) % 506)
    }

    /** APP_SEND_MENU_INFO (0x03-20): пункт «[name]» в меню очков (+ built-in Notification и
     *  заглушки до минимума 5 пунктов). Выбор пункта пользователем приходит как EvenHub
     *  событие cmd=17 `08 11 … a2 01 { 08 <appId> }`. */
    fun buildMenuInfo(seq: Int, msgId: Int, name: String, appId: Int): ByteArray {
        fun item(body: ByteArray) = byteArrayOf(0x12) + encodeVarint(body.size) + body
        val menu = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0x08, 0x05))                                   // f1 count=5
            write(item(byteArrayOf(0x08, 0x00, 0x20, 0x04)))                 // built-in Notification
            val nameB = name.take(15).toByteArray(Charsets.UTF_8)
            write(item(byteArrayOf(0x08, 0x01, 0x10, 0x01, 0x1A) +
                encodeVarint(nameB.size) + nameB + byteArrayOf(0x20) + encodeVarint(appId)))
            for (ph in intArrayOf(10536, 10537, 10538)) {                    // заглушки «  ---»
                val phName = "  ---".toByteArray(Charsets.UTF_8)
                write(item(byteArrayOf(0x08, 0x01, 0x10, 0x01, 0x1A) +
                    encodeVarint(phName.size) + phName + byteArrayOf(0x20) + encodeVarint(ph)))
            }
        }.toByteArray()
        val payload = byteArrayOf(0x08, 0x00, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x1A) + encodeVarint(menu.size) + menu
        return buildPacket(seq, 0x03, 0x20, payload)
    }

    /** Разбить текст на страницы ~[maxChars] символов по границам слов (для EvenHub). */
    fun paginateForHub(text: String, maxChars: Int = 420): List<String> {
        val t = text.replace("\\n", "\n").trim()
        if (t.isEmpty()) return listOf(" ")
        val pages = ArrayList<String>()
        val cur = StringBuilder()
        for (para in t.split(Regex("\n"))) {
            val chunk = if (cur.isEmpty()) para else "\n" + para
            if (cur.length + chunk.length <= maxChars) {
                cur.append(chunk)
            } else {
                // абзац не влезает — режем по словам
                if (cur.isNotEmpty()) { pages.add(cur.toString()); cur.setLength(0) }
                var line = StringBuilder()
                for (w in para.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    if (line.length + w.length + 1 > maxChars) { pages.add(line.toString()); line = StringBuilder() }
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(w)
                }
                if (line.isNotEmpty()) cur.append(line)
            }
        }
        if (cur.isNotEmpty()) pages.add(cur.toString())
        if (pages.isEmpty()) pages.add(" ")
        return pages
    }

    /**
     * ЗАПУСК ПРОКРУТКИ: CONTENT_COMPLETE (тип 4, поле 6/0x32) — документированный
     * механизм из firmware RE: «CONTENT_COMPLETE (type=4) triggers automatic scroll».
     * ВАЖНО: альтернатива из того же дока — INIT со state={f1=4} — на прошивке
     * 2.2.5.102 ЗАКРЫВАЕТ показ (проверено логами дважды: сразу после неё очки шлют
     * 0d01 «1a 00» = idle; g2-kit тоже трактует 4=CLOSE). Поэтому только тип 4.
     */
    fun buildContentComplete(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, 0x04, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x32, 0x00)
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /**
     * Яркость дисплея очков. Канал 0x0920, кадр «08 01 10 <id> 1a 04 0a 02 10 <value>».
     * В логах офиц. приложения значение менялось ползунком в диапазоне ~2..72.
     */
    fun buildBrightness(seq: Int, msgId: Int, level: Int): ByteArray {
        val v = level.coerceIn(0, 100)
        val payload = byteArrayOf(0x08, 0x01, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x1A, 0x04, 0x0A, 0x02, 0x10, (v and 0xFF).toByte())
        return buildPacket(seq, 0x09, 0x20, payload)
    }

    /** Service 0x06-20 type=3: страница контента ([linesPerPage] строк в странице).
     *  ВИЗУАЛЬНО ПОДТВЕРЖДЁННЫЙ НА ЖЕЛЕЗЕ формат (пользователь видел текст): ведущий
     *  "\n" + 5 строк + " \n". ВАЖНО: геометрия init (константы 16/152/331) снята из
     *  сессий именно с 5-строчными страницами — 10-строчные страницы с этими
     *  константами прошивка «показывает» мимо экрана (state=6, но экран пуст). */
    private fun buildContentPage(seq: Int, msgId: Int, pageNum: Int, text: String, linesPerPage: Int): List<ByteArray> {
        // ДОСЛОВНО из референса: ведущий "\n" + текст страницы. lineCount = 10 (0x0A).
        val textBytes = ("\n" + text).toByteArray(Charsets.UTF_8)
        val inner = byteArrayOf(0x08) + encodeVarint(pageNum) +
            byteArrayOf(0x10, 0x0A) +   // 10 строк на странице (как в reference)
            byteArrayOf(0x1A) + encodeVarint(textBytes.size) + textBytes
        val content = byteArrayOf(0x2A) + encodeVarint(inner.size) + inner
        val payload = byteArrayOf(0x08, 0x03, 0x10) + encodeVarint(msgId) + content
        return buildPacketFrags(seq, 0x06, 0x20, payload)
    }

    /** Service 0x06-20 type=255: mid-stream marker.
     *  ДОСЛОВНО из референса: `6a 04 08 00 10 06`. */
    private fun buildMarker(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, 0xFF.toByte(), 0x01, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x6A, 0x04, 0x08, 0x00, 0x10, 0x06)
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /**
     * Keepalive-маркер «6a 04 08 00 10 00». Официальное приложение шлёт его каждые ~6с
     * всё время, пока телесуфлёр открыт (btsnoop) — без него очки закрывают телесуфлёр
     * по таймауту простоя (~12с, кадр 0xa1 «3a 02 08 04»), и следующий показ игнорируется.
     */
    fun buildKeepalive(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, 0xFF.toByte(), 0x01, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x6A, 0x04, 0x08, 0x00, 0x10, 0x00)
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /**
     * Connection-heartbeat: 0x8000 type=14 «6a 00» (тот же кадр, что sync). Документация
     * openCFW: рекомендуемый интервал 3-5с; официальное приложение мониторит эхо-ответы
     * и по их пропаже переподключается. Мы шлём его постоянно, пока подключены —
     * связь не деградирует в простое между показами.
     */
    fun buildHeartbeat(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, 0x0E, 0x10) + encodeVarint(msgId) + byteArrayOf(0x6A, 0x00)
        return buildPacket(seq, 0x80, 0x00, payload)
    }

    /** Service 0x80-00 type=14: sync/триггер показа. */
    private fun buildSync(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, 0x0E, 0x10) + encodeVarint(msgId) + byteArrayOf(0x6A, 0x00)
        return buildPacket(seq, 0x80, 0x00, payload)
    }

    /** Service 0x04-20: пробуждение дисплея. Байты как у официалки (bugofc):
     *  payload = 08 01 10 <msg> 1a 08 08 01 10 00 18 00 28 01. Дисплей засыпает по
     *  таймауту простоя — перед показом будим, иначе текст рисуется в спящий экран. */
    fun buildDisplayWake(seq: Int, msgId: Int): ByteArray {
        val payload = byteArrayOf(0x08, 0x01, 0x10) + encodeVarint(msgId) +
            byteArrayOf(0x1A, 0x08, 0x08, 0x01, 0x10, 0x00, 0x18, 0x00, 0x28, 0x01)
        return buildPacket(seq, 0x04, 0x20, payload)
    }

    // ---------------------------------------------------------------------------------------------
    // Форматирование текста в страницы (10 строк/страница, мин. 14 страниц).
    //
    // Перенос строк — по БАЙТАМ UTF-8, а не по символам: на Android MTU обычно 247,
    // а кириллица занимает 2 байта/символ, поэтому страница из «широких» строк не
    // влезает в один BLE-пакет (write-without-response нельзя фрагментировать).
    // [lineByteLimit] подбирается из MTU в [BleService], чтобы пакет влезал.
    // ---------------------------------------------------------------------------------------------

    /**
     * Перенос одной строки-абзаца по ширине [cols] символов (спецификация G2):
     *   • обычные слова не рвутся — переносятся целиком на следующую строку;
     *   • слово длиннее ширины строки заполняет остаток ТЕКУЩЕЙ строки, а НЕ помещающиеся
     *     буквы продолжаются со следующей строки (ни один символ не теряется);
     *   • каждая строка заполняется максимально.
     */
    private fun wrapByWidth(line: String, cols: Int): List<String> {
        val width = cols.coerceAtLeast(1)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (word in line.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var w = word
            while (true) {
                val sep = if (cur.isEmpty()) 0 else 1
                val free = width - cur.length - sep
                if (w.length <= maxOf(free, 0)) {
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(w)
                    break
                }
                if (cur.isEmpty()) {
                    out.add(w.substring(0, width))
                    w = w.substring(width)
                } else if (free > 0 && w.length > width) {
                    cur.append(' ').append(w.substring(0, free))
                    out.add(cur.toString()); cur.setLength(0)
                    w = w.substring(free)
                } else {
                    out.add(cur.toString()); cur.setLength(0)
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        if (out.isEmpty()) out.add("")
        return out
    }

    // Полная ширина дисплея очков в символах (size 12): в захвате официалки самая
    // длинная строка = 43, значит экран вмещает ~44 моноширинных символа. Центрируем
    // ВСЕГДА относительно этой ширины (а НЕ ширины колонки) — тогда получается нужная
    // зависимость: чем уже колонка, тем больше пустых полей по бокам → текст ближе к
    // ЦЕНТРУ обзора; чем шире колонка, тем сильнее текст «разворачивается» на весь экран.
    const val DISPLAY_COLS = 44

    // Символ отступа — ОБЫЧНЫЙ пробел (офиц. способ центрирования на G2:
    // "manually pad with spaces", гайд Even Realities). NBSP (U+00A0) НЕ используем:
    // символы вне прошивочного шрифта очки "молча пропускают" (могли исчезать).
    private const val PAD = ' '

    /**
     * Горизонтальное центрирование строки по ПОЛНОЙ ширине дисплея [width]
     * (=[DISPLAY_COLS]). Официальный способ на G2 — «pad with spaces» (display.md:
     * «To "centre" text, you must manually pad with spaces»). Шрифт ПРОПОРЦИОНАЛЬНЫЙ
     * (не моноширинный, display.md/font), пробел ≈ ПОЛОВИНА среднего символа, поэтому
     * пробелов нужно ~вдвое больше, чем при моноширинном расчёте: pad = width − len
     * (а не /2 — с /2 текст стоит левее центра). Прежняя «обрезка длинного текста»
     * была багом порядка страниц (страницы после sync), НЕ объёма отступов — исправлено.
     * Пустые строки не трогаем.
     */
    private fun centerLine(line: String, width: Int = DISPLAY_COLS): String {
        val t = line.trim()
        if (t.isEmpty()) return line
        val pad = width - t.length
        return if (pad > 0) PAD.toString().repeat(pad) + t else t
    }

    // Вертикальное центрирование: сколько пустых строк добавить СВЕРХУ, чтобы текст
    // «висел» по центру области обзора очков, а не прижимался к верхнему краю.
    // Область обзора ≈ viewport/lineHeight = 2264/567 ≈ 4 видимые строки, поэтому 2
    // пустые строки сверху ставят первую строку текста в середину (как официалка:
    // страница 0 в захвате начиналась ровно с 2 пустых строк).
    private const val VCENTER_TOP_LINES = 2

    /**
     * Форматирование текста в страницы по [lineByteLimit] символов на строку
     * (ширина колонки) и [linesPerPage] строк на странице. Перенос — [wrapByWidth]
     * (полное заполнение строк, слова не теряются). Каждая строка ЦЕНТРИРУЕТСЯ по
     * горизонтали ([centerLine]), плюс [VCENTER_TOP_LINES] пустых строк сверху для
     * центрирования по вертикали. Пустых страниц-добивки НЕТ — добивается пустыми
     * строками только последняя неполная страница.
     */
    fun formatText(text: String, lineByteLimit: Int, linesPerPage: Int = 10, topMargin: Int = VCENTER_TOP_LINES): List<String> {
        val cols = lineByteLimit.coerceIn(8, DISPLAY_COLS)
        val t = text.replace("\\n", "\n")
        val wrapped = ArrayList<String>()
        for (line in t.split("\n")) {
            // Перенос — по ширине КОЛОНКИ (cols), центрирование — по ширине ДИСПЛЕЯ.
            if (line.trim().isEmpty()) wrapped.add("") else wrapByWidth(line, cols).forEach { wrapped.add(centerLine(it)) }
        }
        if (wrapped.isEmpty()) wrapped.add("")
        repeat(topMargin.coerceAtLeast(0)) { wrapped.add(0, "") }   // верт. центрирование (0 для авто-кадров — не съедать видимые строки)
        while (wrapped.size < linesPerPage) wrapped.add(" ")

        val pages = ArrayList<String>()
        var i = 0
        while (i < wrapped.size) {
            val pageLines = ArrayList(wrapped.subList(i, minOf(i + linesPerPage, wrapped.size)))
            while (pageLines.size < linesPerPage) pageLines.add(" ")
            pages.add(pageLines.joinToString("\n") + " \n")
            i += linesPerPage
        }
        if (pages.isEmpty()) pages.add(List(linesPerPage) { " " }.joinToString("\n") + " \n")
        return pages
    }

    // ---------------------------------------------------------------------------------------------
    // Полная последовательность показа сценария
    // ---------------------------------------------------------------------------------------------

    /**
     * Собирает все пакеты для показа [text]. Счётчики seq/msg_id продолжаются с
     * [seqStart]/[msgStart] (после аутентификации это 0x08 / 0x14, как в эталоне).
     * [lineByteLimit] — максимум байт UTF-8 на строку (подбирается из MTU в BleService),
     * чтобы каждый пакет-страница влез в один BLE write-without-response.
     */
    fun buildScript(
        text: String,
        seqStart: Int,
        msgStart: Int,
        lineByteLimit: Int,
        linesPerPage: Int,
        manualMode: Boolean,
        big: Boolean = true,
        perLineMs: Int = 0,
        topMargin: Int = VCENTER_TOP_LINES,
        autoStart: Boolean = false
    ): Built {
        val pages = formatText(text, lineByteLimit, linesPerPage, topMargin)
        // total_lines = число строк ПОСЛЕ переноса (страницы × строк/страницу) — именно
        // столько строк реально прокручивает прошивка (content_height в init).
        val totalLines = (pages.size * linesPerPage).coerceAtLeast(1)
        var seq = seqStart
        var msg = msgStart
        val out = ArrayList<Item>()

        fun add(pkt: ByteArray, gap: Long) {
            out.add(Item(pkt, gap))
            seq = (seq + 1) and 0xFF
            msg = (msg + 1) and 0xFF   // msgId — байт с заворотом (иначе >255 клинит прошивку)
        }

        // Страница может быть НЕСКОЛЬКО фрагментов (общий seq, msg внутри payload).
        fun addPage(frags: List<ByteArray>, gap: Long) {
            frags.forEachIndexed { idx, f -> out.add(Item(f, if (idx == frags.lastIndex) gap else 20L)) }
            seq = (seq + 1) and 0xFF
            msg = (msg + 1) and 0xFF
        }

        // Порядок: config → init → страницы 0..9 → marker → ВСЕ остальные страницы → sync.
        // ВАЖНО: раньше sync шёл после страницы 11, а страницы 12+ — ПОСЛЕ sync, и
        // прошивка их игнорировала (рендер «фиксировался» на sync) → текст обрезался на
        // ~12 страницах (120 строк). Поэтому широкая колонка (≤12 страниц) влезала, а
        // узкая (втрое больше страниц) — обрезалась. Теперь ВСЕ страницы идут ДО sync.
        add(buildDisplayConfig(seq, msg), 300L)
        add(buildTeleprompterInit(seq, msg, totalLines, manualMode = manualMode, big = big, perLineMs = perLineMs), 500L)
        for (i in 0 until minOf(10, pages.size)) addPage(buildContentPage(seq, msg, i, pages[i], linesPerPage), 100L)
        add(buildMarker(seq, msg), 100L)
        for (i in 10 until pages.size) addPage(buildContentPage(seq, msg, i, pages[i], linesPerPage), 100L)
        add(buildSync(seq, msg), 100L)
        // АВТО: CONTENT_COMPLETE (тип 4) после всех страниц запускает прокрутку прошивки
        // (firmware RE). НЕ путать со state={f1=4} — тот на этой прошивке закрывает показ.
        if (autoStart) add(buildContentComplete(seq, msg), 200L)

        return Built(out, seq, msg)
    }

    /** 7 пакетов «открытия показа» как Item'ы с паузами — шлются перед КАЖДЫМ показом. */
    fun buildAuthItems(): List<Item> {
        val pkts = buildAuthPackets()
        return pkts.mapIndexed { index, p ->
            Item(p, if (index == pkts.lastIndex) 180L else 45L)
        }
    }

    /**
     * ИНИЦИАЛИЗАЦИЯ УСТРОЙСТВА после авторизации — кадры официального приложения
     * (CONNECT_FRAMES кроме первых 4 auth-кадров): device-info, яркость, дашборд,
     * погода, конфиг дисплея. Именно эта «разогревающая» последовательность переводит
     * очки в состояние, когда телесуфлёр реально РИСУЕТ текст (без неё страницы
     * подтверждаются и state=6 приходит, но экран чёрный). Каждый кадр пересобирается
     * со СВЕЖИМ монотонным msg (продолжая нашу авторизацию) и новым CRC — иначе повтор
     * или «откат» msg прошивка отвергает.
     */
    fun buildDeviceInitItems(seqStart: Int, msgStart: Int): Built {
        var seq = seqStart
        var msg = msgStart
        val out = ArrayList<Item>()
        for (i in 4 until CONNECT_FRAMES.size) {
            val raw = hex(CONNECT_FRAMES[i])
            val svcHi = raw[6].toInt() and 0xFF
            val svcLo = raw[7].toInt() and 0xFF
            val payload = raw.copyOfRange(8, raw.size - 2)   // без заголовка и CRC
            // Формат всех этих кадров: 08 <type> 10 <msg> … — msg на индексе 3, 1 байт.
            if (payload.size > 3) payload[3] = (msg and 0xFF).toByte()
            out.add(Item(buildPacket(seq, svcHi, svcLo, payload), 120L))
            seq = (seq + 1) and 0xFF
            msg = (msg + 1) and 0xFF   // msgId — байт с заворотом
        }
        return Built(out, seq, msg)
    }

    /** Все строки текста после переноса по ширине колонки (для превью и показа).
     *  Разбивка ТА ЖЕ, что уходит на очки (см. [formatText]). */
    fun wrapLines(text: String, lineByteLimit: Int, center: Boolean = true): List<String> {
        val cols = lineByteLimit.coerceIn(8, DISPLAY_COLS)
        val t = text.replace("\\n", "\n")
        val out = ArrayList<String>()
        for (line in t.split("\n")) {
            if (line.isBlank()) out.add("")
            else wrapByWidth(line, cols).forEach { out.add(if (center) centerLine(it) else it) }
        }
        if (out.isEmpty()) out.add(" ")
        // Верхний отступ (верт. центрирование) — только для превью; для окон авто-режима
        // (center=false) НЕ добавляем, иначе buildScript отцентрует повторно.
        if (center) repeat(VCENTER_TOP_LINES) { out.add(0, "") }
        return out
    }

    /**
     * Смена размера текста «на лету»: один-единственный кадр teleprompter-init
     * (type=1) с новым пресетом. Ровно так делает официальное приложение — в логах
     * это одиночные кадры 0x0620 type=1 (без пере-отправки контента и авторизации):
     *   big=true  → пресет A: size 16, line_height 331, viewport 1817
     *   big=false → пресет B: size 12, line_height 567, viewport 3113
     */
    fun buildInitOnly(
        seqStart: Int, msgStart: Int, totalLines: Int, big: Boolean, manualMode: Boolean,
        perLineMs: Int = 0
    ): Built {
        val pkt = buildTeleprompterInit(seqStart, msgStart, totalLines, manualMode = manualMode, big = big, perLineMs = perLineMs)
        return Built(listOf(Item(pkt, 60L)), (seqStart + 1) and 0xFF, msgStart + 1)
    }
}
