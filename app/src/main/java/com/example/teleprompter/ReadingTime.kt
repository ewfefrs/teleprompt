package com.example.teleprompter

/**
 * Оценка времени, за которое ВЕСЬ текст прочитается в авто-режиме при заданной скорости
 * (слов в минуту). Темп авто-прокрутки на очках привязан к числу слов (см.
 * [BleService.recomputeLps]), поэтому оценка = слова / WPM.
 */
object ReadingTime {

    /** Число слов в тексте (\n и пунктуация игнорируются как разделители). */
    fun wordCount(text: String): Int {
        val t = text.replace("\\n", "\n").trim()
        if (t.isEmpty()) return 0
        return t.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    /** Предполагаемое время чтения в СЕКУНДАХ при скорости [wpm] (слов/мин). */
    fun estimateSeconds(text: String, wpm: Float): Int {
        val words = wordCount(text)
        if (words == 0 || wpm <= 0f) return 0
        return Math.round(words / wpm * 60f)
    }

    /**
     * Более ТОЧНОЕ время (сек): за сколько авто-прокрутка ДОХОДИТ до конца — т.е. когда
     * текст перестаёт двигаться. В отличие от [estimateSeconds], учитывает, что последнее
     * видимое окно из [windowRows] строк НЕ прокручивается (показывается сразу), и считает
     * по той же модели, что реальный темп на очках ([BleService.recomputeLps]): строк/сек
     * из WPM и среднего числа слов в строке при ширине колонки [cols].
     */
    fun estimateSeconds(text: String, wpm: Float, cols: Int, windowRows: Int): Int {
        if (wpm <= 0f) return 0
        val wrapped = EvenG2Protocol.wrapLines(text, cols.coerceIn(8, 44), center = false)
        val n = wrapped.size
        val words = wordCount(text)
        if (words == 0 || n == 0) return 0
        val wordsPerLine = (words.toFloat() / n).coerceAtLeast(0.8f)
        val lps = ((wpm / 60f) / wordsPerLine).coerceIn(0.02f, 20f)
        val scrollLines = (n - windowRows).coerceAtLeast(0)
        return Math.round(scrollLines / lps)
    }

    /** Короткая метка «м:сс» / «сс c» для показа на очках (без локализованных слов). */
    fun shortLabel(text: String, wpm: Float): String {
        val s = estimateSeconds(text, wpm)
        if (s <= 0) return ""
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "%d:%02d".format(m, sec) else "0:%02d".format(sec)
    }
}
