package com.example.teleprompter

import android.content.Context

/**
 * Пользовательская кастомизация: что делает каждый жест тачбара во время показа и через
 * сколько секунд текст скрывается после полной авто-прокрутки. Всё хранится в [Prefs].
 */
object Customization {

    /** Действия, которые можно назначить на жест тачбара. */
    enum class GestureAction(val labelRes: Int) {
        NONE(R.string.gesture_none),
        SLEEP_WAKE(R.string.gesture_sleep_wake),
        SCROLL_FWD(R.string.gesture_scroll_fwd),
        SCROLL_BACK(R.string.gesture_scroll_back),
        PAUSE_RESUME(R.string.gesture_pause_resume),
        EXIT(R.string.gesture_exit);

        companion object {
            fun byName(name: String?, def: GestureAction): GestureAction =
                entries.firstOrNull { it.name == name } ?: def
        }
    }

    /** Жесты + их ключи в Prefs и действия по умолчанию. */
    enum class Gesture(val key: String, val titleRes: Int, val default: GestureAction) {
        TAP(Prefs.KEY_GEST_TAP, R.string.gesture_tap, GestureAction.SLEEP_WAKE),
        DOUBLE(Prefs.KEY_GEST_DOUBLE, R.string.gesture_double, GestureAction.EXIT),
        SWIPE_FWD(Prefs.KEY_GEST_SWIPE_FWD, R.string.gesture_swipe_fwd, GestureAction.SCROLL_FWD),
        SWIPE_BACK(Prefs.KEY_GEST_SWIPE_BACK, R.string.gesture_swipe_back, GestureAction.SCROLL_BACK)
    }

    fun action(context: Context, gesture: Gesture): GestureAction =
        GestureAction.byName(Prefs.getString(context, gesture.key, ""), gesture.default)

    fun setAction(context: Context, gesture: Gesture, action: GestureAction) {
        Prefs.putString(context, gesture.key, action.name)
    }

    /** Варианты «скрыть текст через N секунд после конца» (0 = никогда). */
    val AUTOHIDE_OPTIONS = intArrayOf(0, 3, 5, 10, 30)
    const val AUTOHIDE_DEFAULT = 5

    fun autoHideSec(context: Context): Int =
        Prefs.getInt(context, Prefs.KEY_AUTOHIDE_SEC, AUTOHIDE_DEFAULT)

    fun setAutoHideSec(context: Context, sec: Int) {
        Prefs.putInt(context, Prefs.KEY_AUTOHIDE_SEC, sec)
    }
}
