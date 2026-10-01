package com.example.teleprompter

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Язык интерфейса. Хранится в Prefs; применяется через [wrap] в attachBaseContext.
 * Тот же выбор использует фоновый [BleService] для меню на очках (locStr → выбранная локаль),
 * поэтому смена языка меняет и текст меню на очках.
 */
object LocaleManager {

    const val ENGLISH = "en"
    const val RUSSIAN = "ru"

    /** Поддерживаемые языки: код + ресурс с названием (эндонимом). Порядок = порядок в списке. */
    data class Lang(val code: String, val nameRes: Int)

    val SUPPORTED = listOf(
        Lang(ENGLISH, R.string.language_en),
        Lang(RUSSIAN, R.string.language_ru),
        Lang("es", R.string.language_es),
        Lang("fr", R.string.language_fr),
        Lang("de", R.string.language_de),
        Lang("pt", R.string.language_pt),
        Lang("it", R.string.language_it),
    )

    fun language(context: Context): String {
        // По умолчанию — АНГЛИЙСКИЙ (включая экран разрешений при первом запуске);
        // другой язык выбирается в Настройках. Системный язык не подхватываем.
        return Prefs.getString(context, Prefs.KEY_LANG, "").ifEmpty { ENGLISH }
    }

    fun setLanguage(context: Context, lang: String) = Prefs.putString(context, Prefs.KEY_LANG, lang)

    fun wrap(base: Context): Context {
        val locale = Locale(language(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
