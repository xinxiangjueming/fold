package com.example.fold.audio

/**
 * Native DSP engine for parametric EQ + bass/treble shelves.
 * Processes float PCM in-place, ARM NEON accelerated.
 */
class DspEngine {

    companion object {
        const val EQ_BAND_COUNT = 10

        /** ISO 10-band center frequencies */
        val CENTER_FREQUENCIES = intArrayOf(
            31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
        )

        init {
            System.loadLibrary("dsp_engine")
        }
    }

    private var nativeHandle: Long = 0L
    private var isReleased = false

    val isReady: Boolean get() = nativeHandle != 0L && !isReleased

    fun create(sampleRate: Int, channelCount: Int): Boolean {
        if (nativeHandle != 0L) destroy()
        nativeHandle = nativeCreate(sampleRate, channelCount)
        isReleased = false
        return nativeHandle != 0L
    }

    fun setEqBands(bandGains: FloatArray, bassDb: Float, trebleDb: Float) {
        if (nativeHandle == 0L) return
        require(bandGains.size == EQ_BAND_COUNT) { "bandGains must have $EQ_BAND_COUNT elements" }
        nativeSetEqBands(nativeHandle, bandGains, bassDb, trebleDb)
    }

    fun setEqEnabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetEqEnabled(nativeHandle, enabled)
    }

    fun processAudio(pcmBuffer: FloatArray) {
        if (nativeHandle == 0L) return
        nativeProcessAudio(nativeHandle, pcmBuffer)
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            isReleased = true
        }
    }

    // JNI
    private external fun nativeCreate(sampleRate: Int, channelCount: Int): Long
    private external fun nativeSetEqBands(handle: Long, bandGains: FloatArray, bassDb: Float, trebleDb: Float)
    private external fun nativeSetEqEnabled(handle: Long, enabled: Boolean)
    private external fun nativeProcessAudio(handle: Long, pcmBuffer: FloatArray)
    private external fun nativeDestroy(handle: Long)
}
