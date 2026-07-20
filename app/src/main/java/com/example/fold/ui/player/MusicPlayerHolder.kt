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
import com.example.fold.audio.AudioFormat
import com.example.fold.audio.DspRenderersFactory
import com.example.fold.audio.PlaybackMemoryMonitor
import com.example.fold.audio.UsbAudioDevice
import com.example.fold.audio.UsbAudioDeviceManager
import com.example.fold.audio.UsbAudioDeviceInfo
import com.example.fold.audio.UsbAudioStream
import com.example.fold.audio.UsbExclusiveRenderersFactory
import com.example.fold.service.MusicNotificationService

object MusicPlayerHolder {

    private const val TAG = "MusicPlayerHolder"

    @Volatile var exoPlayer: ExoPlayer? = null
        private set
    @Volatile var mediaSession: MediaSession? = null
        private set
    @Volatile var playlist: List<String> = emptyList()
        private set
    @Volatile var lastFilePath: String = ""

    // 歌词缓存：key=歌曲路径, value=解析后的歌词列表
    @Volatile var cachedLyrics: List<Pair<Long, String>> = emptyList()
    @Volatile var cachedLyricsPath: String = ""

    @Volatile var usbDeviceInfo: UsbAudioDeviceInfo? = null
        private set
    @Volatile var usbStream: UsbAudioStream? = null
        private set
    @Volatile var exclusiveDevice: UsbAudioDevice? = null
        private set
    @Volatile var exclusiveFormat: AudioFormat? = null
        private set
    var isExclusiveMode = mutableStateOf(false)
        private set

    fun isExclusiveSupported(): Boolean {
        // USB exclusive mode works on Android 8+ (API 26+)
        // The UsbAudioStream uses Linux usbdevfs which is available on all Android versions
        return Build.VERSION.SDK_INT >= 26
    }

