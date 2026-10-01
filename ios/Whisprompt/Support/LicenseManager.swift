import Foundation
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

/// Активация премиума по подписанному ключу С ПРИВЯЗКОЙ К УСТРОЙСТВУ. Ключ выдаёт
/// Telegram-бот (@Whispromptbot), подписывая его вместе с device_id покупателя, поэтому
/// ключ работает ТОЛЬКО на этом устройстве. Крипта: EC P-256 / ECDSA-SHA256 (CryptoKit).
/// Приложение хранит ТОЛЬКО публичный ключ. Формат ключа:
///   base64url(payload) + "." + base64url(signatureDER),  payload = "PREMIUM:<deviceId>:<time>[:note]"
/// Совпадает с Android LicenseManager.kt и tools/tgbot/license.py.
enum LicenseManager {

    /// Публичный ключ EC P-256 (X.509 SubjectPublicKeyInfo, Base64). Приватный — только в боте.
    /// ТОТ ЖЕ ключ, что зашит в Android-версии.
    private static let PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Ul6wn5+DeVh3mEfB2Lxt7cdhMZJ42Kax7oYGD1Q0RkvG7ZILacxONlhjuQiSFr6aUK5LF789hzotphU64/xTw=="

    /// Хэндл Telegram-бота, где покупается ключ (для подсказки в UI).
    static let TG_BOT = "@Whispromptbot"

    static func isConfigured() -> Bool { !PUBLIC_KEY_B64.isEmpty }

    /// Идентификатор ЭТОГО устройства — показываем пользователю, к нему привязан ключ.
    /// На iOS это identifierForVendor (аналог Android ID).
    static func deviceId() -> String {
        #if canImport(UIKit)
        return UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
        #else
        return "unknown"
        #endif
    }

    /// Проверить и активировать ключ: подпись верна И ключ выдан для ЭТОГО устройства → премиум.
    @discardableResult
    static func activate(_ key: String) -> Bool {
        guard verify(key) else { return false }
        PremiumManager.grantFromKey(key.trimmingCharacters(in: .whitespacesAndNewlines))
        return true
    }

    static func verify(_ key: String) -> Bool {
        let parts = key.trimmingCharacters(in: .whitespacesAndNewlines).split(
            separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2,
              let payload = base64urlDecode(parts[0]),
              let sigData = base64urlDecode(parts[1]),
              let pubDER = Data(base64Encoded: PUBLIC_KEY_B64) else { return false }
        do {
            let pub = try P256.Signing.PublicKey(derRepresentation: pubDER)
            let sig = try P256.Signing.ECDSASignature(derRepresentation: sigData)
            // isValidSignature(_:for:) само хэширует данные SHA-256 → эквивалент SHA256withECDSA.
            guard pub.isValidSignature(sig, for: payload) else { return false }
            let payloadStr = String(decoding: payload, as: UTF8.self)
            return payloadStr.hasPrefix("PREMIUM:\(deviceId()):")
        } catch {
            return false
        }
    }

    /// base64url → Data (добавляет паддинг, переводит -_ в +/).
    private static func base64urlDecode(_ s: String) -> Data? {
        var str = s.replacingOccurrences(of: "-", with: "+")
                   .replacingOccurrences(of: "_", with: "/")
        let rem = str.count % 4
        if rem > 0 { str += String(repeating: "=", count: 4 - rem) }
        return Data(base64Encoded: str)
    }
}
