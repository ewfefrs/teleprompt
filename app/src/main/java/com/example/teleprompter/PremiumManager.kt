package com.example.teleprompter

import android.content.Context
import java.security.MessageDigest

/**
 * Право на премиум-версию. Хранится НЕ открытым флагом, а как SHA-256(purchaseToken + pepper)
 * — по образцу [SecurityManager], чтобы простое редактирование Prefs («premium=true») не
 * включало премиум. Премиум выдаётся после активации подписанного лицензионного ключа
 * (см. [LicenseManager.activate] → [grantFromPurchase]); ключ покупается в Telegram-боте.
 *
 * Бесплатный тариф: максимум [MAX_TEXTS_FREE] текстов и [MAX_WORDS_FREE] слов в тексте.
 * Премиум снимает оба лимита.
 */
object PremiumManager {

    const val MAX_TEXTS_FREE = 2
    const val MAX_WORDS_FREE = 100

    /** ID товара в Google Play Console (одноразовая покупка «разблокировать премиум»). */
    const val PRODUCT_ID = "premium_unlock"

    fun isPremium(context: Context): Boolean {
        // Личная авторская сборка: премиум всегда включён, лимиты сняты (build type "author").
        if (BuildConfig.AUTHOR_UNLOCKED) return true
        // Подмена (реверс/патч) сбрасывает премиум — см. IntegrityGuard.
        if (Prefs.getBool(context, Prefs.KEY_TAMPERED, false)) return false
        return Prefs.getString(context, Prefs.KEY_PREMIUM_HASH, "").isNotBlank()
    }

    /** Выдать премиум после подтверждённой покупки. token — purchaseToken из Play Billing. */
    fun grantFromPurchase(context: Context, token: String, orderId: String?) {
        if (token.isBlank()) return
        Prefs.putString(context, Prefs.KEY_PREMIUM_HASH, hash(token))
        if (!orderId.isNullOrBlank()) Prefs.putString(context, Prefs.KEY_PREMIUM_ORDER, orderId)
    }

    /** Снять премиум (возврат средств / отзыв покупки Google Play). */
    fun revoke(context: Context) {
        Prefs.putString(context, Prefs.KEY_PREMIUM_HASH, "")
        Prefs.putString(context, Prefs.KEY_PREMIUM_ORDER, "")
    }

    // --- Лимиты бесплатного тарифа ------------------------------------------

    /** Можно ли сохранить ЕЩЁ один текст (при [currentCount] уже сохранённых). */
    fun canAddText(context: Context, currentCount: Int): Boolean =
        isPremium(context) || currentCount < MAX_TEXTS_FREE

    /** Лимит слов на текст (Int.MAX_VALUE для премиума). */
    fun wordLimit(context: Context): Int =
        if (isPremium(context)) Int.MAX_VALUE else MAX_WORDS_FREE

    // --- Хэширование (тот же приём, что в SecurityManager) ------------------

    private fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(token.trim().toByteArray(Charsets.UTF_8) + pepper())
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) hex.append("%02x".format(b.toInt() and 0xFF))
        return hex.toString()
    }

    private fun pepper(): ByteArray {
        val b = ByteArray(PEPPER_OBF.size)
        for (i in PEPPER_OBF.indices) b[i] = (PEPPER_OBF[i] xor KEY).toByte()
        return b
    }

    private const val KEY = 0x5C
    // Pepper для хэша покупки (XOR-обфусцирован), отдельный от SecurityManager.
    private val PEPPER_OBF = intArrayOf(
        0x32, 0x39, 0x2A, 0x37, 0x2D, 0x2E, 0x37, 0x71, 0x28, 0x2A, 0x2B, 0x28, 0x2A, 0x6E, 0x6C, 0x6E, 0x6A
    )
}
