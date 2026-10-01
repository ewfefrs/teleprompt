import Foundation

// Приватные строковые помощники (посимвольные срезы — как в Kotlin substring/length).
private extension String {
    func headChars(_ n: Int) -> String { String(prefix(max(0, n))) }
    func tailChars(_ n: Int) -> String { String(dropFirst(max(0, n))) }
}

/// Протокол дисплея очков Even Realities **G2**. Порт 1:1 из Android `EvenG2Protocol.kt`.
///
/// Запись в характеристику 0x5401, нотификации 0x5402. Кадр пакета:
///   [0xAA, 0x21, seq, len(payload)+2, 0x01, 0x01, svc_hi, svc_lo] + payload + CRC16_LE
///   CRC-16/CCITT (init=0xFFFF, poly=0x1021) считается ТОЛЬКО по payload (после 8-байтового заголовка).
/// Payload'ы — protobuf (свой varint-энкодер).
enum EvenG2Protocol {

    /// Один отправляемый пакет + задержка ПОСЛЕ него (мс).
    struct Item { let packet: [UInt8]; let gapMs: Int }
    /// Пакеты + следующие значения счётчиков seq/msg_id.
    struct Built { let items: [Item]; let nextSeq: Int; let nextMsg: Int }

    // MARK: - CRC-16/CCITT и кодирование

