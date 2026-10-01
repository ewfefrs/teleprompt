package com.example.teleprompter

import android.content.Context

object ThemeManager {

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    fun mode(context: Context): Int = Prefs.getInt(context, Prefs.KEY_THEME, SYSTEM)

    fun setMode(context: Context, mode: Int) = Prefs.putInt(context, Prefs.KEY_THEME, mode)
}
