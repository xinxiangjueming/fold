package com.example.fold.ui.reader

import com.example.fold.util.FoldLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 阅读器事件总线 — 用于 Activity 与阅读器之间的通信
 * 目前用于：音量键翻页事件
 * 使用 Channel 保证每个事件都被接收（SharedFlow 可能丢失）
 */
object ReaderEventBus {
    private const val TAG = "ReaderEventBus"
    private val _events = Channel<ReaderEvent>(capacity = 8)
    val events = _events.receiveAsFlow()

    fun emit(event: ReaderEvent) {
        val result = _events.trySend(event)
        FoldLogger.i(TAG, "emit: event=$event, result=$result")
    }
}

enum class ReaderEvent {
    VOLUME_UP,
    VOLUME_DOWN
}
