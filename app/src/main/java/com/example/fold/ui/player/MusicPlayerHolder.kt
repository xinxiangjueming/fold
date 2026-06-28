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
import com.example.fold.audio.UsbAudioDevice
import com.example.fold.audio.UsbAudioDeviceInfo
import com.example.fold.audio.UsbAudioStream
import com.example.fold.audio.UsbExclusiveRenderersFactory
import com.example.fold.service.MusicNotificationService

object MusicPlayerHolder {

    private const val TAG = "MusicPlayerHolder"

    var exoPlayer: ExoPlayer? = null
        private set
    var mediaSession: MediaSession? = null
        private set
    var playlist: List<String> = emptyList()
        private set
    var lastFilePath: String = ""

    var usbDeviceInfo: UsbAudioDeviceInfo? = null
        private set
    var usbStream: UsbAudioStream? = null
        private set
    var exclusiveDevice: UsbAudioDevice? = null
        private set
    var exclusiveFormat: AudioFormat? = null
        private set
    var isExclusiveMode = mutableStateOf(false)
        private set

    fun isExclusiveSupported(): Boolean = Build.VERSION.SDK_INT >= 34

    fun getOrCreate(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = if (isExclusiveMode.value && usbStream != null) {
                buildExclusivePlayer(context)
            } else {
                buildNormalPlayer(context)
            }
        }
        return exoPlayer!!
    }

    private fun buildNormalPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context.applicationContext).build()
    }

    private fun buildExclusivePlayer(context: Context): ExoPlayer {
        val stream = usbStream!!
        val info = usbDeviceInfo
        val renderersFactory = UsbExclusiveRenderersFactory(context.applicationContext, stream, info)
        val player = ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(renderersFactory)
            .build()
        player.volume = 0f
        return player
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
        return true
    }

    fun disableExclusiveMode(context: Context) {
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

        context.startForegroundService(Intent(context, MusicNotificationService::class.java))
    }

    fun release(context: Context) {
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        playlist = emptyList()
        usbStream?.drain()
        usbStream?.release()
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
