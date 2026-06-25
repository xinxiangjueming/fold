package com.example.fold.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class MusicEvent { PREV, PAUSE, RESUME, NEXT, STOP }

object MusicEventBus {
    private val _events = MutableSharedFlow<MusicEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    fun send(event: MusicEvent) { _events.tryEmit(event) }
}
