package com.example.fold.ui.player

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮小窗播放器的全局状态（单例）
 * 由 AudioPlayerViewModel 更新，由 NavGraph 中的悬浮窗读取
 */
data class MiniPlayerUiState(
    val title: String = "",
    val isPlaying: Boolean = false,
    val albumArt: Bitmap? = null,
    val filePath: String = "",
)

object MiniPlayerState {
    private val _state = MutableStateFlow(MiniPlayerUiState())
    val state: StateFlow<MiniPlayerUiState> = _state.asStateFlow()

    fun update(title: String, isPlaying: Boolean, albumArt: Bitmap?, filePath: String) {
        _state.value = MiniPlayerUiState(
            title = title,
            isPlaying = isPlaying,
            albumArt = albumArt,
            filePath = filePath,
        )
    }

    fun updatePlaying(isPlaying: Boolean) {
        _state.value = _state.value.copy(isPlaying = isPlaying)
    }

    fun clear() {
        _state.value = MiniPlayerUiState()
    }

    val isActive: Boolean get() = _state.value.filePath.isNotEmpty()
}
