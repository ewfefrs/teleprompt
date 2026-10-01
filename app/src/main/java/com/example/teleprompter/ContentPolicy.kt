package com.example.teleprompter

import android.content.Context

/**
 * Политика контента с учётом тарифа. На БЕСПЛАТНОМ тарифе:
 *  • показывается не более [PremiumManager.MAX_WORDS_FREE] слов (остальное обрезается,
 *    [Result.truncated] = true);
 *  • в КОНЕЦ каждого текста дописывается промо со ссылкой на бота (локализовано по языку).
 * Премиум снимает и лимит, и промо — текст идёт целиком.
 */
object ContentPolicy {

    data class Result(val text: String, val truncated: Boolean)

    fun apply(context: Context, raw: String): Result {
        if (PremiumManager.wordLimit(context) == Int.MAX_VALUE) return Result(raw, false)  // премиум
        if (raw.isBlank()) return Result(raw, false)

        val limit = PremiumManager.MAX_WORDS_FREE
        // Режем по границам слов, сохраняя исходные пробелы/переводы строк.
        var count = 0
        var cutAt = -1
        for (m in Regex("\\S+").findAll(raw)) {
            count++
            if (count == limit) { cutAt = m.range.last + 1; break }
        }
        val truncated = count > limit && cutAt >= 0
        val body = if (truncated) raw.substring(0, cutAt).trimEnd() else raw

        // Промо в конце каждого текста бесплатной версии (в языке интерфейса).
        val promo = "\n\n" + context.getString(R.string.promo_buy_full, LicenseManager.TG_BOT)
        return Result(body + promo, truncated)
    }
}