    fun getOrCreate(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            val mode = if (isExclusiveMode.value && usbStream != null) "exclusive" else "normal"
            exoPlayer = if (isExclusiveMode.value && usbStream != null) {
                buildExclusivePlayer(context)
            } else {
                buildNormalPlayer(context)
            }
            com.example.fold.util.FoldLogger.i(TAG, "ExoPlayer created: mode=$mode, sessionId=${exoPlayer?.audioSessionId}")
        }
        return exoPlayer!!
    }

    private fun buildNormalPlayer(context: Context): ExoPlayer {
        val renderersFactory = DspRenderersFactory(context.applicationContext)
        return ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(renderersFactory)
            .build()
    }

    private fun buildExclusivePlayer(context: Context): ExoPlayer {
        val stream = usbStream!!
        val info = usbDeviceInfo
        val renderersFactory = UsbExclusiveRenderersFactory(
            context.applicationContext, stream, info,
            onStreamStopped = { handleStreamStopped(context) }
        )
        val player = ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(renderersFactory)
            .build()
        player.volume = 0f
        return player
    }

    /** Called when native USB stream stops due to ENOENT. Recreates the stream. */
    private fun handleStreamStopped(context: Context) {
        android.util.Log.w(TAG, "USB stream stopped — recreating")
        try {
            val oldStream = usbStream ?: return
            val oldInfo = usbDeviceInfo ?: return
            val format = exclusiveFormat ?: return
            val device = exclusiveDevice ?: return

            val positionMs = exoPlayer?.currentPosition ?: 0L
            val wasPlaying = exoPlayer?.playWhenReady ?: false

            // Release old stream
            try { oldStream.release() } catch (_: Exception) {}
            usbStream = null

            // Close old UsbDeviceConnection to release kernel usb_device
            try {
                oldInfo.connection.close()
                android.util.Log.i(TAG, "Closed old UsbDeviceConnection")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to close old connection: ${e.message}")
            }

            // Reopen device to get fresh connection and fd
            val newInfo = UsbAudioDeviceManager.openAndInit(device, context)
            if (newInfo == null) {
                android.util.Log.e(TAG, "Failed to reopen USB device")
                disableExclusiveMode(context)
                return
            }
            usbDeviceInfo = newInfo

            // Create new stream with fresh connection
            val newStream = UsbAudioStream.create(newInfo, format.sampleRate, format.channels, format.bitDepth)
            if (newStream == null) {
                android.util.Log.e(TAG, "Failed to recreate USB stream")
                disableExclusiveMode(context)
                return
            }
            usbStream = newStream

            // Rebuild player with new stream
            exoPlayer?.release()
            exoPlayer = buildExclusivePlayer(context)
            exoPlayer?.volume = 0f

            // Resume playback from saved position
            if (playlist.isNotEmpty()) {
                val player = exoPlayer!!
                player.clearMediaItems()
                playlist.forEach { path ->
                    player.addMediaItem(MediaItem.fromUri(android.net.Uri.parse("file://$path")))
                }
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.prepare()
                player.seekTo(positionMs.coerceAtLeast(0))
                player.playWhenReady = wasPlaying
            }

            android.util.Log.i(TAG, "USB stream recreated with new fd=${newInfo.fd}, resumed from ${positionMs}ms")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleStreamStopped failed: ${e.message}")
            disableExclusiveMode(context)
        }
    }

    fun enableExclusiveMode(
        context: Context,
        device: UsbAudioDevice,
        format: AudioFormat,
        deviceInfo: UsbAudioDeviceInfo,
    ): Boolean {
        exclusiveDevice = device
        exclusiveFormat = format
        usbDeviceInfo = deviceInfo
        isExclusiveMode.value = true
        com.example.fold.util.FoldLogger.i(TAG, "Exclusive mode enabled: device=${device.productName}, " +
            "format=${format.sampleRate}Hz/${format.bitDepth}bit/${format.channels}ch, " +
            "epOut=0x${deviceInfo.endpointOut.toString(16)}, maxPkt=${deviceInfo.maxPacketSize}")
        return true
    }

    fun disableExclusiveMode(context: Context) {
        val wasEnabled = isExclusiveMode.value
        isExclusiveMode.value = false
        exclusiveDevice = null
        exclusiveFormat = null
        // release() is idempotent (safe to call multiple times)
        try {
            usbStream?.release()
        } catch (e: Exception) {
            android.util.Log.e("MusicPlayerHolder", "usbStream.release() failed: ${e.message}")
        }
        usbStream = null
        usbDeviceInfo = null
        if (wasEnabled) {
            com.example.fold.util.FoldLogger.i(TAG, "Exclusive mode disabled")
        }
    }

    /** Force reset all state (used when USB detach cleanup fails) */
    fun forceReset() {
        isExclusiveMode.value = false
        exclusiveDevice = null
        exclusiveFormat = null
        usbStream = null
        usbDeviceInfo = null
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
    }

    fun setStream(stream: UsbAudioStream) {
        usbStream = stream
    }

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

        // 启动内存监控
        PlaybackMemoryMonitor.start()

        context.startForegroundService(Intent(context, MusicNotificationService::class.java))
    }

    fun release(context: Context) {
        com.example.fold.util.FoldLogger.i(TAG, "release: playlist=${playlist.size}items, exclusive=${isExclusiveMode.value}")
        // 停止内存监控
        PlaybackMemoryMonitor.stop()
        mediaSession?.release()
        mediaSession = null
        // Stop player FIRST — triggers sink.release() which drains + releases stream
        exoPlayer?.release()
        exoPlayer = null
        playlist = emptyList()
        cachedLyrics = emptyList()
        cachedLyricsPath = ""
        // Safety: drain + release stream if sink didn't (idempotent)
        try {
            usbStream?.drain()
            usbStream?.release()
        } catch (_: Exception) {}
        usbStream = null
        usbDeviceInfo = null
        isExclusiveMode.value = false
        exclusiveDevice = null
        exclusiveFormat = null
        MiniPlayerState.clear()
        context.stopService(Intent(context, MusicNotificationService::class.java))
    }

    fun isActive(): Boolean = exoPlayer != null
}
