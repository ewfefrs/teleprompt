import Foundation
import CryptoKit

/// Право «премиум» хранится как SHA-256 от активированного ключа (а не голый bool),
/// и гасится при обнаружении вмешательства (KEY_TAMPERED). Аналог Android `PremiumManager`.
enum PremiumManager {

    static func isPremium() -> Bool {
        if Prefs.getBool(Prefs.KEY_TAMPERED) { return false }
        return !((Prefs.getString(Prefs.KEY_PREMIUM_HASH) ?? "").isEmpty)
    }

    static func grantFromKey(_ key: String) {
        let digest = SHA256.hash(data: Data(key.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        Prefs.putString(Prefs.KEY_PREMIUM_HASH, hex)
    }

    static func clear() {
        Prefs.putString(Prefs.KEY_PREMIUM_HASH, nil)
    }
}
