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

private const val TAG = "FoldNotif"
private const val CHANNEL_TTS = "reader_tts"
private const val CHANNEL_PROGRESS = "reader_progress"
private const val NOTIF_ID_TTS = 1001
private const val NOTIF_ID_PROGRESS = 1002

class ReaderNotificationService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notifManager: NotificationManager
    private lateinit var ttsReceiver: BroadcastReceiver

    companion object {
        private var instance: ReaderNotificationService? = null
        /** 当前朗读的文件路径（供通知点击跳转用） */
        @Volatile
        var currentFilePath: String = ""

        fun start(context: Context) {
            val intent = Intent(context, ReaderNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReaderNotificationService::class.java))
        }

        /** 更新 TTS 通知 */
        fun updateTts(
            title: String,
            paragraph: String,
            current: Int,
            total: Int,
            isPlaying: Boolean
        ) {
            instance?.showTtsNotification(title, paragraph, current, total, isPlaying)
        }

        /** 更新阅读进度通知 */
        fun updateProgress(
            bookName: String,
            chapterName: String,
            currentChapter: Int,
            totalChapters: Int,
            percent: Int
        ) {
            instance?.showProgressNotification(bookName, chapterName, currentChapter, totalChapters, percent)
        }

        fun dismissProgress() {
            instance?.notifManager?.cancel(NOTIF_ID_PROGRESS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FoldLogger.i(TAG, "onCreate")
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()

        // 立即启动前台通知，避免 ForegroundServiceDidNotStartInTimeException
        val placeholder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fold")
            .setContentText("")
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(NOTIF_ID_TTS, placeholder)

        initMediaSession()

        // 广播接收器：通知栏按钮点击 → TtsEventBus
        ttsReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    "com.example.fold.TTS_PREV" -> TtsEventBus.send(TtsEvent.PREV)
                    "com.example.fold.TTS_PAUSE" -> TtsEventBus.send(TtsEvent.PAUSE)
                    "com.example.fold.TTS_RESUME" -> TtsEventBus.send(TtsEvent.RESUME)
                    "com.example.fold.TTS_NEXT" -> TtsEventBus.send(TtsEvent.NEXT)
                    "com.example.fold.TTS_STOP" -> TtsEventBus.send(TtsEvent.STOP)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.example.fold.TTS_PREV")
            addAction("com.example.fold.TTS_PAUSE")
            addAction("com.example.fold.TTS_RESUME")
            addAction("com.example.fold.TTS_NEXT")
            addAction("com.example.fold.TTS_STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ttsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ttsReceiver, filter)
        }

        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        FoldLogger.i(TAG, "onDestroy")
        try { unregisterReceiver(ttsReceiver) } catch (_: Exception) {}
        mediaSession.isActive = false
        mediaSession.release()
        instance = null
        super.onDestroy()
    }

    // ==================== Channel ====================

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifManager.createNotificationChannel(
                NotificationChannel(CHANNEL_TTS, getString(R.string.tts_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.tts_channel_desc)
                    setShowBadge(false)
                }
            )
            notifManager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, getString(R.string.tts_progress_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.tts_progress_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    // ==================== MediaSession (Xiaomi Dynamic Island) ====================

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "FoldReader").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    FoldLogger.d(TAG, "MediaSession onPlay")
                    TtsEventBus.send(TtsEvent.RESUME)
                }
                override fun onPause() {
                    FoldLogger.d(TAG, "MediaSession onPause")
                    TtsEventBus.send(TtsEvent.PAUSE)
                }
                override fun onSkipToNext() {
                    FoldLogger.d(TAG, "MediaSession onSkipToNext")
                    TtsEventBus.send(TtsEvent.NEXT)
                }
                override fun onSkipToPrevious() {
                    FoldLogger.d(TAG, "MediaSession onSkipToPrevious")
                    TtsEventBus.send(TtsEvent.PREV)
                }
                override fun onStop() {
                    FoldLogger.d(TAG, "MediaSession onStop")
                    TtsEventBus.send(TtsEvent.STOP)
                }
            })
            // 设置初始播放状态 — 灵动岛需要 playbackState 不为 null
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

    // ==================== TTS Notification ====================

    private fun showTtsNotification(
        title: String,
        paragraph: String,
        current: Int,
        total: Int,
        isPlaying: Boolean
    ) {
        // 更新 MediaSession 元数据（灵动岛显示用）
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, paragraph.take(60))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, total.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (current + 1).toLong())
                .build()
        )

        // 更新 MediaSession 播放状态
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, current.toLong(), 1f)
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
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("OPEN_READER", true)
                putExtra("READER_PATH", currentFilePath)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_TTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(paragraph.take(100))
            .setSubText("${current + 1}/$total")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setProgress(total, current, false)

        // 播放控制按钮
        if (isPlaying) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous, getString(R.string.tts_prev),
                    PendingIntent.getBroadcast(this, 101,
                        Intent("com.example.fold.TTS_PREV"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            )
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause, getString(R.string.player_pause),
                    PendingIntent.getBroadcast(this, 102,
                        Intent("com.example.fold.TTS_PAUSE"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            )
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next, getString(R.string.tts_next),
                    PendingIntent.getBroadcast(this, 103,
                        Intent("com.example.fold.TTS_NEXT"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            )
        } else {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play, getString(R.string.tts_continue),
                    PendingIntent.getBroadcast(this, 104,
                        Intent("com.example.fold.TTS_RESUME"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            )
        }

        // MediaStyle 绑定 MediaSession — 这是小米灵动岛的关键
        builder.setStyle(
            MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    PendingIntent.getBroadcast(this, 105,
                        Intent("com.example.fold.TTS_STOP"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
        )

        builder.priority = NotificationCompat.PRIORITY_LOW
        startForeground(NOTIF_ID_TTS, builder.build())
    }

    // ==================== Reading Progress Notification ====================

    private fun showProgressNotification(
        bookName: String,
        chapterName: String,
        currentChapter: Int,
        totalChapters: Int,
        percent: Int
    ) {
        val contentIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                putExtra("OPEN_READER", true)
                putExtra("READER_PATH", currentFilePath)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = bookName.ifEmpty { getString(R.string.reader_reading) }
        val text = if (chapterName.isNotEmpty()) "$chapterName · $percent%" else ""

        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent, false)

        // API 36+ Live Update: 使用平台原生 Builder + ProgressStyle
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val platBuilder = Notification.Builder(this, CHANNEL_PROGRESS)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setCategory("live_update")

                val clazz = Class.forName("android.app.Notification\$ProgressStyle")
                val progressStyle = clazz.getDeclaredConstructor().newInstance()
                clazz.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(progressStyle, percent)
                val setStyleMethod = platBuilder.javaClass.getMethod("setStyle", Notification.Style::class.java)
                setStyleMethod.invoke(platBuilder, progressStyle)

                notifManager.notify(NOTIF_ID_PROGRESS, platBuilder.build())
                return
            } catch (e: Exception) {
                FoldLogger.w(TAG, "Live Update ProgressStyle not available: ${e.message}")
            }
        }

        notifManager.notify(NOTIF_ID_PROGRESS, builder.build())
    }
}
