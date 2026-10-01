import Foundation
import CoreBluetooth
import Combine

/// Подключение к очкам Even Realities **G2** по CoreBluetooth (аналог Android BleService).
/// Скан → подключение обеих дужек → нотификации → авторизация (7 пакетов) → READY,
/// очередь отправки с ВРЕМЕННЫМ пейсингом (write-without-response в 0x5401), парсинг событий
/// тачбара (0xE0-01) и показ текста через EvenHub-контейнеры. Байтовую сборку берёт `EvenG2Protocol`.
final class BleManager: NSObject, ObservableObject {

    static let shared = BleManager()

    private let CHAR_WRITE  = CBUUID(string: "00002760-08c2-11e1-9073-0e8ac72e5401")
    private let CHAR_NOTIFY = CBUUID(string: "00002760-08c2-11e1-9073-0e8ac72e5402")
    private let NUS_RX      = CBUUID(string: "6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    private let CONTENT_SEQ_START = 0x08
    private let CONTENT_MSG_START = 0x14

    // MARK: - Публичное состояние (для SwiftUI)

    @Published private(set) var status: String = "Отключено"
    @Published private(set) var isReady = false
    @Published private(set) var isScanning = false
    @Published private(set) var lines: [String] = []   // весь текущий текст, разбитый на строки
    @Published private(set) var top = 0                // верхняя видимая строка окна

    /// События с очков: "connected"/"disconnected"/"tap"/"prev"/"next"/"exit"/"reexit"/"narrow"/"wide".
    let events = PassthroughSubject<String, Never>()

    // MARK: - Внутреннее

    private var central: CBCentralManager!
    private var leftPeripheral: CBPeripheral?
    private var rightPeripheral: CBPeripheral?
    private var leftWrite: CBCharacteristic?
    private var lReady = false
    private var rReady = false
    private var authStarted = false
    private var authDone = false
    private var wantScan = false

    private struct Frame { let bytes: [UInt8]; let gapMs: Int }
    private var outbox = [Frame]()
    private var draining = false

    private var contentSeq = 0x08
    private var contentMsg = 0x14

    private var showNarrow = false
    private var showColChars = 44
    private var showActive = false

    /// Строк в окне показа (широкий 9 / узкий 4).
    var windowRows: Int { showNarrow ? EvenG2Protocol.EH_NARROW_ROWS : EvenG2Protocol.EH_WIDE_ROWS }
    var maxTop: Int { max(0, lines.count - windowRows) }

    override private init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "whisprompt.central"])
    }

    // MARK: - Публичный API

    func connect() {
        wantScan = true
        if central.state == .poweredOn { startScan() }
    }

    func disconnect() {
        wantScan = false
        stopScanning()
        showActive = false
        outbox.removeAll()
        draining = false
        if let l = leftPeripheral { central.cancelPeripheralConnection(l) }
        if let r = rightPeripheral { central.cancelPeripheralConnection(r) }
        resetConnection()
        setStatus("Отключено", ready: false)
    }

    /// Показать текст на очках (EvenHub). Создаёт страницу и рисует окно с начала.
    func showText(_ text: String, narrow: Bool = false, colChars: Int = 44) {
        guard isReady else { return }
        showNarrow = narrow
        showColChars = colChars
        lines = EvenG2Protocol.wrapLines(text, lineByteLimit: colChars, center: true)
        top = 0
        showActive = true
        let content = windowContent()
        enqueueHub { [self] s, m in
            EvenG2Protocol.buildEvenHubCreate(s, m, content: content, colChars: colChars,
                                              filledRows: filledRows(), narrow: narrow)
        }
    }

    /// Прокрутка окна на delta строк (перерисовка страницы на месте).
    func scroll(_ delta: Int) {
        guard isReady, showActive, !lines.isEmpty else { return }
        top = min(max(top + delta, 0), maxTop)
        let content = windowContent()
        enqueueHub { [self] s, m in
            EvenG2Protocol.buildEvenHubRebuild(s, m, content: content, colChars: showColChars,
                                               filledRows: filledRows(), narrow: showNarrow)
        }
    }

    func setNarrow(_ narrow: Bool) {
        guard isReady, showActive else { return }
        showNarrow = narrow
        let content = windowContent()
        enqueueHub { [self] s, m in
            EvenG2Protocol.buildEvenHubRebuild(s, m, content: content, colChars: showColChars,
                                               filledRows: filledRows(), narrow: narrow)
        }
        events.send(narrow ? "narrow" : "wide")
    }

    func setBrightness(_ level: Int) {
        guard isReady else { return }
        enqueueSingle { s, m in EvenG2Protocol.buildBrightness(s, m, level: level) }
    }

    func stopShow() {
        guard isReady else { return }
        showActive = false
        enqueueSingle(gapMs: 250) { s, m in EvenG2Protocol.buildEvenHubShutdown(s, m) }
        enqueueSingle(gapMs: 250) { s, m in EvenG2Protocol.buildEvenHubShutdown(s, m) }
        lines = []
        top = 0
    }

    // MARK: - Показ: окно/полоса прогресса

    private func windowContent() -> String {
        guard !lines.isEmpty else { return " " }
        var window = [String]()
        var i = top
        while i < lines.count && window.count < windowRows { window.append(lines[i]); i += 1 }
        while window.count < windowRows { window.append(" ") }
        return window.joined(separator: "\n")
    }

    private func filledRows() -> Int {
        guard lines.count > 0 else { return 0 }
        let frac = Double(top) / Double(max(1, lines.count))
        return Int((frac * Double(EvenG2Protocol.EH_BAR_ROWS)).rounded())
    }

    // MARK: - Очередь отправки (временной пейсинг)

    private func enqueue(_ items: [EvenG2Protocol.Item]) {
        for it in items { outbox.append(Frame(bytes: it.packet, gapMs: it.gapMs)) }
        drain()
    }

    private func enqueueSingle(gapMs: Int = 60, _ build: (Int, Int) -> [UInt8]) {
        let pkt = build(contentSeq, contentMsg)
        outbox.append(Frame(bytes: pkt, gapMs: gapMs))
        contentSeq = (contentSeq + 1) & 0xFF
        contentMsg = (contentMsg + 1) & 0xFF
        drain()
    }

    /// Один логический EvenHub-месседж = несколько фрагментов с ОДНИМ seq (шлём вплотную ~20мс).
    private func enqueueHub(gapMs: Int = 120, _ build: (Int, Int) -> [[UInt8]]) {
        let frags = build(contentSeq, contentMsg)
        for (idx, f) in frags.enumerated() {
            outbox.append(Frame(bytes: f, gapMs: idx == frags.count - 1 ? gapMs : 20))
        }
        contentSeq = (contentSeq + 1) & 0xFF
        contentMsg = (contentMsg + 1) & 0xFF
        drain()
    }

    private func drain() {
        guard !draining else { return }
        draining = true
        sendNext()
    }

    private func sendNext() {
        guard !outbox.isEmpty else { draining = false; return }
        guard let p = leftPeripheral, let ch = leftWrite else { draining = false; return }
        guard p.canSendWriteWithoutResponse else { return }   // ждём peripheralIsReady...
        let frame = outbox.removeFirst()
        p.writeValue(Data(frame.bytes), for: ch, type: .withoutResponse)
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(frame.gapMs) / 1000.0) { [weak self] in
            self?.sendNext()
        }
    }

    // MARK: - Авторизация

    private func maybeStartAuth() {
        guard lReady, rReady, !authStarted else { return }
        authStarted = true
        authDone = false
        contentSeq = CONTENT_SEQ_START
        contentMsg = CONTENT_MSG_START
        setStatus("Авторизация…", ready: false)
        enqueue(EvenG2Protocol.buildAuthItems())
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self, self.leftPeripheral != nil else { return }
            self.authDone = true
            self.setStatus("Подключено", ready: true)
            self.events.send("connected")
        }
    }

    // MARK: - Разбор нотификаций / жестов

    private func handleRx(_ v: [UInt8]) {
        guard v.count >= 11, v[0] == 0xAA else { return }
        let svcHi = Int(v[6]); let svcLo = Int(v[7])
        if svcHi == 0xE0 && svcLo == 0x01 { handleHubEvent(v) }
    }

    /// События EvenHub-страницы (0xE0-01). type: 0=тап, 1=свайп назад, 2=свайп вперёд, 3=двойной, 7=SYSTEM_EXIT.
    private func handleHubEvent(_ v: [UInt8]) {
        guard v.count >= 10 else { return }
        let payload = Array(v[8..<(v.count - 2)])
        var type = -1
        var i = 0
        while i + 1 < payload.count {
            if payload[i] == 0x6A {
                let outerEnd = min(i + 2 + Int(payload[i + 1]), payload.count)
                var j = i + 2
                while j + 1 < outerEnd {
                    let tag = Int(payload[j])
                    if tag == 0x12 || tag == 0x1A {
                        let subEnd = min(j + 2 + Int(payload[j + 1]), outerEnd)
                        if tag == 0x1A && type == -1 { type = 0 }
                        var k = j + 2
                        while k + 1 < subEnd {
                            switch Int(payload[k]) {
                            case 0x08: if tag == 0x1A { type = Int(payload[k + 1]) }; k += 2
                            case 0x18: type = Int(payload[k + 1]); k += 2
                            case 0x10, 0x20: k += 2
                            case 0x12: k += 2 + Int(payload[k + 1])
                            default: k += 1
                            }
                        }
                        j = subEnd
                    } else { j += 1 }
                }
                break
            }
            i += 1
        }
        let t = type
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            switch t {
            case 0: self.events.send("tap")
            case 1: self.scroll(-1); self.events.send("prev")
            case 2: self.scroll(1); self.events.send("next")
            case 3: self.events.send("exit")
            case 7: self.events.send("reexit")
            default: break
            }
        }
    }

    // MARK: - Скан/сброс

    private func startScan() {
        guard central.state == .poweredOn, !isScanning else { return }
        isScanning = true
        setStatus("Поиск очков…", ready: false)
        central.scanForPeripherals(withServices: nil, options: nil)
    }

    private func stopScanning() {
        if isScanning { central.stopScan() }
        isScanning = false
    }

    private func resetConnection() {
        leftPeripheral = nil
        rightPeripheral = nil
        leftWrite = nil
        lReady = false
        rReady = false
        authStarted = false
        authDone = false
        showActive = false
        outbox.removeAll()
        draining = false
    }

    private func setStatus(_ text: String, ready: Bool) {
        status = text
        isReady = ready
    }
}

