package com.example.fold.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class TtsEvent { PLAY, PAUSE, RESUME, STOP, NEXT, PREV }

/** MediaSession 回调 → ViewModel 的桥梁 */
object TtsEventBus {
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    fun send(event: TtsEvent) {
        _events.tryEmit(event)
    }
}
