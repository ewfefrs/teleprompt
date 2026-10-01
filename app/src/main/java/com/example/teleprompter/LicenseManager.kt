package com.example.teleprompter

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Активация премиума по подписанному ключу С ПРИВЯЗКОЙ К УСТРОЙСТВУ (магазин-независимо:
 * Google Play, RuStore, прямой APK). Ключ выдаёт Telegram-бот после оплаты (см. tools/tgbot),
 * подписывая его вместе с [deviceId] покупателя — поэтому ключ работает ТОЛЬКО на этом устройстве
 * и его нельзя передать другому.
 *
 * Криптография: EC P-256 / SHA256withECDSA. Приложение хранит ТОЛЬКО публичный ключ — подделать
 * ключ без приватного (он только в боте) невозможно. Формат ключа:
 *   base64url(payload) + "." + base64url(signatureDER),  payload = "PREMIUM:<deviceId>:<time>[:note]"
 * Совпадает с tools/LicenseGen.java и tools/tgbot/license.py.
 */
object LicenseManager {

    /** Публичный ключ EC P-256 (X.509 SubjectPublicKeyInfo, Base64). Приватный ключ — в боте. */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5Ul6wn5+DeVh3mEfB2Lxt7cdhMZJ42Kax7oYGD1Q0RkvG7ZILacxONlhjuQiSFr6aUK5LF789hzotphU64/xTw=="

    /** Хэндл Telegram-бота, где покупается ключ (для подсказки в UI). */
    const val TG_BOT = "@Whispromptbot"

    fun isConfigured(): Boolean = PUBLIC_KEY_B64.isNotBlank()

    /** Идентификатор ЭТОГО устройства (Android ID) — показываем пользователю, к нему привязан ключ. */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /** Проверить и активировать ключ: подпись верна И ключ выдан для ЭТОГО устройства → премиум. */
    fun activate(context: Context, key: String): Boolean {
        if (!verify(context, key)) return false
        PremiumManager.grantFromPurchase(context, "license:${key.trim()}", null)
        return true
    }

    fun verify(context: Context, key: String): Boolean {
        if (PUBLIC_KEY_B64.isBlank()) return false
        return try {
            val parts = key.trim().split(".")
            if (parts.size != 2) return false
            val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            val payload = Base64.decode(parts[0], flags)
            val sig = Base64.decode(parts[1], flags)
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pub)
            verifier.update(payload)
            if (!verifier.verify(sig)) return false
            // Привязка к устройству: ключ должен быть выдан именно для нашего Android ID.
            String(payload, Charsets.UTF_8).startsWith("PREMIUM:${deviceId(context)}:")
        } catch (_: Exception) {
            false
        }
    }
}
