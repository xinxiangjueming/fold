package com.example.fold.audio

import android.os.Process
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class UsbExclusiveAudioSink(
    private val stream: UsbAudioStream,
) : AudioSink {

    companion object {
        private const val TAG = "UsbSink"
        private const val QUEUE_CAPACITY = 128
    }

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
    private var usbClockSourceId = 0

    private var needsConversion = false
    private var srcBytesPerSample = 2

    private var bufferCount = 0L
    private var lastBufferLogMs = 0L
    private var usbStarted = false

    private val pcmQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var streamingThread: Thread? = null

    private var deviceInfo: UsbAudioDeviceInfo? = null

    // DSP engine for EQ processing
    private var dspEngine: DspEngine? = null

    fun setDeviceInfo(info: UsbAudioDeviceInfo) {
        deviceInfo = info
        usbClockSourceId = info.clockSourceId
    }

    override fun setListener(listener: AudioSink.Listener) { this.listener = listener }
    override fun supportsFormat(format: Format): Boolean = MimeTypes.isAudio(format.sampleMimeType ?: "")
    override fun getFormatSupport(format: Format): Int =
        if (supportsFormat(format)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        submittedFrames = 0L
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount

        Log.i(TAG, "configure: mime=${inputFormat.sampleMimeType} " +
            "sampleRate=$sampleRate channelCount=$channelCount " +
            "pcmEncoding=${inputFormat.pcmEncoding} " +
            "bitrate=${inputFormat.bitrate} averageBitrate=${inputFormat.averageBitrate}")

        // Initialize DSP engine
        dspEngine?.destroy()
        dspEngine = EqManager.createEngine(sampleRate, channelCount)
        if (dspEngine != null) {
            Log.i(TAG, "DSP engine created: rate=$sampleRate ch=$channelCount")
        }

        encoding = if (inputFormat.pcmEncoding > 0) {
            inputFormat.pcmEncoding
        } else {
            Log.w(TAG, "pcmEncoding=0 (invalid), fallback to ENCODING_PCM_16BIT")
            C.ENCODING_PCM_16BIT
        }

        bytesPerFrame = try {
            Util.getPcmFrameSize(encoding, channelCount)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "getPcmFrameSize failed for encoding=$encoding, fallback to 16bit", e)
            encoding = C.ENCODING_PCM_16BIT
            channelCount * 2
        }

        srcBytesPerSample = when (encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_16BIT -> 2
            else -> 2
        }

        Log.i(TAG, "ExoPlayer output: encoding=$encoding bytesPerFrame=$bytesPerFrame srcBps=$srcBytesPerSample")

        val info = deviceInfo
        if (info != null) {
            usbBitDepth = info.bestBitDepth
            usbBytesPerSample = usbBitDepth / 8
            usbChannels = 2
            usbClockSourceId = info.clockSourceId
            Log.i(TAG, "Using device info: ${usbBitDepth}bit, iface=${info.interfaceId}, " +
                "alt=${info.bestAltSetting}, epOut=0x${info.endpointOut.toString(16)}, " +
                "csId=$usbClockSourceId, maxPkt=${info.maxPacketSize}")
        } else {
            // Fallback: no device info, use source format
            usbChannels = 2
            usbBytesPerSample = srcBytesPerSample
            usbBitDepth = srcBytesPerSample * 8
            Log.w(TAG, "No device info, using source format: ${usbBitDepth}bit")
        }

        // Float PCM is always converted to 16-bit in handleBuffer, so effective srcBps is 2
        val effectiveSrcBps = if (encoding == C.ENCODING_PCM_FLOAT) 2 else srcBytesPerSample
        needsConversion = (effectiveSrcBps != usbBytesPerSample) || (channelCount != usbChannels)
        Log.i(TAG, "USB output: ${sampleRate}Hz ${usbBitDepth}bit ${usbChannels}ch " +
            "usbBps=$usbBytesPerSample, needsConversion=$needsConversion " +
            "(srcBps=$srcBytesPerSample, srcCh=$channelCount)")
    }

    override fun play() {
        Log.i(TAG, "play() called, encoding=$encoding usbBitDepth=$usbBitDepth, usbStarted=$usbStarted")
        if (usbStarted) {
            isPlaying = true
        } else {
            // Proper startup sequence matching decent-player / UAPP:
            // 1. Drain any stale URBs from previous session
            // 2. Set alt=0 (deactivate endpoint)
            // 3. Set sample rate via clock control
            // 4. Set alt=N (activate endpoint)
            // 5. Start stream
            stream.drain()
            stream.stop()
            val info = deviceInfo
            if (info != null) {
                stream.setAltSetting(0)
                Log.i(TAG, "setAltSetting(0): deactivated endpoint")
                Thread.sleep(50) // DAC needs time to settle after alt change

                val rateOk = stream.setSampleRate(sampleRate, usbClockSourceId)
                Log.i(TAG, "setSampleRate($sampleRate, csId=$usbClockSourceId): $rateOk")
                Thread.sleep(10) // Let clock lock before activating endpoint

                val altOk = stream.setAltSetting(info.bestAltSetting)
                Log.i(TAG, "setAltSetting(${info.bestAltSetting}): $altOk")
                if (!altOk) {
                    Log.e(TAG, "setAltSetting failed, cannot start playback")
                    return
                }
                Thread.sleep(20) // DAC needs time after alt activation before URBs
            }
            isPlaying = true
            val started = stream.start()
            if (!started) {
                Log.e(TAG, "stream.start() failed, cleaning up")
                isPlaying = false
                usbStarted = false
                stream.stop()
            } else {
                usbStarted = true
                startStreamingThread()
                Log.i(TAG, "USB stream started successfully")
            }
        }
    }

    private fun startStreamingThread() {
        streamingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "Streaming thread started")
            // When encoding is float, data is already converted to 16-bit in handleBuffer
            val writeBitDepth = if (encoding == C.ENCODING_PCM_FLOAT) 16 else srcBytesPerSample * 8
            while (isPlaying || pcmQueue.isNotEmpty()) {
                val chunk = try {
                    pcmQueue.poll(10, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                try {
                    stream.writeRaw(chunk, writeBitDepth)
                } catch (e: Exception) {
                    Log.e(TAG, "writeRaw failed: ${e.message}")
                    // Brief pause before retry to avoid tight error loop
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                }
            }
            // Drain remaining queue before exiting
            while (pcmQueue.isNotEmpty()) {
                val chunk = pcmQueue.poll() ?: break
                try {
                    stream.writeRaw(chunk, writeBitDepth)
                } catch (_: Exception) {
                    break
                }
            }
            Log.i(TAG, "Streaming thread exited")
        }, "UsbStreamingThread").apply { start() }
    }

    @Throws(AudioSink.InitializationException::class, AudioSink.WriteException::class)
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val remaining = buffer.remaining()
        if (remaining <= 0) {
            Log.w(TAG, "handleBuffer: empty buffer (remaining=0)")
            return false
        }

        if (!usbStarted) {
            Log.i(TAG, "Auto-starting USB on first handleBuffer")
            stream.drain()
            stream.stop()
            val info = deviceInfo
            if (info != null) {
                stream.setAltSetting(0)
                Log.i(TAG, "setAltSetting(0): deactivated endpoint (auto-start)")
                Thread.sleep(50)
                stream.setSampleRate(sampleRate, usbClockSourceId)
                Thread.sleep(10)
                val altOk = stream.setAltSetting(info.bestAltSetting)
                Log.i(TAG, "setAltSetting(${info.bestAltSetting}): $altOk (auto-start)")
                if (!altOk) {
                    Log.e(TAG, "setAltSetting failed in auto-start")
                    return false
                }
                Thread.sleep(20)
            }
            val started = stream.start()
            if (!started) {
                Log.e(TAG, "Auto-start failed")
                return false
            }
            usbStarted = true
            isPlaying = true
            startStreamingThread()
        }

        bufferCount++
        val now = android.os.SystemClock.uptimeMillis()
        if (bufferCount <= 5 || now - lastBufferLogMs > 2000) {
            Log.i(TAG, "handleBuffer #$bufferCount: ${remaining}bytes, " +
                "pts=${presentationTimeUs}us, frames=$submittedFrames, " +
                "pos=${submittedFrames * 1000 / sampleRate}ms")
            lastBufferLogMs = now
        }

        val pcmData = ByteArray(remaining)
        buffer.get(pcmData)

        // Apply DSP (EQ) processing if available
        val processedData = if (encoding == C.ENCODING_PCM_FLOAT && dspEngine?.isReady == true) {
            // Convert byte array to float array for DSP processing
            val floatBuf = java.nio.ByteBuffer.wrap(pcmData).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val floatArray = FloatArray(floatBuf.remaining())
            floatBuf.get(floatArray)
            
            // Apply EQ processing
            dspEngine!!.processAudio(floatArray)
            
            // Convert back to byte array
            val outBuf = java.nio.ByteBuffer.allocate(floatArray.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            outBuf.asFloatBuffer().put(floatArray)
            outBuf.array()
        } else {
            pcmData
        }

        // Always convert float PCM to 16-bit integer before sending to USB device
        val outputData = if (encoding == C.ENCODING_PCM_FLOAT) {
            convertFloatTo16bit(processedData, channelCount)
        } else if (needsConversion) {
            convertPcm(processedData, srcBytesPerSample, usbBytesPerSample, channelCount, usbChannels)
        } else {
            processedData
        }

        // Try to enqueue with backpressure handling
        if (!pcmQueue.offer(outputData)) {
            // Queue full - wait briefly for streaming thread to consume
            val waited = pcmQueue.offer(outputData, 5, TimeUnit.MILLISECONDS)
            if (!waited) {
                // Still full after wait - drop oldest to avoid blocking ExoPlayer
                pcmQueue.poll()
                pcmQueue.offer(outputData)
                Log.w(TAG, "PCM queue full, dropped oldest chunk")
            }
        }

        submittedFrames += remaining / bytesPerFrame
        return true
    }

    @Throws(AudioSink.WriteException::class)
    override fun playToEndOfStream() {}

    override fun handleDiscontinuity() {}

    @Volatile private var released = false

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (released) return C.TIME_UNSET
        val sr = sampleRate
        if (sr <= 0) return C.TIME_UNSET
        return try {
            val written = stream.getFramesWritten()
            if (written <= 0L) C.TIME_UNSET
            else (written * 1_000_000L / sr).coerceAtLeast(0L)
        } catch (_: Exception) {
            C.TIME_UNSET
        }
    }

    override fun isEnded(): Boolean = !isPlaying && submittedFrames > 0
    override fun hasPendingData(): Boolean = isPlaying || pcmQueue.isNotEmpty()
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

    override fun pause() {
        Log.i(TAG, "pause() called, submittedFrames=$submittedFrames")
        isPlaying = false
        drainAndStop()
    }

    override fun flush() {
        Log.i(TAG, "flush() called, submittedFrames=$submittedFrames")
        isPlaying = false
        pcmQueue.clear()
        drainAndStop()
        submittedFrames = 0
        bufferCount = 0
    }

    override fun reset() {
        Log.i(TAG, "reset() called, submittedFrames=$submittedFrames")
        isPlaying = false
        pcmQueue.clear()
        drainAndStop()
        submittedFrames = 0
        bufferCount = 0
    }

    override fun release() {
        Log.i(TAG, "release() called")
        released = true
        isPlaying = false
        pcmQueue.clear()
        joinStreamingThread()  // Ensure thread exits before releasing native resources
        stream.drain()
        stream.stop()
        usbStarted = false
        stream.release()
        dspEngine?.destroy()
        dspEngine = null
        EqManager.destroyEngine()
    }

    private fun drainAndStop() {
        // 1. Signal thread to exit
        isPlaying = false
        // 2. Wait for streaming thread to finish (it must not call writeRaw after this)
        joinStreamingThread()
        // 3. Now safe to drain and stop the native stream
        stream.drain()
        stream.stop()
        usbStarted = false
    }

    private fun joinStreamingThread() {
        val t = streamingThread ?: return
        streamingThread = null
        try {
            t.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining streaming thread")
        }
    }

    /**
     * Convert float PCM (4 bytes/sample) to 16-bit integer PCM (2 bytes/sample).
     * Float range [-1.0, 1.0] maps to [-32768, 32767].
     * This is mathematically lossless for 16-bit source (float32 has 24-bit mantissa).
     */
    private fun convertFloatTo16bit(data: ByteArray, channels: Int): ByteArray {
        val sampleCount = data.size / 4
        val out = ByteArray(sampleCount * 2)
        var si = 0; var di = 0
        for (i in 0 until sampleCount) {
            val bits = (data[si].toInt() and 0xFF) or
                ((data[si+1].toInt() and 0xFF) shl 8) or
                ((data[si+2].toInt() and 0xFF) shl 16) or
                (data[si+3].toInt() shl 24)
            val f = Float.fromBits(bits)
            val s = (f.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)
            out[di++] = (s and 0xFF).toByte()
            out[di++] = ((s shr 8) and 0xFF).toByte()
            si += 4
        }
        return out
    }

    private fun convertPcm(
        data: ByteArray,
        srcBps: Int,
        dstBps: Int,
        srcCh: Int,
        dstCh: Int,
    ): ByteArray {
        val srcFrameSize = srcCh * srcBps
        val dstFrameSize = dstCh * dstBps
        val frames = data.size / srcFrameSize
        val result = ByteArray(frames * dstFrameSize)
        var s = 0; var d = 0

        for (f in 0 until frames) {
            for (ch in 0 until srcCh) {
                val sample32 = when (srcBps) {
                    2 -> {
                        // 16-bit signed → 32-bit, sign-extend
                        val lo = data[s].toInt() and 0xFF
                        val hi = data[s + 1].toInt() and 0xFF
                        val raw16 = (hi shl 8) or lo
                        // Sign-extend from 16 to 32 bits
                        val signed16 = if (raw16 >= 0x8000) raw16 - 0x10000 else raw16
                        signed16 shl 16
                    }
                    3 -> {
                        // 24-bit signed → 32-bit, sign-extend
                        val b0 = data[s].toInt() and 0xFF
                        val b1 = data[s + 1].toInt() and 0xFF
                        val b2 = data[s + 2].toInt() and 0xFF
                        val raw24 = (b2 shl 16) or (b1 shl 8) or b0
                        // Sign-extend from 24 to 32 bits
                        val signed24 = if (raw24 >= 0x800000) raw24 - 0x1000000 else raw24
                        signed24 shl 8
                    }
                    4 -> {
                        if (encoding == C.ENCODING_PCM_FLOAT) {
                            val v = ByteBuffer.wrap(data, s, 4).order(ByteOrder.LITTLE_ENDIAN).float
                            (v.toDouble() * 2147483648.0).toInt().coerceIn(Int.MIN_VALUE, Int.MAX_VALUE)
                        } else {
                            // 32-bit signed, already in correct range
                            (data[s].toInt() and 0xFF) or
                                ((data[s + 1].toInt() and 0xFF) shl 8) or
                                ((data[s + 2].toInt() and 0xFF) shl 16) or
                                ((data[s + 3].toInt() and 0xFF) shl 24)
                        }
                    }
                    else -> 0
                }

                when (dstBps) {
                    2 -> {
                        val out = (sample32 shr 16).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        result[d++] = (out and 0xFF).toByte()
                        result[d++] = ((out shr 8) and 0xFF).toByte()
                    }
                    3 -> {
                        val out = sample32 shr 8
                        result[d++] = (out and 0xFF).toByte()
                        result[d++] = ((out shr 8) and 0xFF).toByte()
                        result[d++] = ((out shr 16) and 0xFF).toByte()
                    }
                    4 -> {
                        result[d++] = (sample32 and 0xFF).toByte()
                        result[d++] = ((sample32 shr 8) and 0xFF).toByte()
                        result[d++] = ((sample32 shr 16) and 0xFF).toByte()
                        result[d++] = ((sample32 shr 24) and 0xFF).toByte()
                    }
                }
                s += srcBps
            }
            if (srcCh == 1 && dstCh == 2) {
                System.arraycopy(result, d - dstBps, result, d, dstBps)
                d += dstBps
            }
        }
        return result
    }
}
