package com.example.fold.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.example.fold.service.MusicNotificationService

/**
 * 音乐播放器全局持有者（单例）
 * ExoPlayer 生命周期独立于 ViewModel，支持后台播放
 */
object MusicPlayerHolder {

    var exoPlayer: ExoPlayer? = null
        private set
    var mediaSession: MediaSession? = null
        private set
    var playlist: List<String> = emptyList()
        private set
    /** 最近一次 init 的 filePath，供通知栏跳转使用 */
    var lastFilePath: String = ""

    fun getOrCreate(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context.applicationContext).build()
            mediaSession = MediaSession.Builder(context.applicationContext, exoPlayer!!).build()
        }
        return exoPlayer!!
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
        context.stopService(Intent(context, MusicNotificationService::class.java))
    }

    fun isActive(): Boolean = exoPlayer != null
}