    static func crc16Ccitt(_ data: [UInt8], initial: Int = 0xFFFF) -> Int {
        var crc = initial
        for b in data {
            crc ^= (Int(b) & 0xFF) << 8
            for _ in 0..<8 {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1)
                crc &= 0xFFFF
            }
        }
        return crc & 0xFFFF
    }

    /// Дописывает CRC (по payload = всё после 8-байтового заголовка), little-endian.
    private static func addCrc(_ packet: [UInt8]) -> [UInt8] {
        let crc = crc16Ccitt(Array(packet[8...]))
        return packet + [UInt8(crc & 0xFF), UInt8((crc >> 8) & 0xFF)]
    }

    static func encodeVarint(_ value: UInt64) -> [UInt8] {
        var v = value
        var out = [UInt8]()
        while v > 0x7F {
            out.append(UInt8((v & 0x7F) | 0x80))
            v >>= 7
        }
        out.append(UInt8(v & 0x7F))
        return out
    }
    static func encodeVarint(_ value: Int) -> [UInt8] { encodeVarint(UInt64(max(0, value))) }

    private static func hex(_ s: String) -> [UInt8] {
        let chars = Array(s)
        var out = [UInt8]()
        var i = 0
        while i + 1 < chars.count {
            let hi = chars[i].hexDigitValue ?? 0
            let lo = chars[i + 1].hexDigitValue ?? 0
            out.append(UInt8((hi << 4) + lo))
            i += 2
        }
        return out
    }

    /// Кадр: заголовок 0xAA 0x21 ... + payload + CRC.
    private static func buildPacket(_ seq: Int, _ svcHi: Int, _ svcLo: Int, _ payload: [UInt8]) -> [UInt8] {
        let header: [UInt8] = [
            0xAA, 0x21,
            UInt8(seq & 0xFF),
            UInt8((payload.count + 2) & 0xFF),
            0x01, 0x01,
            UInt8(svcHi & 0xFF), UInt8(svcLo & 0xFF)
        ]
        return addCrc(header + payload)
    }

    private static let FRAG_CHUNK = 232

    /// Кадры сообщения с ФРАГМЕНТАЦИЕЙ (формат официального приложения): payload + один CRC
    /// нарезаются по 232 байта; у всех фрагментов один seq, байт4 = всего фрагментов, байт5 = номер.
    private static func buildPacketFrags(_ seq: Int, _ svcHi: Int, _ svcLo: Int, _ payload: [UInt8]) -> [[UInt8]] {
        let crc = crc16Ccitt(payload)
        let full = payload + [UInt8(crc & 0xFF), UInt8((crc >> 8) & 0xFF)]
        let total = (full.count + FRAG_CHUNK - 1) / FRAG_CHUNK
        if total <= 1 {
            let head: [UInt8] = [
                0xAA, 0x21, UInt8(seq & 0xFF), UInt8(full.count & 0xFF),
                0x01, 0x01, UInt8(svcHi & 0xFF), UInt8(svcLo & 0xFF)
            ]
            return [head + full]
        }
        var out = [[UInt8]]()
        var off = 0
        for k in 1...total {
            let end = min(off + FRAG_CHUNK, full.count)
            let chunk = Array(full[off..<end])
            off = end
            let head: [UInt8] = [
                0xAA, 0x21, UInt8(seq & 0xFF), UInt8(chunk.count & 0xFF),
                UInt8(total & 0xFF), UInt8(k & 0xFF),
                UInt8(svcHi & 0xFF), UInt8(svcLo & 0xFF)
            ]
            out.append(head + chunk)
        }
        return out
    }

    private static func unixSeconds() -> UInt64 { UInt64(Date().timeIntervalSince1970) }

    // MARK: - Аутентификация (снято дословно из официального приложения)

    private static let CONNECT_FRAMES: [String] = [
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
    ]

    /// seq/msg, с которых продолжаем контент ПОСЛЕ buildConnectItems.
    static let CONNECT_NEXT_SEQ = 0x3b
    static let CONNECT_NEXT_MSG = 0x3d

    /// Дословная последовательность подключения как Item'ы (с паузами). Кадр №4 (индекс 3) —
    /// авторизация с меткой времени — пересобирается со СВЕЖИМ timestamp.
    static func buildConnectItems() -> [Item] {
        CONNECT_FRAMES.enumerated().map { i, h in
            let bytes = i == 3 ? buildAuthTimeFrame() : hex(h)
            let gap: Int
            switch i {
            case 0: gap = 2100          // после первого auth-кадра — большая пауза (challenge)
            case 1...5: gap = 250       // остальная авторизация
            default: gap = 160          // запросы инициализации устройства
            }
            return Item(packet: bytes, gapMs: gap)
        }
    }

    /// Два первых auth-запроса для ЛЕВОЙ дужки.
    static func leftPingFrames() -> [[UInt8]] { [hex(CONNECT_FRAMES[0]), hex(CONNECT_FRAMES[1])] }

    /// Кадр авторизации №4 (seq 0x04, svc 0x8020) со свежим unix-временем (сек).
    private static func buildAuthTimeFrame() -> [UInt8] {
        let ts = encodeVarint(unixSeconds())
        let payload: [UInt8] = [0x08, 0x80, 0x01, 0x10, 0x06, 0x82, 0x08, 0x08, 0x08] + ts + [0x10, 0x0C]
        let header: [UInt8] = [0xAA, 0x21, 0x04, UInt8((payload.count + 2) & 0xFF), 0x01, 0x01, 0x80, 0x20]
        return addCrc(header + payload)
    }

    static func buildAuth8000(_ seq: Int, _ msg: Int) -> [UInt8] {
        buildPacket(seq, 0x80, 0x00, [0x08, 0x04, 0x10] + encodeVarint(msg) + [0x1A, 0x04, 0x08, 0x01, 0x10, 0x04])
    }

    static func buildAuth8020c(_ seq: Int, _ msg: Int) -> [UInt8] {
        buildPacket(seq, 0x80, 0x20, [0x08, 0x05, 0x10] + encodeVarint(msg) + [0x22, 0x02, 0x08, 0x01])
    }

    static func buildAuth8020d(_ seq: Int, _ msg: Int) -> [UInt8] {
        let ts = encodeVarint(unixSeconds())
        let sub: [UInt8] = [0x08] + ts + [0x10, 0x0C]
        let payload: [UInt8] = [0x08, 0x80, 0x01, 0x10] + encodeVarint(msg) +
            [0x82, 0x08, UInt8(sub.count & 0xFF)] + sub
        return buildPacket(seq, 0x80, 0x20, payload)
    }

    /// «Открытие сессии показа» — 7 пакетов с фиксированными msg 0x0c..0x13 и seq 1..7.
    static func buildAuthPackets() -> [[UInt8]] {
        let ts = encodeVarint(unixSeconds())
        let txid: [UInt8] = [0xE8, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01]
        var list = [[UInt8]]()

        list.append(addCrc([0xAA, 0x21, 0x01, 0x0C, 0x01, 0x01, 0x80, 0x00,
                            0x08, 0x04, 0x10, 0x0C, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04]))
        list.append(addCrc([0xAA, 0x21, 0x02, 0x0A, 0x01, 0x01, 0x80, 0x20,
                            0x08, 0x05, 0x10, 0x0E, 0x22, 0x02, 0x08, 0x02]))
        let p3: [UInt8] = [0x08, 0x80, 0x01, 0x10, 0x0F, 0x82, 0x08, 0x11, 0x08] + ts + [0x10] + txid
        list.append(addCrc([0xAA, 0x21, 0x03, UInt8((p3.count + 2) & 0xFF), 0x01, 0x01, 0x80, 0x20] + p3))
        list.append(addCrc([0xAA, 0x21, 0x04, 0x0C, 0x01, 0x01, 0x80, 0x00,
                            0x08, 0x04, 0x10, 0x10, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04]))
        list.append(addCrc([0xAA, 0x21, 0x05, 0x0C, 0x01, 0x01, 0x80, 0x00,
                            0x08, 0x04, 0x10, 0x11, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04]))
        list.append(addCrc([0xAA, 0x21, 0x06, 0x0A, 0x01, 0x01, 0x80, 0x20,
                            0x08, 0x05, 0x10, 0x12, 0x22, 0x02, 0x08, 0x01]))
        let p7: [UInt8] = [0x08, 0x80, 0x01, 0x10, 0x13, 0x82, 0x08, 0x11, 0x08] + ts + [0x10] + txid
        list.append(addCrc([0xAA, 0x21, 0x07, UInt8((p7.count + 2) & 0xFF), 0x01, 0x01, 0x80, 0x20] + p7))

        return list
    }

    // MARK: - Команды телесуфлёра

    /// Service 0x0E-20: конфигурация дисплея (138-байтовый конфиг с нулевыми координатами —
    /// точный захват официального приложения, даёт полную ширину текста до 43 символов).
    static func buildDisplayConfig(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let config = hex(
            "08011215080210904e1d0000000025000000002800300038001215080310ac02" +
            "1d0000000025000000002800300038001214080410001d000000002500000000" +
            "2800300038001214080510001d0000000025000000002800300038001214080610" +
            "001d0000000025000000002800300038001214080910001d0000000025000000" +
            "0028003000380018" + "00"
        )
        let payload: [UInt8] = [0x08, 0x02, 0x10] + encodeVarint(msgId) +
            [0x22] + encodeVarint(config.count) + config
        return buildPacket(seq, 0x0E, 0x20, payload)
    }

    // Пресеты размера текста (12→(113,567) 16→(152,331)) — значения строго из btsnoop.
    private struct SizePreset { let size: Int; let f28: Int; let lineHeight: Int }
    private static let PRESET_BIG   = SizePreset(size: 16, f28: 152, lineHeight: 331)
    private static let PRESET_SMALL = SizePreset(size: 12, f28: 113, lineHeight: 567)

    static let PRESET_A_SIZE = 16
    static let PRESET_B_SIZE = 12

    /// Service 0x06-20 type=1: инициализация телесуфлёра. f2/f3 = startPage/startLine (позиция
    /// прокрутки), f5 = content_height = lines×2665/140, f9 = scroll mode (0=manual, 1=auto).
    private static func buildTeleprompterInit(
        _ seq: Int, _ msgId: Int, _ totalLines: Int, manualMode: Bool = true, big: Bool = false,
        perLineMs: Int = 0, startPage: Int = 0, startLine: Int = 0
    ) -> [UInt8] {
        let preset = big ? PRESET_BIG : PRESET_SMALL
        let mode: UInt8 = manualMode ? 0x00 : 0x01
        let contentHeight = max(1, (max(1, totalLines) * 2665) / 140)
        var display: [UInt8] = [0x08, 0x01, 0x10] + encodeVarint(startPage)
        display += [0x18] + encodeVarint(startLine)
        display += [0x20] + encodeVarint(preset.size)
        display += [0x28] + encodeVarint(contentHeight)
        display += [0x30] + encodeVarint(preset.lineHeight)
        display += [0x38] + encodeVarint(2264)
        display += [0x40, 0x00, 0x48, mode]

        let speedField: [UInt8] = perLineMs > 0 ? [0x38] + encodeVarint(perLineMs) : []
        let settings: [UInt8] = [0x08, 0x01, 0x12, UInt8(display.count & 0xFF)] + display + speedField
        let payload: [UInt8] = [0x08, 0x01, 0x10] + encodeVarint(msgId) +
            [0x1A] + encodeVarint(settings.count) + settings
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /// Прокрутка к позиции: пере-присылка ТОЛЬКО init с новыми startPage/startLine.
    static func buildScrollTo(
        seqStart: Int, msgStart: Int, totalLines: Int, startPage: Int, startLine: Int,
        manualMode: Bool = true, big: Bool = false
    ) -> Built {
        let pkt = buildTeleprompterInit(seqStart, msgStart, totalLines, manualMode: manualMode,
                                        big: big, startPage: startPage, startLine: startLine)
        return Built(items: [Item(packet: pkt, gapMs: 60)], nextSeq: (seqStart + 1) & 0xFF, nextMsg: msgStart + 1)
    }

    // MARK: - Управление воспроизведением и яркостью

    static let TP_PAUSE = 0x05
    static let TP_PLAY  = 0x06   // RESUME

    static func buildControl(_ seq: Int, _ msgId: Int, _ type: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, UInt8(type & 0xFF), 0x10] + encodeVarint(msgId)
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /// СТОП / закрыть показ. Кадр «08 01 10 <msg> 1a 02 08 04».
    static func buildStop(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0x01, 0x10] + encodeVarint(msgId) + [0x1A, 0x02, 0x08, 0x04]
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    // MARK: - EvenHub контейнеры (сервис 0xE0-20) — плавная прокрутка

    private static let EH_CMD_CREATE = 0
    private static let EH_CMD_REBUILD = 7
    private static let EH_CMD_SHUTDOWN = 9
    private static let EH_CMD_HEARTBEAT = 0xC

    private static let EH_PX_PER_CHAR = 13
    private static let EH_SCREEN_W = 576
    private static let EH_SCREEN_H = 288
    private static let EH_BAR_W = 24
    private static let EH_BODY_AREA_W = 576 - 24 - 4
    static let EH_BAR_ROWS = 10

    /// TextContainerProperty (поля как у реального note-list приложения).
    private static func ehContainer(
        x: Int, y: Int, w: Int, h: Int, id: Int, name: String, eventCapture: Bool, content: String
    ) -> [UInt8] {
        let nameB = Array(name.utf8)
        let cB = Array(content.utf8)
        var out: [UInt8] = [0x08] + encodeVarint(x)      // f1 X
        out += [0x10] + encodeVarint(y)                  // f2 Y
        out += [0x18] + encodeVarint(w)                  // f3 width
        out += [0x20] + encodeVarint(h)                  // f4 height
        out += [0x28, 0x00]                              // f5 borderWidth=0
        out += [0x30, 0x05]                              // f6 borderColor=5
        out += [0x38, 0x00]                              // f7 borderRadius=0
        out += [0x40, 0x02]                              // f8 padding=2
        out += [0x48] + encodeVarint(id)                 // f9 containerID
        out += [0x52] + encodeVarint(nameB.count) + nameB   // f10 name
        out += [0x58, eventCapture ? 0x01 : 0x00]        // f11 isEventCapture
        out += [0x62] + encodeVarint(cB.count) + cB      // f12 content
        return out
    }

    /// Полоса прогресса: filledRows из EH_BAR_ROWS заполнено ('#'=прочитано, '.'=осталось).
    private static func ehBarContent(_ filledRows: Int) -> String {
        let f = min(max(filledRows, 0), EH_BAR_ROWS)
        return (0..<EH_BAR_ROWS).map { $0 < f ? "#" : "." }.joined(separator: "\n")
    }

    static let EH_WIDE_ROWS = 9
    static let EH_NARROW_ROWS = 4
    private static let EH_LINE_H = 27

    /// Страница из двух контейнеров: центрированный текст + полоса прогресса справа.
    /// subPx 0..26 — суб-строчное смещение для плавной прокрутки.
    private static func ehPage(_ content: String, _ colChars: Int, _ filledRows: Int, _ narrow: Bool, subPx: Int = 0) -> [UInt8] {
        let w = min(max(colChars * EH_PX_PER_CHAR + 8, 64), EH_BODY_AREA_W)
        let x = (EH_BODY_AREA_W - w) / 2
        let rows = narrow ? EH_NARROW_ROWS : EH_WIDE_ROWS
        let h = rows * EH_LINE_H + 4
        let yBase = narrow ? max((EH_SCREEN_H - h) / 2 - EH_LINE_H / 2, 0) : 0
        let y = min(max(yBase + EH_LINE_H - min(max(subPx, 0), EH_LINE_H), 0), EH_SCREEN_H - h)
        let body = ehContainer(x: x, y: y, w: w, h: h, id: 1, name: "body", eventCapture: true, content: content)
        let bar = ehContainer(x: EH_SCREEN_W - EH_BAR_W, y: 0, w: EH_BAR_W, h: EH_SCREEN_H,
                              id: 2, name: "sbar", eventCapture: false, content: ehBarContent(filledRows))
        var out: [UInt8] = [0x08, 0x02]                          // f1 containerTotalNum=2
        out += [0x1A] + encodeVarint(body.count) + body          // f3 text container
        out += [0x1A] + encodeVarint(bar.count) + bar
        return out
    }

    /// CreateStartUpPage — ОТКРЫВАЕТ EvenHub-сессию (cmd=0, страница в поле 3).
    static func buildEvenHubCreate(
        _ seq: Int, _ msgId: Int, content: String, colChars: Int = 44, filledRows: Int = 0,
        narrow: Bool = false, subPx: Int = 0
    ) -> [[UInt8]] {
        let page = ehPage(content, colChars, filledRows, narrow, subPx: subPx)
        let payload: [UInt8] = [0x08, UInt8(EH_CMD_CREATE), 0x10] + encodeVarint(msgId) +
            [0x1A] + encodeVarint(page.count) + page
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    /// RebuildPage — сменить страницу НА МЕСТЕ (cmd=7, страница в поле 7).
    static func buildEvenHubRebuild(
        _ seq: Int, _ msgId: Int, content: String, colChars: Int = 44, filledRows: Int = 0,
        narrow: Bool = false, subPx: Int = 0
    ) -> [[UInt8]] {
        let page = ehPage(content, colChars, filledRows, narrow, subPx: subPx)
        let payload: [UInt8] = [0x08, UInt8(EH_CMD_REBUILD), 0x10] + encodeVarint(msgId) +
            [0x3A] + encodeVarint(page.count) + page
        return buildPacketFrags(seq, 0xE0, 0x20, payload)
    }

    /// ShutDownPage — закрыть EvenHub-страницу (cmd=9, поле 11 {f1=exitMode}).
    static func buildEvenHubShutdown(_ seq: Int, _ msgId: Int, exitMode: Int = 0) -> [UInt8] {
        let payload: [UInt8] = [0x08, UInt8(EH_CMD_SHUTDOWN), 0x10] + encodeVarint(msgId) +
            [0x5A, 0x02, 0x08, UInt8(exitMode & 0xFF)]
        return buildPacket(seq, 0xE0, 0x20, payload)
    }

    /// Управляющий кадр cmd=9 (f11={f1=flag}); официалка шлёт {0} сразу после создания страницы.
    static func buildEvenHubControl(_ seq: Int, _ msgId: Int, flag: Int = 0) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0x09, 0x10] + encodeVarint(msgId) +
            [0x5A, 0x02, 0x08, UInt8(flag & 0xFF)]
        return buildPacket(seq, 0xE0, 0x20, payload)
    }

    /// EvenHub heartbeat — держит сессию живой («08 0c 10 <msg> 72 00»).
    static func buildEvenHubHeartbeat(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, UInt8(EH_CMD_HEARTBEAT), 0x10] + encodeVarint(msgId) + [0x72, 0x00]
        return buildPacket(seq, 0xE0, 0x20, payload)
    }

    /// Детерминированный appId из имени пакета (алгоритм MentraOS; 32-битное переполнение).
    static func menuAppId(_ packageName: String) -> Int {
        var h: Int32 = 0
        for scalar in packageName.unicodeScalars {
            let code = Int32(truncatingIfNeeded: scalar.value)
            h = (h &<< 5) &- h &+ code
        }
        return 10029 + (Int(abs(Int64(h))) % 506)
    }

    /// APP_SEND_MENU_INFO (0x03-20): пункт «[name]» в меню очков + заглушки до 5 пунктов.
    static func buildMenuInfo(_ seq: Int, _ msgId: Int, name: String, appId: Int) -> [UInt8] {
        func item(_ body: [UInt8]) -> [UInt8] { [0x12] + encodeVarint(body.count) + body }
        var menu: [UInt8] = [0x08, 0x05]                              // f1 count=5
        menu += item([0x08, 0x00, 0x20, 0x04])                        // built-in Notification
        let nameB = Array(String(name.prefix(15)).utf8)
        menu += item([0x08, 0x01, 0x10, 0x01, 0x1A] + encodeVarint(nameB.count) + nameB + [0x20] + encodeVarint(appId))
        for ph in [10536, 10537, 10538] {
            let phName = Array("  ---".utf8)
            menu += item([0x08, 0x01, 0x10, 0x01, 0x1A] + encodeVarint(phName.count) + phName + [0x20] + encodeVarint(ph))
        }
        let payload: [UInt8] = [0x08, 0x00, 0x10] + encodeVarint(msgId) +
            [0x1A] + encodeVarint(menu.count) + menu
        return buildPacket(seq, 0x03, 0x20, payload)
    }

    /// Разбить текст на страницы ~maxChars символов по границам слов (для EvenHub).
    static func paginateForHub(_ text: String, maxChars: Int = 420) -> [String] {
        let t = text.replacingOccurrences(of: "\\n", with: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return [" "] }
        var pages = [String]()
        var cur = ""
        for para in t.components(separatedBy: "\n") {
            let chunk = cur.isEmpty ? para : "\n" + para
            if cur.count + chunk.count <= maxChars {
                cur += chunk
            } else {
                if !cur.isEmpty { pages.append(cur); cur = "" }
                var line = ""
                for w in para.split(whereSeparator: { $0.isWhitespace }).map(String.init) {
                    if line.count + w.count + 1 > maxChars { pages.append(line); line = "" }
                    if !line.isEmpty { line += " " }
                    line += w
                }
                if !line.isEmpty { cur += line }
            }
        }
        if !cur.isEmpty { pages.append(cur) }
        if pages.isEmpty { pages.append(" ") }
        return pages
    }

    /// CONTENT_COMPLETE (тип 4, поле 6) — запускает нативную прокрутку прошивки.
    static func buildContentComplete(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0x04, 0x10] + encodeVarint(msgId) + [0x32, 0x00]
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /// Яркость дисплея (канал 0x0920, «08 01 10 <id> 1a 04 0a 02 10 <value>»).
    static func buildBrightness(_ seq: Int, _ msgId: Int, level: Int) -> [UInt8] {
        let v = min(max(level, 0), 100)
        let payload: [UInt8] = [0x08, 0x01, 0x10] + encodeVarint(msgId) +
            [0x1A, 0x04, 0x0A, 0x02, 0x10, UInt8(v & 0xFF)]
        return buildPacket(seq, 0x09, 0x20, payload)
    }

    /// Service 0x06-20 type=3: страница контента (ведущий "\n" + строки + " \n").
    private static func buildContentPage(_ seq: Int, _ msgId: Int, _ pageNum: Int, _ text: String, _ linesPerPage: Int) -> [[UInt8]] {
        let textBytes = Array(("\n" + text).utf8)
        var inner: [UInt8] = [0x08] + encodeVarint(pageNum)
        inner += [0x10, 0x0A]                                     // 10 строк на странице
        inner += [0x1A] + encodeVarint(textBytes.count) + textBytes
        let content: [UInt8] = [0x2A] + encodeVarint(inner.count) + inner
        let payload: [UInt8] = [0x08, 0x03, 0x10] + encodeVarint(msgId) + content
        return buildPacketFrags(seq, 0x06, 0x20, payload)
    }

    /// Service 0x06-20 type=255: mid-stream marker («6a 04 08 00 10 06»).
    private static func buildMarker(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0xFF, 0x01, 0x10] + encodeVarint(msgId) + [0x6A, 0x04, 0x08, 0x00, 0x10, 0x06]
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /// Keepalive-маркер «6a 04 08 00 10 00» (официалка шлёт каждые ~6с, пока показ открыт).
    static func buildKeepalive(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0xFF, 0x01, 0x10] + encodeVarint(msgId) + [0x6A, 0x04, 0x08, 0x00, 0x10, 0x00]
        return buildPacket(seq, 0x06, 0x20, payload)
    }

    /// Connection-heartbeat: 0x8000 type=14 «6a 00».
    static func buildHeartbeat(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0x0E, 0x10] + encodeVarint(msgId) + [0x6A, 0x00]
        return buildPacket(seq, 0x80, 0x00, payload)
    }

    /// Service 0x80-00 type=14: sync/триггер показа.
    private static func buildSync(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0x0E, 0x10] + encodeVarint(msgId) + [0x6A, 0x00]
        return buildPacket(seq, 0x80, 0x00, payload)
    }

    /// Service 0x04-20: пробуждение дисплея (payload 08 01 10 <msg> 1a 08 08 01 10 00 18 00 28 01).
    static func buildDisplayWake(_ seq: Int, _ msgId: Int) -> [UInt8] {
        let payload: [UInt8] = [0x08, 0x01, 0x10] + encodeVarint(msgId) +
            [0x1A, 0x08, 0x08, 0x01, 0x10, 0x00, 0x18, 0x00, 0x28, 0x01]
        return buildPacket(seq, 0x04, 0x20, payload)
    }

    // MARK: - Форматирование текста в страницы

    static let DISPLAY_COLS = 44
    static let VCENTER_TOP_LINES = 2

    /// Перенос строки-абзаца по ширине cols символов (слова не теряются, строки заполняются максимально).
    private static func wrapByWidth(_ line: String, _ cols: Int) -> [String] {
        let width = max(cols, 1)
        var out = [String]()
        var cur = ""
        for word in line.split(whereSeparator: { $0.isWhitespace }).map(String.init) {
            var w = word
            while true {
                let sep = cur.isEmpty ? 0 : 1
                let free = width - cur.count - sep
                if w.count <= max(free, 0) {
                    if !cur.isEmpty { cur += " " }
                    cur += w
                    break
                }
                if cur.isEmpty {
                    out.append(w.headChars(width))
                    w = w.tailChars(width)
                } else if free > 0 && w.count > width {
                    cur += " " + w.headChars(free)
                    out.append(cur); cur = ""
                    w = w.tailChars(free)
                } else {
                    out.append(cur); cur = ""
                }
            }
        }
        if !cur.isEmpty { out.append(cur) }
        if out.isEmpty { out.append("") }
        return out
    }

    /// Горизонтальное центрирование по полной ширине дисплея (pad = width − len, официальный способ).
    private static func centerLine(_ line: String, _ width: Int = 44) -> String {
        let t = line.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return line }
        let pad = width - t.count
        return pad > 0 ? String(repeating: " ", count: pad) + t : t
    }

    /// Форматирование текста в страницы: перенос по ширине колонки, центрирование по ширине дисплея,
    /// topMargin пустых строк сверху (верт. центрирование), добивка последней страницы.
    static func formatText(_ text: String, lineByteLimit: Int, linesPerPage: Int = 10, topMargin: Int = 2) -> [String] {
        let cols = min(max(lineByteLimit, 8), DISPLAY_COLS)
        let t = text.replacingOccurrences(of: "\\n", with: "\n")
        var wrapped = [String]()
        for line in t.components(separatedBy: "\n") {
            if line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                wrapped.append("")
            } else {
                for w in wrapByWidth(line, cols) { wrapped.append(centerLine(w)) }
            }
        }
        if wrapped.isEmpty { wrapped.append("") }
        for _ in 0..<max(topMargin, 0) { wrapped.insert("", at: 0) }
        while wrapped.count < linesPerPage { wrapped.append(" ") }

        var pages = [String]()
        var i = 0
        while i < wrapped.count {
            var pageLines = Array(wrapped[i..<min(i + linesPerPage, wrapped.count)])
            while pageLines.count < linesPerPage { pageLines.append(" ") }
            pages.append(pageLines.joined(separator: "\n") + " \n")
            i += linesPerPage
        }
        if pages.isEmpty {
            pages.append(Array(repeating: " ", count: linesPerPage).joined(separator: "\n") + " \n")
        }
        return pages
    }

    // MARK: - Полная последовательность показа сценария

    /// Собирает все пакеты для показа text (config → init → страницы 0..9 → marker → остальные → sync).
    static func buildScript(
        text: String, seqStart: Int, msgStart: Int, lineByteLimit: Int, linesPerPage: Int,
        manualMode: Bool, big: Bool = true, perLineMs: Int = 0, topMargin: Int = 2, autoStart: Bool = false
    ) -> Built {
        let pages = formatText(text, lineByteLimit: lineByteLimit, linesPerPage: linesPerPage, topMargin: topMargin)
        let totalLines = max(pages.count * linesPerPage, 1)
        var seq = seqStart
        var msg = msgStart
        var out = [Item]()

        func add(_ pkt: [UInt8], _ gap: Int) {
            out.append(Item(packet: pkt, gapMs: gap))
            seq = (seq + 1) & 0xFF
            msg = (msg + 1) & 0xFF
        }
        func addPage(_ frags: [[UInt8]], _ gap: Int) {
            for (idx, f) in frags.enumerated() {
                out.append(Item(packet: f, gapMs: idx == frags.count - 1 ? gap : 20))
            }
            seq = (seq + 1) & 0xFF
            msg = (msg + 1) & 0xFF
        }

        add(buildDisplayConfig(seq, msg), 300)
        add(buildTeleprompterInit(seq, msg, totalLines, manualMode: manualMode, big: big, perLineMs: perLineMs), 500)
        for i in 0..<min(10, pages.count) { addPage(buildContentPage(seq, msg, i, pages[i], linesPerPage), 100) }
        add(buildMarker(seq, msg), 100)
        if pages.count > 10 {
            for i in 10..<pages.count { addPage(buildContentPage(seq, msg, i, pages[i], linesPerPage), 100) }
        }
        add(buildSync(seq, msg), 100)
        if autoStart { add(buildContentComplete(seq, msg), 200) }

        return Built(items: out, nextSeq: seq, nextMsg: msg)
    }

    /// 7 пакетов «открытия показа» как Item'ы с паузами.
    static func buildAuthItems() -> [Item] {
        let pkts = buildAuthPackets()
        return pkts.enumerated().map { idx, p in
            Item(packet: p, gapMs: idx == pkts.count - 1 ? 180 : 45)
        }
    }

    /// Инициализация устройства после авторизации (CONNECT_FRAMES кроме первых 4 auth-кадров).
    static func buildDeviceInitItems(seqStart: Int, msgStart: Int) -> Built {
        var seq = seqStart
        var msg = msgStart
        var out = [Item]()
        for i in 4..<CONNECT_FRAMES.count {
            let raw = hex(CONNECT_FRAMES[i])
            let svcHi = Int(raw[6]) & 0xFF
            let svcLo = Int(raw[7]) & 0xFF
            var payload = Array(raw[8..<(raw.count - 2)])   // без заголовка и CRC
            if payload.count > 3 { payload[3] = UInt8(msg & 0xFF) }  // msg на индексе 3
            out.append(Item(packet: buildPacket(seq, svcHi, svcLo, payload), gapMs: 120))
            seq = (seq + 1) & 0xFF
            msg = (msg + 1) & 0xFF
        }
        return Built(items: out, nextSeq: seq, nextMsg: msg)
    }

    /// Все строки текста после переноса по ширине колонки (для превью и показа).
    static func wrapLines(_ text: String, lineByteLimit: Int, center: Bool = true) -> [String] {
        let cols = min(max(lineByteLimit, 8), DISPLAY_COLS)
        let t = text.replacingOccurrences(of: "\\n", with: "\n")
        var out = [String]()
        for line in t.components(separatedBy: "\n") {
            if line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                out.append("")
            } else {
                for w in wrapByWidth(line, cols) { out.append(center ? centerLine(w) : w) }
            }
        }
        if out.isEmpty { out.append(" ") }
        if center { for _ in 0..<VCENTER_TOP_LINES { out.insert("", at: 0) } }
        return out
    }

    /// Смена размера текста «на лету»: один кадр teleprompter-init (type=1) с новым пресетом.
    static func buildInitOnly(
        seqStart: Int, msgStart: Int, totalLines: Int, big: Bool, manualMode: Bool, perLineMs: Int = 0
    ) -> Built {
        let pkt = buildTeleprompterInit(seqStart, msgStart, totalLines, manualMode: manualMode, big: big, perLineMs: perLineMs)
        return Built(items: [Item(packet: pkt, gapMs: 60)], nextSeq: (seqStart + 1) & 0xFF, nextMsg: msgStart + 1)
    }
}
