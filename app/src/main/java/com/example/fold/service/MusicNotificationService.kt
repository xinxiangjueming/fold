package com.example.fold.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.fold.MainActivity
import com.example.fold.R
import com.example.fold.util.FoldLogger

private const val TAG = "MusicNotif"
private const val CHANNEL = "music_playback"
const val NOTIF_ID_MUSIC = 2001

/**
 * 音乐前台通知服务
 *
 * MediaSessionCompat + MediaStyle → 小米灵动岛
 * API 36+ ProgressStyle → Live Update
 */
class MusicNotificationService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notifManager: NotificationManager
    private lateinit var receiver: BroadcastReceiver

    companion object {
        private var instance: MusicNotificationService? = null

        fun updatePlayback(
            title: String,
            artist: String,
            albumArt: Bitmap?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long
        ) {
            instance?.showNotification(title, artist, albumArt, isPlaying, positionMs, durationMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FoldLogger.i(TAG, "onCreate")
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        initMediaSession()

        // 占位通知，立即启动前台
        val placeholder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.player_music_play))
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(NOTIF_ID_MUSIC, placeholder)

        // 广播接收器：通知栏按钮
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    "com.example.fold.MUSIC_PREV" -> MusicEventBus.send(MusicEvent.PREV)
                    "com.example.fold.MUSIC_PAUSE" -> MusicEventBus.send(MusicEvent.PAUSE)
                    "com.example.fold.MUSIC_RESUME" -> MusicEventBus.send(MusicEvent.RESUME)
                    "com.example.fold.MUSIC_NEXT" -> MusicEventBus.send(MusicEvent.NEXT)
                    "com.example.fold.MUSIC_STOP" -> MusicEventBus.send(MusicEvent.STOP)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.example.fold.MUSIC_PREV")
            addAction("com.example.fold.MUSIC_PAUSE")
            addAction("com.example.fold.MUSIC_RESUME")
            addAction("com.example.fold.MUSIC_NEXT")
            addAction("com.example.fold.MUSIC_STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        FoldLogger.i(TAG, "onDestroy")
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        mediaSession.isActive = false
        mediaSession.release()
        instance = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifManager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.player_music_play), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.player_music_control)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "FoldMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { MusicEventBus.send(MusicEvent.RESUME) }
                override fun onPause() { MusicEventBus.send(MusicEvent.PAUSE) }
                override fun onSkipToNext() { MusicEventBus.send(MusicEvent.NEXT) }
                override fun onSkipToPrevious() { MusicEventBus.send(MusicEvent.PREV) }
                override fun onStop() { MusicEventBus.send(MusicEvent.STOP) }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun showNotification(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        // 更新 MediaSession 元数据（灵动岛显示）
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .also {
                    if (albumArt != null) {
                        it.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                    }
                }
                .build()
        )

        // 更新播放状态（灵动岛需要 playbackState 不为 null）
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, positionMs, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )

        val contentIntent = PendingIntent.getActivity(
            this, 200,
            Intent(this, MainActivity::class.java).apply {
                putExtra("OPEN_PLAYER", true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setLargeIcon(albumArt)

        // 播放控制按钮
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_previous, getString(R.string.player_prev),
                PendingIntent.getBroadcast(this, 201,
                    Intent("com.example.fold.MUSIC_PREV"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        )

        if (isPlaying) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause, getString(R.string.player_pause),
                    PendingIntent.getBroadcast(this, 202,
                        Intent("com.example.fold.MUSIC_PAUSE"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            )
        } else {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play, getString(R.string.player_play),
                    PendingIntent.getBroadcast(this, 203,
                        Intent("com.example.fold.MUSIC_RESUME"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            )
        }

        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_next, getString(R.string.player_next),
                PendingIntent.getBroadcast(this, 204,
                    Intent("com.example.fold.MUSIC_NEXT"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        )

        // MediaStyle 绑定 MediaSession — 小米灵动岛的关键
        builder.setStyle(
            MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    PendingIntent.getBroadcast(this, 205,
                        Intent("com.example.fold.MUSIC_STOP"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
        )

        builder.priority = NotificationCompat.PRIORITY_LOW

        // API 36+ Live Update
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val platBuilder = Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(artist)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setCategory("live_update")
                    .setLargeIcon(albumArt)

                val clazz = Class.forName("android.app.Notification\$MediaStyle")
                val mediaStyle = clazz.getDeclaredConstructor().newInstance()
                // MediaSessionCompat.Token → 平台 MediaSession.Token
                val platformToken = mediaSession.sessionToken.let { compatToken ->
                    try {
                        compatToken.javaClass.getMethod("getToken").invoke(compatToken)
                            ?: compatToken.javaClass.getMethod("unwrap").invoke(compatToken)
                    } catch (_: Exception) { compatToken }
                }
                clazz.getMethod("setMediaSession", android.media.session.MediaSession.Token::class.java)
                    .invoke(mediaStyle, platformToken)

                val setStyleMethod = platBuilder.javaClass.getMethod("setStyle", Notification.Style::class.java)
                setStyleMethod.invoke(platBuilder, mediaStyle)

                notifManager.notify(NOTIF_ID_MUSIC, platBuilder.build())
                return
            } catch (e: Exception) {
                FoldLogger.w(TAG, "Live Update MediaStyle not available: ${e.message}")
            }
        }

        startForeground(NOTIF_ID_MUSIC, builder.build())
    }
}
