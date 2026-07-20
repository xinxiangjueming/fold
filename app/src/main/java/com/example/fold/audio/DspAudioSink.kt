package com.example.fold.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioSink that wraps DefaultAudioSink and applies DSP EQ processing.
 * Works with normal playback (speaker, 3.5mm headphone, USB headphone, etc.)
 */
@UnstableApi
class DspAudioSink(
    private val delegate: DefaultAudioSink,
    private val context: Context
) : AudioSink {

    companion object {
        private const val TAG = "DspAudioSink"
    }

    private var dspEngine: DspEngine? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_PCM_16BIT
    private var isActive = false
    private var processCount = 0

    override fun setListener(listener: AudioSink.Listener) {
        delegate.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = delegate.getFormatSupport(format)

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        encoding = inputFormat.pcmEncoding

        dspEngine?.destroy()
        dspEngine = EqManager.createEngine(sampleRate, channelCount)
        isActive = dspEngine?.isReady == true
        if (isActive) {
            Log.i(TAG, "DSP engine created: rate=$sampleRate ch=$channelCount encoding=$encoding")
        }

        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() = delegate.play()

    override fun handleDiscontinuity() = delegate.handleDiscontinuity()

    @Throws(AudioSink.WriteException::class)
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (isActive && encoding == C.ENCODING_PCM_FLOAT) {
            applyDspProcessing(buffer)
        }
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private var totalDspTimeNs = 0L

    private fun applyDspProcessing(buffer: ByteBuffer) {
        val engine = dspEngine ?: return
        if (!engine.isReady) return

        val position = buffer.position()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val floatArray = FloatArray(floatBuffer.remaining())
        floatBuffer.get(floatArray)

        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        engine.processAudio(floatArray)
        val t1 = android.os.SystemClock.elapsedRealtimeNanos()
        totalDspTimeNs += (t1 - t0)

        processCount++
        // 每 1000 次记录一次平均 DSP 处理耗时
        if (processCount % 1000 == 0) {
            val avgUs = totalDspTimeNs / 1000.0 / processCount
            Log.i(TAG, "DSP: #$processCount, samples=${floatArray.size}, avgTime=${String.format("%.1f", avgUs)}us")
        }

        buffer.position(position)
        buffer.asFloatBuffer().put(floatArray)
        buffer.position(position)
    }

    @Throws(AudioSink.WriteException::class)
    override fun playToEndOfStream() = delegate.playToEndOfStream()

    override fun isEnded(): Boolean = delegate.isEnded

    override fun hasPendingData(): Boolean = delegate.hasPendingData()

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = delegate.getCurrentPositionUs(sourceEnded)

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        delegate.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = delegate.playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        delegate.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = delegate.skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        delegate.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes = delegate.audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        delegate.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        delegate.setAuxEffectInfo(auxEffectInfo)
    }

    override fun enableTunnelingV21() = delegate.enableTunnelingV21()

    override fun disableTunneling() = delegate.disableTunneling()

    override fun setVolume(volume: Float) {
        delegate.setVolume(volume)
    }

    override fun pause() = delegate.pause()

    override fun flush() {
        delegate.flush()
    }

    override fun reset() {
        delegate.reset()
        isActive = false
        dspEngine?.destroy()
        dspEngine = null
        EqManager.destroyEngine()
    }

    override fun release() {
        delegate.release()
        isActive = false
        dspEngine?.destroy()
        dspEngine = null
        EqManager.destroyEngine()
    }
}
