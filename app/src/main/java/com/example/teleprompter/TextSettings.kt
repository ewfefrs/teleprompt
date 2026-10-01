package com.example.teleprompter

import android.content.Context
import org.json.JSONObject

/**
 * Память настроек показа НА КАЖДЫЙ текст: скорость (WPM), ширина колонки, узкий экран,
 * яркость, режим и размер. Хранится в [Prefs] по ключу «tcfg_<id>». При выборе текста
 * настройки применяются, при изменении — сохраняются для текущего текста.
 */
object TextSettings {

    data class Cfg(
        val wpm: Float,
        val col: Int,
        val narrow: Boolean,
        val brightness: Int,
        val mode: ScrollMode,
        val big: Boolean
    )

    fun load(context: Context, id: Long): Cfg? {
        val raw = Prefs.getString(context, key(id), "")
        if (raw.isEmpty()) return null
        return runCatching {
            val o = JSONObject(raw)
            Cfg(
                wpm = o.optDouble("wpm", 130.0).toFloat(),
                col = o.optInt("col", 44),
                narrow = o.optBoolean("narrow", false),
                brightness = o.optInt("bri", 60),
                mode = if (o.optString("mode") == ScrollMode.MANUAL.name) ScrollMode.MANUAL else ScrollMode.APP_AUTO,
                big = o.optBoolean("big", false)
            )
        }.getOrNull()
    }

    fun save(
        context: Context, id: Long, wpm: Float, col: Int, narrow: Boolean,
        brightness: Int, mode: ScrollMode, big: Boolean
    ) {
        val o = JSONObject()
            .put("wpm", wpm.toDouble())
            .put("col", col)
            .put("narrow", narrow)
            .put("bri", brightness)
            .put("mode", mode.name)
            .put("big", big)
        Prefs.putString(context, key(id), o.toString())
    }

    private fun key(id: Long) = "tcfg_$id"
}
