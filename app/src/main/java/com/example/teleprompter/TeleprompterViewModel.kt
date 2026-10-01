package com.example.teleprompter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PlayState { STOPPED, PLAYING, PAUSED }

/**
 * Состояние телесуфлёра. Живёт дольше пересоздания Activity (поворот экрана).
 *
 * Важно про G2: очки получают ВЕСЬ сценарий разом (см. BleService.sendScript) и
 * прокручивают его сами (листание тачбаром на дужке). Поэтому здесь нет потоковой
 * отправки кусков — только прокрутка превью НА ТЕЛЕФОНЕ для оператора. Скорость
 * управляет именно скоростью превью.
 */
class TeleprompterViewModel : ViewModel() {

    var script by mutableStateOf("")
    var currentTextId: Long? = null
    var currentTitle by mutableStateOf<String?>(null)  // заголовок выбранного текста (для карточки)
    var wpm by mutableFloatStateOf(130f)     // скорость чтения, слов в минуту (WPM)
    var scrollMode by mutableStateOf(ScrollMode.APP_AUTO) // режим прокрутки на очках
    var bigText by mutableStateOf(false)     // размер текста: false=мелкий (size 12, официальная широкая колонка), true=крупный (size 16)
    var brightness by mutableFloatStateOf(60f) // яркость очков 0..100 (канал 0x0920); дефолт = как на подключении
    var colWidth by mutableFloatStateOf(44f) // ширина колонки на очках, символов в строке (44 = как у официалки)
    var narrowScreen by mutableStateOf(false) // узкий экран: полоса в 4 строки по центру (как в официалке)

    var playState by mutableStateOf(PlayState.STOPPED)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null

    /** Запускает показ (позицию превью теперь ведёт РЕАЛЬНАЯ позиция на очках —
     *  Activity опрашивает BleService.hubProgress и зовёт [syncProgress]). */
    fun start() {
        if (playState == PlayState.PLAYING) return
        if (playState == PlayState.STOPPED) progress = 0f
        playState = PlayState.PLAYING
        job?.cancel()
    }

    /** Синхронизация с реальной позицией показа на очках (0..1). */
    fun syncProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
    }

    fun pause() {
        if (playState != PlayState.PLAYING) return
        playState = PlayState.PAUSED
        job?.cancel()
    }

    fun stop() {
        job?.cancel()
        progress = 0f
        playState = PlayState.STOPPED
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
