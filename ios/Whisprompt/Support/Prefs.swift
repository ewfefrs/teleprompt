import Foundation

/// Тонкая обёртка над UserDefaults — аналог Android `Prefs`.
enum Prefs {
    static let KEY_TAMPERED = "tampered"
    static let KEY_PREMIUM_HASH = "premium_hash"
    static let KEY_WPM = "wpm"
    static let KEY_COL_WIDTH = "col_width"
    static let KEY_BRIGHTNESS = "brightness"
    static let KEY_NARROW = "narrow"
    static let KEY_BIG_TEXT = "big_text"
    static let KEY_LOCALE = "locale"

    private static var d: UserDefaults { .standard }

    static func putBool(_ key: String, _ value: Bool) { d.set(value, forKey: key) }
    static func getBool(_ key: String, _ def: Bool = false) -> Bool {
        d.object(forKey: key) == nil ? def : d.bool(forKey: key)
    }

    static func putString(_ key: String, _ value: String?) {
        if let value { d.set(value, forKey: key) } else { d.removeObject(forKey: key) }
    }
    static func getString(_ key: String) -> String? { d.string(forKey: key) }

    static func putFloat(_ key: String, _ value: Float) { d.set(value, forKey: key) }
    static func getFloat(_ key: String, _ def: Float) -> Float {
        d.object(forKey: key) == nil ? def : d.float(forKey: key)
    }

    static func putInt(_ key: String, _ value: Int) { d.set(value, forKey: key) }
    static func getInt(_ key: String, _ def: Int) -> Int {
        d.object(forKey: key) == nil ? def : d.integer(forKey: key)
    }
}
