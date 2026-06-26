package com.example.fold.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * ExoPlayer AudioSink：拦截 PCM，通过 libusb 等时传输发送到 USB DAC
 */
class UsbExclusiveAudioSink(
    private val usbAudio: UsbAudioNative,
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var isPlaying = false
    @Volatile
    private var submittedFrames = 0L
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_PCM_16BIT
    private var bytesPerFrame = 0
    private var usbBitDepth = 16
    private var usbChannels = 2
    private var usbBytesPerSample = 2

    override fun setListener(listener: AudioSink.Listener) { this.listener = listener }
    override fun supportsFormat(format: Format): Boolean = MimeTypes.isAudio(format.sampleMimeType ?: "")
    override fun getFormatSupport(format: Format): Int =
        if (supportsFormat(format)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        submittedFrames = 0L  // 新曲目重置位置
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount

        /* 详细日志：ExoPlayer 给了什么格式 */
        android.util.Log.i("UsbSink", "configure: mime=${inputFormat.sampleMimeType} " +
            "sampleRate=$sampleRate channelCount=$channelCount " +
            "pcmEncoding=${inputFormat.pcmEncoding} " +
            "bitrate=${inputFormat.bitrate} averageBitrate=${inputFormat.averageBitrate}")

        /*
         * Format.pcmEncoding 可能为 0 或 -1（无效），常见于：
         * - ExoPlayer 解码器输出 Format 没携带 pcmEncoding 键
         * - MP3/AAC 解码后 MediaFormat 的 pcm-encoding 未传递到 Format
         * fallback 到 16bit PCM
         */
        encoding = if (inputFormat.pcmEncoding > 0) {
            inputFormat.pcmEncoding
        } else {
            android.util.Log.w("UsbSink", "pcmEncoding=0 (invalid), fallback to ENCODING_PCM_16BIT")
            C.ENCODING_PCM_16BIT
        }

        /* 安全计算 frameSize */
        bytesPerFrame = try {
            Util.getPcmFrameSize(encoding, channelCount)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("UsbSink", "getPcmFrameSize failed for encoding=$encoding, fallback to 16bit", e)
            encoding = C.ENCODING_PCM_16BIT
            channelCount * 2
        }

        android.util.Log.i("UsbSink", "Resolved: encoding=$encoding bytesPerFrame=$bytesPerFrame")

        usbChannels = 2
        usbBytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        usbBitDepth = usbBytesPerSample * 8

        android.util.Log.i("UsbSink", "USB output: ${sampleRate}Hz ${usbBitDepth}bit ${usbChannels}ch")

        /* 仅配置格式，不启动 ISO 传输（延迟到 play()） */
        val result = usbAudio.setFormat(sampleRate, usbBitDepth, usbChannels)
        if (result == -2) {
            android.util.Log.w("UsbSink", "Format not supported by USB device, fallback to 16bit")
            usbBitDepth = 16; usbBytesPerSample = 2
            encoding = C.ENCODING_PCM_16BIT  // 同步 encoding，避免 handleBuffer 转换逻辑错乱
            usbAudio.setFormat(sampleRate, 16, 2)
        }
    }

    override fun play() {
        android.util.Log.i("UsbSink", "play() called, encoding=$encoding usbBitDepth=$usbBitDepth")
        /* 先安全停止旧的 ISO 传输（防止连续 play() 调用） */
        usbAudio.stopPlayback()
        isPlaying = true
        /* 启动 ISO 传输——此时 handleBuffer 可以接收数据了 */
        val result = usbAudio.startPlayback(sampleRate, usbBitDepth, usbChannels)
        if (result < 0) {
            android.util.Log.e("UsbSink", "startPlayback failed: $result, cleaning up")
            isPlaying = false
            usbAudio.stopPlayback()
        }
    }

    @Throws(AudioSink.InitializationException::class, AudioSink.WriteException::class)
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val remaining = buffer.remaining()
        if (remaining <= 0) return false

        if (submittedFrames == 0L) {
            android.util.Log.i("UsbSink", "First PCM buffer: $remaining bytes, encoding=$encoding usbBitDepth=$usbBitDepth")
        }

        val pcmData = ByteArray(remaining)
        buffer.get(pcmData)
        if (encoding != C.ENCODING_PCM_16BIT && usbBitDepth == 16) {
            usbAudio.writePcm(convertTo16bit(pcmData, encoding, channelCount))
        } else if (channelCount != usbChannels) {
            usbAudio.writePcm(convertChannels(pcmData, channelCount, usbChannels, usbBytesPerSample))
        } else {
            usbAudio.writePcm(pcmData)
        }
        submittedFrames += remaining / bytesPerFrame
        return true
    }

    @Throws(AudioSink.WriteException::class)
    override fun playToEndOfStream() {}

    override fun handleDiscontinuity() {}
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val sr = sampleRate
        val frames = submittedFrames
        if (sr <= 0) return C.TIME_UNSET
        // 当还没有数据流入时，返回 TIME_UNSET 让 ExoPlayer 用解码器的 PTS 作为位置
        // 否则 renderer 会认为 sink 位置=0 并停止发送数据
        if (frames <= 0L) return C.TIME_UNSET
        return try {
            (frames * 1_000_000L / sr).coerceAtLeast(0L)
        } catch (_: Exception) {
            C.TIME_UNSET
        }
    }

    override fun isEnded(): Boolean = !isPlaying && submittedFrames > 0
    override fun hasPendingData(): Boolean = isPlaying
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) {}

    override fun pause() { isPlaying = false; usbAudio.stopPlayback() }
    override fun flush() { isPlaying = false; submittedFrames = 0; usbAudio.stopPlayback() }
    override fun reset() { isPlaying = false; submittedFrames = 0; usbAudio.stopPlayback() }
    override fun release() { reset() }

    /* ── 格式转换 ── */

    private fun convertTo16bit(data: ByteArray, srcEncoding: Int, channels: Int): ByteArray {
        val srcBps = when (srcEncoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val srcFrameSize = channels * srcBps
        val dstFrameSize = channels * 2
        val frames = data.size / srcFrameSize
        val result = ByteArray(frames * dstFrameSize)
        var s = 0; var d = 0
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val sample16 = when (srcEncoding) {
                    C.ENCODING_PCM_FLOAT -> {
                        val v = ByteBuffer.wrap(data, s, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                        (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    }
                    C.ENCODING_PCM_32BIT -> {
                        ((data[s].toInt() and 0xFF) or ((data[s+1].toInt() and 0xFF) shl 8) or
                                ((data[s+2].toInt() and 0xFF) shl 16) or (data[s+3].toInt() shl 24)) shr 16
                    }
                    C.ENCODING_PCM_24BIT -> {
                        ((data[s].toInt() and 0xFF) or ((data[s+1].toInt() and 0xFF) shl 8) or
                                (data[s+2].toInt() shl 16)) shr 8
                    }
                    else -> 0
                }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                result[d++] = (sample16 and 0xFF).toByte()
                result[d++] = ((sample16 shr 8) and 0xFF).toByte()
                s += srcBps
            }
        }
        return result
    }

    private fun convertChannels(data: ByteArray, srcCh: Int, dstCh: Int, bps: Int): ByteArray {
        if (srcCh == dstCh) return data
        val srcFS = srcCh * bps; val dstFS = dstCh * bps
        val frames = data.size / srcFS
        val result = ByteArray(frames * dstFS)
        var s = 0; var d = 0
        for (f in 0 until frames) {
            if (srcCh == 1 && dstCh == 2) {
                System.arraycopy(data, s, result, d, bps); d += bps
                System.arraycopy(data, s, result, d, bps); d += bps
            } else if (srcCh == 2 && dstCh == 1) {
                System.arraycopy(data, s, result, d, bps); d += bps
            }
            s += srcFS
        }
        return result
    }
}
