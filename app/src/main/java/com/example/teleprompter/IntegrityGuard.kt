package com.example.teleprompter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.security.MessageDigest

/**
 * Лёгкая защита от реверс-инжиниринга / подмены. Проверяет на запуске (только в RELEASE):
 *  1. приложение НЕ перепаковано как debuggable;
 *  2. к процессу НЕ подключён отладчик;
 *  3. подпись APK совпадает с ожидаемой (если [EXPECTED_SIG_SHA256] задан).
 *
 * При обнаружении вмешательства выставляется флаг [Prefs.KEY_TAMPERED] — премиум-функции
 * тогда отключаются ([PremiumManager.isPremium] вернёт false). Приложение НЕ падает: жёсткий
 * краш из-за ложного срабатывания хуже, чем мягкая деградация. В DEBUG-сборке проверки
 * пропускаются, чтобы не мешать разработке.
 */
object IntegrityGuard {

    // SHA-256 сертификата подписи РЕЛИЗА в HEX (нижний регистр, без двоеточий). Получить:
    //   keytool -list -v -keystore <release.keystore> -alias <alias>
    // и взять SHA-256, убрав двоеточия и переведя в нижний регистр. Пока пусто — проверка
    // подписи отключена (чтобы не блокировать до настройки ключа).
    private const val EXPECTED_SIG_SHA256 = "323778f756c890245ebde073899aa726d6a3cf413158b1882867cb337d386de1"

    fun check(context: Context) {
        if (BuildConfig.DEBUG) return

        var tampered = false

        // 1. Перепаковка как debuggable (типичный шаг при патче/анализе).
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) tampered = true

        // 2. Подключённый отладчик.
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) tampered = true

        // 3. Подпись APK.
        if (EXPECTED_SIG_SHA256.isNotBlank()) {
            val sig = signatureSha256(context)
            if (sig == null || !sig.equals(EXPECTED_SIG_SHA256, ignoreCase = true)) tampered = true
        }

        // Пишем актуальное состояние каждый запуск: чистый запуск снимает старый флаг.
        Prefs.putBool(context, Prefs.KEY_TAMPERED, tampered)
    }

    private fun signatureSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return null
        val first = signatures.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrNull()
}
