package com.example.fold.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.example.fold.audio.UsbAudioNative
import com.example.fold.audio.UsbExclusiveRenderersFactory
import com.example.fold.service.MusicNotificationService

/**
 * 音乐播放器全局持有者（单例）
 * 支持普通模式（系统 mixer）和 USB 独占模式（libusb 绕过 AudioFlinger）
 */
object MusicPlayerHolder {

    private const val TAG = "MusicPlayerHolder"

    var exoPlayer: ExoPlayer? = null
        private set
    var mediaSession: MediaSession? = null
        private set
    var playlist: List<String> = emptyList()
        private set
    var lastFilePath: String = ""

    /* ── USB 独占模式 ── */
    var usbAudio: UsbAudioNative? = null
        private set
    var exclusiveDevice: UsbAudioNative.UsbAudioDevice? = null
        private set
    var exclusiveFormat: UsbAudioNative.AudioFormat? = null
        private set
    var isExclusiveMode = mutableStateOf(false)
        private set

    /**
     * 检查设备是否支持 USB 独占模式（需要 Android 14+）
     */
    fun isExclusiveSupported(): Boolean = Build.VERSION.SDK_INT >= 34

    fun getOrCreate(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = if (isExclusiveMode.value && usbAudio != null) {
                buildExclusivePlayer(context)
            } else {
                buildNormalPlayer(context)
            }
            // 不创建 Media3 MediaSession — MusicNotificationService 已有自己的 MediaSessionCompat
            // 两个 MediaSession 同时存在会导致通知链 IPC 双倍，造成严重卡顿
        }
        return exoPlayer!!
    }

    private fun buildNormalPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context.applicationContext).build()
    }

    private fun buildExclusivePlayer(context: Context): ExoPlayer {
        val ua = usbAudio!!
        val renderersFactory = UsbExclusiveRenderersFactory(context.applicationContext, ua)
        val player = ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(renderersFactory)
            .build()
        // 独占模式下静音系统 AudioTrack，避免与 libusb 输出叠加产生杂音
        // 注意：内核 ALSA 路径可能仍会尝试访问 USB 设备（报 "usb device is not connected"），
        // 但由于 player.volume=0，不会有可听噪音。
        // libusb 独占了 USB 接口后内核路径自动失效。
        player.volume = 0f
        return player
    }

    /**
     * 启用 USB 独占模式（设备必须已通过 openAndInit 打开）
     * 只设置标志，不重建 player — 调用方负责释放旧 player 并重新 init
     */
    fun enableExclusiveMode(
        context: Context,
        device: UsbAudioNative.UsbAudioDevice,
        format: UsbAudioNative.AudioFormat,
    ): Boolean {
        val ua = usbAudio ?: return false
        exclusiveDevice = device
        exclusiveFormat = format
        isExclusiveMode.value = true
        return true
    }

    /**
     * 禁用独占模式，回到系统 mixer
     * 只设置标志 — 调用方负责释放旧 player 并重新 init
     */
    fun disableExclusiveMode(context: Context) {
        isExclusiveMode.value = false
        exclusiveDevice = null
        exclusiveFormat = null
        usbAudio?.release()
        usbAudio = null
    }

    /**
     * 释放当前 player（供模式切换时调用）
     * 释放后 getOrCreate() 会按当前模式创建新 player
     */
    fun releasePlayer() {
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
    }

    fun loadPlaylist(context: Context, paths: List<String>, startIndex: Int) {
        val player = getOrCreate(context)
        playlist = paths
        player.clearMediaItems()
        paths.forEach { path ->
            player.addMediaItem(MediaItem.fromUri(Uri.parse("file://$path")))
        }
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        player.seekTo(startIndex.coerceIn(0, paths.size - 1), 0)
        player.playWhenReady = true

        context.startForegroundService(Intent(context, MusicNotificationService::class.java))
    }

    fun release(context: Context) {
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        playlist = emptyList()
        usbAudio?.release()
        usbAudio = null
        isExclusiveMode.value = false
        exclusiveDevice = null
        exclusiveFormat = null
        MiniPlayerState.clear()
        context.stopService(Intent(context, MusicNotificationService::class.java))
    }

    fun isActive(): Boolean = exoPlayer != null

    /**
     * 外部设置 usbAudio 实例（从 Dialog 扫描后缓存）
     */
    fun setUsbAudio(ua: UsbAudioNative) {
        usbAudio = ua
    }
}
