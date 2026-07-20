package com.example.fold.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * USB exclusive AudioSink — wraps DefaultAudioSink (muted) for ExoPlayer
 * clock/position tracking, routes audio to USB DAC via isochronous transfers.
 *
 * Follows decent-player's ForwardingAudioSink architecture exactly.
 */
@UnstableApi
class UsbExclusiveAudioSink(
    private val delegate: DefaultAudioSink,
    private val usbStream: UsbAudioStream,
    private val deviceInfo: UsbAudioDeviceInfo? = null,
    private val onStreamStopped: (() -> Unit)? = null,
) : ForwardingAudioSink(delegate) {

    companion object {
        private const val TAG = "UsbSink"
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 100L
        private const val QUEUE_BACKPRESSURE_THRESHOLD = 16
    }

    // ── Streaming thread (separate class pattern) ──────────────────

    private sealed class AudioBuffer {
        class FloatBuffer(val data: FloatArray) : AudioBuffer()
        class RawBuffer(val data: ByteArray, val encoding: Int) : AudioBuffer()
    }

    private val audioQueue = ArrayBlockingQueue<AudioBuffer>(QUEUE_CAPACITY)
    @Volatile private var streamingRunning = false
    @Volatile private var streamingPaused = false
    private var streamingThread: Thread? = null
    private var dropCount = 0

    private fun startStreamingThread() {
        if (streamingRunning) return
        streamingRunning = true
        streamingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "USB streaming thread started")
            while (streamingRunning) {
                if (streamingPaused) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }
                // Check if native stream stopped (e.g. ENOENT)
                if (!usbStream.isRunning()) {
                    Log.w(TAG, "USB stream stopped natively, signaling recreate")
                    streamingRunning = false
                    onStreamStopped?.invoke()
                    break
                }
                val buf = try {
                    audioQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                try {
                    when (buf) {
                        is AudioBuffer.FloatBuffer -> usbStream.write(buf.data)
                        is AudioBuffer.RawBuffer -> usbStream.writeRaw(buf.data, buf.encoding)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "USB write failed: ${e.message}")
                }
            }
            Log.i(TAG, "USB streaming thread exited")
        }, "UsbStreamingThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopStreamingThread() {
        streamingRunning = false
        audioQueue.clear()
        streamingThread?.join(2000)
        streamingThread = null
    }

    private fun enqueueFloat(floatBuf: FloatArray) {
        val buf = AudioBuffer.FloatBuffer(floatBuf)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped float buffer #$dropCount")
            }
        }
    }

    private fun enqueueRaw(rawBytes: ByteArray, encoding: Int) {
        val buf = AudioBuffer.RawBuffer(rawBytes, encoding)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped raw buffer #$dropCount")
            }
        }
    }

    // ── State ──────────────────────────────────────────────────────

    private var currentEncoding: Int = C.ENCODING_PCM_16BIT
    private var currentSampleRate: Int = 0
    private var currentChannelCount: Int = 0
    private var delegateMuted = false
    private var handleBufferCallCount = 0L
    private var usbStartMediaTimeUs: Long = 0L
    private var usbStartMediaTimeNeedsInit = true
    private var isPlaying = false
    private var backpressureCount = 0L

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val enc = inputFormat.pcmEncoding
        if (enc != Format.NO_VALUE) currentEncoding = enc
        currentSampleRate = inputFormat.sampleRate
        currentChannelCount = inputFormat.channelCount
        handleBufferCallCount = 0

        Log.i(TAG, "configure: encoding=$enc rate=$currentSampleRate ch=$currentChannelCount")

        // Delegate must be configured first — provides ExoPlayer's clock
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        muteDelegateIfNeeded()

        // Start streaming thread if not already running
        if (!streamingRunning) {
            startStreamingThread()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        // Fallback to delegate if USB stream is not ready
        if (usbStream.nativeHandle == 0L) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        muteDelegateIfNeeded()

        // Backpressure: pace ExoPlayer to USB consumption rate
        if (audioQueue.size >= QUEUE_BACKPRESSURE_THRESHOLD) {
            backpressureCount++
            if (backpressureCount % 50L == 0L) {
                Log.i(TAG, "backpressure: #$backpressureCount, queueSize=${audioQueue.size}, dropCount=$dropCount")
            }
            return false
        }

        // Capture media timeline offset from first buffer
        if (usbStartMediaTimeNeedsInit) {
            usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
            usbStartMediaTimeNeedsInit = false
            Log.i(TAG, "startMediaTimeUs=$usbStartMediaTimeUs")
        }

        handleBufferCallCount++
        val snapshot: ByteBuffer = buffer.slice().order(buffer.order())

        if (currentEncoding == C.ENCODING_PCM_FLOAT) {
            val totalSamples = snapshot.remaining() / 4
            if (totalSamples > 0) {
                val floatBuf = FloatArray(totalSamples)
                snapshot.asFloatBuffer().get(floatBuf)
                enqueueFloat(floatBuf)
            }
        } else {
            val remaining = snapshot.remaining()
            if (remaining > 0) {
                val rawBytes = ByteArray(remaining)
                snapshot.get(rawBytes)
                enqueueRaw(rawBytes, currentEncoding)
            }
        }

        // 每 200 次记录一次队列状态
        if (handleBufferCallCount % 200L == 0L) {
            Log.i(TAG, "handleBuffer: #$handleBufferCallCount, queue=${audioQueue.size}, drops=$dropCount, backpressure=$backpressureCount")
        }

        // Consume buffer — delegate is muted so we skip its handleBuffer
        buffer.position(buffer.limit())
        return true
    }

    // ── Position tracking ──────────────────────────────────────────

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (usbStream.nativeHandle != 0L) {
            if (usbStartMediaTimeNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
            val frames = usbStream.framesWritten
            return if (currentSampleRate > 0) {
                usbStartMediaTimeUs + frames * C.MICROS_PER_SECOND / currentSampleRate
            } else AudioSink.CURRENT_POSITION_NOT_SET
        }
        return super.getCurrentPositionUs(sourceEnded)
    }

    // ── Playback control ───────────────────────────────────────────

    override fun play() {
        super.play()
        isPlaying = true
        streamingPaused = false
        Log.i(TAG, "play()")
    }

    override fun pause() {
        isPlaying = false
        streamingPaused = true
        super.pause()
        Log.i(TAG, "pause()")
    }

    override fun flush() {
        super.flush()
        audioQueue.clear()
        usbStream.flush()
        usbStartMediaTimeNeedsInit = true
        Log.i(TAG, "flush()")
    }

    override fun reset() {
        super.reset()
        // USB stream survives reset — configure() manages its lifecycle
    }

    override fun release() {
        stopStreamingThread()
        // Drain ALL in-flight URBs — MUST complete before any USB reset
        val drained = usbStream.drain()
        Log.i(TAG, "release: drained $drained URBs")
        // Release native context
        usbStream.release()
        unmuteDelegateIfNeeded()
        super.release()
    }

    override fun setVolume(volume: Float) {
        if (usbStream.nativeHandle != 0L) {
            muteDelegateIfNeeded()
        } else {
            unmuteDelegateIfNeeded()
            super.setVolume(volume)
        }
    }

    override fun isEnded(): Boolean {
        if (usbStream.nativeHandle != 0L) {
            return !isPlaying && handleBufferCallCount > 0
        }
        return super.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (usbStream.nativeHandle != 0L) {
            return isPlaying || audioQueue.isNotEmpty()
        }
        return super.hasPendingData()
    }

    // ── Delegate mute management ───────────────────────────────────

    private fun muteDelegateIfNeeded() {
        if (!delegateMuted) {
            super.setVolume(0f)
            delegateMuted = true
        }
    }

    private fun unmuteDelegateIfNeeded() {
        if (delegateMuted) {
            super.setVolume(1f)
            delegateMuted = false
        }
    }
}
