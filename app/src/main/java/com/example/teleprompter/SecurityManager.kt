package com.example.teleprompter

import android.content.Context
import java.security.MessageDigest

/**
 * Оффлайн-авторизация по ПАРОЛЮ (без привязки к Device ID).
 *
 * Две роли:
 *  • Админ — вводит Admin Passcode → сессия на 48 часов. Может сменить админ-пароль
 *    и СОЗДАТЬ гостевой код (только после ввода админ-пароля).
 *  • Гость — вводит одноразовый Guest Code (созданный админом) → сессия на 24 часа.
 *    Код одноразовый: после использования сгорает.
 *
 * Пароли хранятся ТОЛЬКО как SHA-256(пароль + pepper) — открытых паролей в Prefs нет.
 * Дефолтный админ-пароль и pepper зашиты в код В ОБФУСЦИРОВАННОМ виде (XOR), чтобы их
 * нельзя было вытащить простым `strings` из APK. После смены админ-пароля используется
 * сохранённый хэш, дефолт больше не действует.
 */
class SecurityManager(context: Context) {

    private val appContext = context.applicationContext

    // --- Хэширование ---------------------------------------------------------

    private fun hash(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(code.trim().toByteArray(Charsets.UTF_8) + pepper())
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) hex.append("%02x".format(b.toInt() and 0xFF))
        return hex.toString()
    }

    /** Текущий хэш админ-пароля: сохранённый (после смены) либо дефолтный (зашитый). */
    private fun adminHash(): String =
        Prefs.getString(appContext, Prefs.KEY_ADMIN_HASH, "").ifBlank { hash(defaultAdmin()) }

    // --- Админ ---------------------------------------------------------------

    fun verifyAdmin(code: String): Boolean = code.isNotBlank() && hash(code) == adminHash()

    /** Вход админа: при верном пароле — сессия на 48 часов. */
    fun loginAdmin(code: String): Boolean {
        if (!verifyAdmin(code)) return false
        startSession(ADMIN_TTL_MS, admin = true)
        return true
    }

    /** Админ-пароль ещё дефолтный (владелец не задавал свой) — для первичной настройки аренды. */
    fun isDefaultAdmin(): Boolean =
        Prefs.getString(appContext, Prefs.KEY_ADMIN_HASH, "").isBlank()

    /** Первичная установка админ-пароля (при включении режима аренды впервые). */
    fun setInitialAdminPassword(new: String): Boolean {
        if (new.trim().length < 4) return false
        Prefs.putString(appContext, Prefs.KEY_ADMIN_HASH, hash(new))
        return true
    }

    /** Смена админ-пароля: нужен текущий пароль + новый (не короче 4 символов). */
    fun changeAdminPassword(current: String, new: String): Boolean {
        if (!verifyAdmin(current)) return false
        if (new.trim().length < 4) return false
        Prefs.putString(appContext, Prefs.KEY_ADMIN_HASH, hash(new))
        return true
    }

    /**
     * Создать одноразовый гостевой код. Требует ввода админ-пароля (по ТЗ — только
     * после ввода админ-пароля). Сбрасывает флаг «использован».
     */
    fun createGuestCode(adminCode: String, guestCode: String): Boolean {
        if (!verifyAdmin(adminCode)) return false
        if (guestCode.trim().length < 3) return false
        Prefs.putString(appContext, Prefs.KEY_GUEST_HASH, hash(guestCode))
        Prefs.putBool(appContext, Prefs.KEY_GUEST_USED, false)
        return true
    }

    // --- Гость ---------------------------------------------------------------

    /** Вход по гостевому коду: одноразовый, при успехе — сессия на 24 часа. */
    fun loginGuest(code: String): Boolean {
        if (code.isBlank()) return false
        val gh = Prefs.getString(appContext, Prefs.KEY_GUEST_HASH, "")
        if (gh.isBlank() || hash(code) != gh) return false
        if (Prefs.getBool(appContext, Prefs.KEY_GUEST_USED, false)) return false   // уже использован
        Prefs.putBool(appContext, Prefs.KEY_GUEST_USED, true)                      // сжигаем код
        startSession(GUEST_TTL_MS, admin = false)
        return true
    }

    // --- Сессия --------------------------------------------------------------

    private fun startSession(ttlMs: Long, admin: Boolean) {
        Prefs.putLong(appContext, Prefs.KEY_SESSION_EXPIRY, System.currentTimeMillis() + ttlMs)
        Prefs.putBool(appContext, Prefs.KEY_SESSION_IS_ADMIN, admin)
    }

    /** Есть ли действующая сессия (не истекла). */
    fun hasSession(): Boolean {
        val exp = Prefs.getLong(appContext, Prefs.KEY_SESSION_EXPIRY, 0L)
        return exp > 0L && System.currentTimeMillis() < exp
    }

    /** Текущая сессия — админская (иначе гостевая или её нет). */
    fun isAdminSession(): Boolean =
        hasSession() && Prefs.getBool(appContext, Prefs.KEY_SESSION_IS_ADMIN, false)

    /** Часов до конца сессии (0, если истекла). */
    fun sessionHoursLeft(): Int {
        val exp = Prefs.getLong(appContext, Prefs.KEY_SESSION_EXPIRY, 0L)
        val left = exp - System.currentTimeMillis()
        if (left <= 0L) return 0
        return ((left + 3_600_000L - 1) / 3_600_000L).toInt()
    }

    /** Выход: сбрасываем сессию (потребуется снова ввести пароль). */
    fun logout() {
        Prefs.putLong(appContext, Prefs.KEY_SESSION_EXPIRY, 0L)
        Prefs.putBool(appContext, Prefs.KEY_SESSION_IS_ADMIN, false)
    }

    // --- Обфусцированные константы -------------------------------------------

    private fun deobf(arr: IntArray): String {
        val b = ByteArray(arr.size)
        for (i in arr.indices) b[i] = (arr[i] xor KEY).toByte()
        return String(b, Charsets.UTF_8)
    }

    private fun defaultAdmin(): String = deobf(DEFAULT_ADMIN_OBF)
    private fun pepper(): ByteArray = deobf(PEPPER_OBF).toByteArray(Charsets.UTF_8)

    companion object {
        const val ADMIN_TTL_MS = 48L * 60L * 60L * 1000L   // 48 часов
        const val GUEST_TTL_MS = 24L * 60L * 60L * 1000L   // 24 часа

        private const val KEY = 0x5C

        // Дефолтный админ-пароль (XOR-обфусцирован): "EvenG2-Admin-2026".
        // Его вводят при первом запуске; после смены пароля дефолт не действует.
        private val DEFAULT_ADMIN_OBF = intArrayOf(
            0x19, 0x2A, 0x39, 0x32, 0x1B, 0x6E, 0x71, 0x1D, 0x38, 0x31, 0x35, 0x32, 0x71, 0x6E, 0x6C, 0x6E, 0x6A
        )
        // Pepper для хэширования (XOR-обфусцирован).
        private val PEPPER_OBF = intArrayOf(
            0x3B, 0x6E, 0x28, 0x2C, 0x71, 0x2C, 0x39, 0x2C, 0x2C, 0x39, 0x2E, 0x71, 0x6E, 0x6C, 0x6E, 0x6A, 0x71, 0x2A, 0x6D
        )
    }
}
