package com.example.teleprompter

import android.content.Context

object Prefs {

    private const val FILE = "teleprompter_prefs"

    // --- Авторизация по паролю (Admin/Guest) ---
    const val KEY_ADMIN_HASH = "admin_hash"            // хэш админ-пароля (после смены; иначе дефолт)
    const val KEY_GUEST_HASH = "guest_hash"            // хэш текущего гостевого кода
    const val KEY_GUEST_USED = "guest_used"            // гостевой код уже использован (одноразовый)
    const val KEY_SESSION_EXPIRY = "session_expiry"    // истечение сессии (unix ms)
    const val KEY_SESSION_IS_ADMIN = "session_is_admin" // текущая сессия — админская

    const val KEY_THEME = "theme_mode"
    const val KEY_LANG = "app_language"
    const val KEY_COL_WIDTH = "column_width"
    const val KEY_SPEED = "scroll_speed"
    const val KEY_BRIGHTNESS = "brightness"
    const val KEY_TEXTS = "saved_texts"
    const val KEY_COUNTDOWN = "countdown_enabled" // обратный отсчёт 3-2-1 перед авто-показом (по умолчанию вкл.)
    // --- Кастомизация жестов тачбара + авто-скрытие текста ---
    const val KEY_GEST_TAP = "gest_tap"
    const val KEY_GEST_DOUBLE = "gest_double"
    const val KEY_GEST_SWIPE_FWD = "gest_swipe_fwd"
    const val KEY_GEST_SWIPE_BACK = "gest_swipe_back"
    const val KEY_AUTOHIDE_SEC = "autohide_sec"    // через сколько секунд скрыть текст после конца (0 = никогда)

    // --- Режим приложения (публичный / аренда) ---
    const val KEY_APP_MODE = "app_mode"                // 0 = публичный (без пароля), 1 = аренда

    // --- Премиум (покупка через Google Play Billing) ---
    const val KEY_PREMIUM_HASH = "premium_hash"        // хэш подтверждённой покупки (не открытый флаг)
    const val KEY_PREMIUM_ORDER = "premium_order"      // orderId покупки (для восстановления)

    // --- Защита от подмены ---
    const val KEY_TAMPERED = "integrity_tampered"      // выставляется при обнаружении вмешательства

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, def: String): String =
        prefs(context).getString(key, def) ?: def

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun getInt(context: Context, key: String, def: Int): Int =
        prefs(context).getInt(key, def)

    fun putInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    fun getBool(context: Context, key: String, def: Boolean): Boolean =
        prefs(context).getBoolean(key, def)

    fun putBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun getLong(context: Context, key: String, def: Long): Long =
        prefs(context).getLong(key, def)

    fun putLong(context: Context, key: String, value: Long) {
        prefs(context).edit().putLong(key, value).apply()
    }
}
