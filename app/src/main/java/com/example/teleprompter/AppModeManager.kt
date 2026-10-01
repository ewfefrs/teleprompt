package com.example.teleprompter

import android.content.Context

/**
 * Два режима работы приложения:
 *
 *  • [PUBLIC] — обычная версия, которую скачивают из магазина. Вход БЕЗ пароля,
 *    действуют лимиты бесплатного тарифа (см. [PremiumManager]), которые снимает премиум.
 *
 *  • [RENTAL] — режим аренды очков: включается владельцем. Тогда работает гейт доступа
 *    (Admin Passcode / одноразовый Guest Code — см. [SecurityManager]).
 *
 * По умолчанию — [PUBLIC]. Включить аренду может любой (это настройка владельца), но
 * ВЫКЛЮЧИТЬ её обратно можно только с админ-паролем — чтобы арендатор не сбросил режим.
 */
object AppModeManager {

    const val PUBLIC = 0
    const val RENTAL = 1

    fun mode(context: Context): Int = Prefs.getInt(context, Prefs.KEY_APP_MODE, PUBLIC)

    fun isRental(context: Context): Boolean = mode(context) == RENTAL

    /** Включить режим аренды (доступно без пароля — настройка владельца). */
    fun enableRental(context: Context) = Prefs.putInt(context, Prefs.KEY_APP_MODE, RENTAL)

    /**
     * Выключить аренду и вернуться в публичный режим. Требует верного админ-пароля,
     * иначе арендатор смог бы снять гейт. Возвращает true при успехе.
     */
    fun disableRental(context: Context, security: SecurityManager, adminCode: String): Boolean {
        if (!security.verifyAdmin(adminCode)) return false
        Prefs.putInt(context, Prefs.KEY_APP_MODE, PUBLIC)
        return true
    }
}