// MARK: - CBCentralManagerDelegate

extension BleManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            if wantScan { startScan() }
        case .poweredOff, .unauthorized, .unsupported:
            setStatus("Bluetooth недоступен", ready: false)
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            for p in peripherals {
                p.delegate = self
                if (p.name ?? "").contains("_R_") { rightPeripheral = p } else { leftPeripheral = p }
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name
            ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? ""
        let isLeft = name.contains("_L_")
        let isRight = name.contains("_R_")
        guard isLeft || isRight else { return }

        if isLeft && leftPeripheral == nil {
            leftPeripheral = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        } else if isRight && rightPeripheral == nil {
            rightPeripheral = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }
        if leftPeripheral != nil && rightPeripheral != nil { stopScanning() }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        setStatus("Подключение…", ready: false)
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        setStatus("Ошибка подключения", ready: false)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        resetConnection()
        setStatus("Отключено", ready: false)
        events.send("disconnected")
        if wantScan { startScan() }
    }
}

// MARK: - CBPeripheralDelegate

extension BleManager: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        let isLeft = (peripheral === leftPeripheral)
        for ch in chars {
            switch ch.uuid {
            case CHAR_WRITE:  if isLeft { leftWrite = ch }
            case CHAR_NOTIFY: peripheral.setNotifyValue(true, for: ch)
            case NUS_RX:      peripheral.setNotifyValue(true, for: ch)
            default: break
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == CHAR_NOTIFY else { return }
        if peripheral === leftPeripheral { lReady = true }
        if peripheral === rightPeripheral { rReady = true }
        if rightPeripheral == nil { rReady = true }
        maybeStartAuth()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        handleRx([UInt8](data))
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        if draining { sendNext() }
    }
}
