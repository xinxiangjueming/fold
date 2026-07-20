package com.example.fold.audio

import android.os.Debug
import com.example.fold.ui.player.MiniPlayerState
import com.example.fold.ui.player.MusicPlayerHolder
import com.example.fold.util.FoldLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 播放期间内存监控 — 每 5 秒采样一次，写入 fold.log。
 * 标注各项内存来源，帮助定位内存问题。
 */
object PlaybackMemoryMonitor {

    private const val TAG = "MemMonitor"
    private const val INTERVAL_MS = 5000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null
    private var snapshotCount = 0

    fun start() {
        if (future != null) return
        snapshotCount = 0
        future = scheduler.scheduleAtFixedRate({
            try {
                takeSnapshot()
            } catch (e: Exception) {
                FoldLogger.e(TAG, "snapshot failed: ${e.message}")
            }
        }, 0, INTERVAL_MS, TimeUnit.MILLISECONDS)
        FoldLogger.i(TAG, "Memory monitor started, interval=${INTERVAL_MS}ms")
    }

    fun stop() {
        future?.cancel(false)
        future = null
        FoldLogger.i(TAG, "Memory monitor stopped, total snapshots=$snapshotCount")
    }

    fun snapshot(): String {
        val sb = StringBuilder()
        collectSnapshot(sb)
        return sb.toString()
    }

    private fun takeSnapshot() {
        snapshotCount++
        val sb = StringBuilder()
        sb.appendLine("=== Memory Snapshot #$snapshotCount ===")
        collectSnapshot(sb)
        FoldLogger.i(TAG, sb.toString().trim())
    }

    private fun collectSnapshot(sb: StringBuilder) {
        // --- Java Heap ---
        val rt = Runtime.getRuntime()
        val javaMax = rt.maxMemory()
        val javaTotal = rt.totalMemory()
        val javaFree = rt.freeMemory()
        val javaUsed = javaTotal - javaFree
        sb.appendLine("  Java Heap: ${formatBytes(javaUsed)} / ${formatBytes(javaMax)} (used/max), free=${formatBytes(javaFree)}")

        // --- Native Heap (via Debug) ---
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        sb.appendLine("  Native PSS: ${formatBytes(memInfo.nativePss.toLong() * 1024)}, " +
            "Dalvik PSS: ${formatBytes(memInfo.dalvikPss.toLong() * 1024)}, " +
            "Total PSS: ${formatBytes(memInfo.totalPss.toLong() * 1024)}")
        sb.appendLine("  Native Heap: size=${formatBytes(Debug.getNativeHeapSize())}, " +
            "alloc=${formatBytes(Debug.getNativeHeapAllocatedSize())}")

        // --- Memory Info from ActivityManager (if available) ---
        // Debug.getMemoryInfo already gives us what we need

        // --- Source Annotations ---
        sb.appendLine("  --- Sources ---")

        // ExoPlayer
        val playerActive = MusicPlayerHolder.isActive()
        val exoPlayer = MusicPlayerHolder.exoPlayer
        val sessionId = exoPlayer?.audioSessionId ?: -1
        val isPlaying = exoPlayer?.isPlaying ?: false
        val posMs = exoPlayer?.currentPosition ?: 0L
        val durMs = exoPlayer?.duration ?: 0L
        sb.appendLine("  ExoPlayer: active=$playerActive, playing=$isPlaying, sessionId=$sessionId, " +
            "pos=${posMs}ms, dur=${durMs}ms")

        // USB Exclusive
        val exclusiveEnabled = MusicPlayerHolder.isExclusiveMode.value
        val stream = MusicPlayerHolder.usbStream
        val streamRunning = stream?.isRunning() ?: false
        val streamHandle = stream?.nativeHandle ?: 0L
        sb.appendLine("  USB Exclusive: enabled=$exclusiveEnabled, streamRunning=$streamRunning, handle=$streamHandle")

        // DSP EQ
        val eqEnabled = com.example.fold.audio.EqManager.state.value.isEnabled
        val eqState = com.example.fold.audio.EqManager.state.value
        val hasActiveBands = eqState.bandGains.any { kotlin.math.abs(it) > 0.001f }
        val hasBassTreble = kotlin.math.abs(eqState.bassDb) > 0.001f || kotlin.math.abs(eqState.trebleDb) > 0.001f
        sb.appendLine("  DSP EQ: enabled=$eqEnabled, activeBands=$hasActiveBands, bassTreble=$hasBassTreble")

        // Album Art Bitmap
        val albumArt = MiniPlayerState.state.value.albumArt
        if (albumArt != null) {
            sb.appendLine("  Album Art: bitmap=${formatBytes(albumArt.byteCount.toLong())}, " +
                "size=${albumArt.width}x${albumArt.height}, config=${albumArt.config}")
        } else {
            sb.appendLine("  Album Art: none")
        }

        // Playlist
        val playlistSize = MusicPlayerHolder.playlist.size
        sb.appendLine("  Playlist: $playlistSize items")
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        }
    }
}
