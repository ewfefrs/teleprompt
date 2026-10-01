import Foundation
import Combine

/// Состояние приложения: тексты, настройки, воспроизведение (авто-скролл), лимиты free-версии,
/// премиум. Ведёт показ через `BleManager` (аналог Android TeleprompterViewModel + BleService API).
final class TeleprompterModel: ObservableObject {

    static let shared = TeleprompterModel()

    @Published var texts: [SavedText] = []
    @Published var wpm: Double = 130          // скорость чтения, слов/мин
    @Published var colWidth: Double = 44      // ширина колонки, символов
    @Published var brightness: Double = 60    // яркость очков 0..100
    @Published var narrow: Bool = false       // узкий экран
    @Published var playing: Bool = false
    @Published var premium: Bool = PremiumManager.isPremium()

    let ble = BleManager.shared
    private var timer: Timer?
    private var cancellables = Set<AnyCancellable>()

    static let FREE_MAX_TEXTS = 2
    static let FREE_MAX_WORDS = 100
    private let KEY_TEXTS = "texts_json"

    private init() {
        load()
        ble.events
            .receive(on: RunLoop.main)
            .sink { [weak self] ev in self?.onGlassesEvent(ev) }
            .store(in: &cancellables)
    }

    var isPremium: Bool { PremiumManager.isPremium() }
    func refreshPremium() { premium = PremiumManager.isPremium() }

    // MARK: - Загрузка/сохранение

    private func load() {
        wpm = Double(Prefs.getFloat(Prefs.KEY_WPM, 130))
        colWidth = Double(Prefs.getFloat(Prefs.KEY_COL_WIDTH, 44))
        brightness = Double(Prefs.getFloat(Prefs.KEY_BRIGHTNESS, 60))
        narrow = Prefs.getBool(Prefs.KEY_NARROW, false)
        if let s = Prefs.getString(KEY_TEXTS), let data = s.data(using: .utf8),
           let arr = try? JSONDecoder().decode([SavedText].self, from: data) {
            texts = arr
        }
    }

    func saveSettings() {
        Prefs.putFloat(Prefs.KEY_WPM, Float(wpm))
        Prefs.putFloat(Prefs.KEY_COL_WIDTH, Float(colWidth))
        Prefs.putFloat(Prefs.KEY_BRIGHTNESS, Float(brightness))
        Prefs.putBool(Prefs.KEY_NARROW, narrow)
    }

    private func saveTexts() {
        if let data = try? JSONEncoder().encode(texts), let s = String(data: data, encoding: .utf8) {
            Prefs.putString(KEY_TEXTS, s)
        }
    }

    // MARK: - Тексты + лимиты free

    func canAddText() -> Bool { isPremium || texts.count < Self.FREE_MAX_TEXTS }
    var wordLimit: Int? { isPremium ? nil : Self.FREE_MAX_WORDS }

    func addOrUpdate(_ text: SavedText) {
        var t = text
        if let limit = wordLimit { t.body = Self.capWords(t.body, limit) }
        if let idx = texts.firstIndex(where: { $0.id == t.id }) { texts[idx] = t }
        else { texts.append(t) }
        saveTexts()
    }

    func delete(at offsets: IndexSet) {
        texts.remove(atOffsets: offsets)
        saveTexts()
    }

    static func capWords(_ s: String, _ limit: Int) -> String {
        let words = s.split(whereSeparator: { $0.isWhitespace })
        if words.count <= limit { return s }
        return words.prefix(limit).joined(separator: " ")
    }

    // MARK: - Воспроизведение

    func play(_ text: SavedText) {
        guard ble.isReady else { return }
        ble.showText(displayBody(text.body), narrow: narrow, colChars: Int(colWidth))
        ble.setBrightness(Int(brightness))
        playing = true
        scheduleTimer()
    }

    func pause() {
        playing = false
        timer?.invalidate(); timer = nil
    }

    func resume() {
        guard ble.isReady, ble.top < ble.maxTop else { return }
        playing = true
        scheduleTimer()
    }

    func stop() {
        playing = false
        timer?.invalidate(); timer = nil
        ble.stopShow()
    }

    func scrollBy(_ d: Int) { ble.scroll(d) }

    private func scheduleTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: lineInterval(), repeats: true) { [weak self] _ in
            guard let self else { return }
            if self.ble.top >= self.ble.maxTop { self.pause(); return }
            self.ble.scroll(1)
        }
    }

    /// Секунд на строку из WPM и ширины колонки.
    private func lineInterval() -> Double {
        let wordsPerLine = max(1.0, colWidth / 6.0)      // ~6 символов на слово с пробелом
        let linesPerSec = max(0.05, (wpm / 60.0) / wordsPerLine)
        return min(6.0, max(0.3, 1.0 / linesPerSec))
    }

    // MARK: - Настройки на лету

    func applyBrightness() { ble.setBrightness(Int(brightness)); saveSettings() }
    func applyNarrow() { ble.setNarrow(narrow); saveSettings() }
    func applySpeedIfPlaying() { if playing { scheduleTimer() } }

    private func displayBody(_ body: String) -> String {
        guard !isPremium else { return body }
        let capped = Self.capWords(body, Self.FREE_MAX_WORDS)
        return capped + "\n\nBuy full version: \(LicenseManager.TG_BOT)"
    }

    // MARK: - События тачбара очков

    private func onGlassesEvent(_ ev: String) {
        switch ev {
        case "tap":  narrow.toggle(); applyNarrow()
        case "prev": scrollBy(-1)
        case "next": scrollBy(1)
        case "exit": stop()
        default: break
        }
    }
}
