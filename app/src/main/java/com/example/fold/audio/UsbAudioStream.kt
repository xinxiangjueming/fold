package com.example.fold.audio

import android.util.Log

class UsbAudioStream private constructor(val nativeHandle: Long) {

    @Volatile private var released = false

    companion object {
        private const val TAG = "UsbAudioStream"

        init {
            System.loadLibrary("usb_audio_output")
        }

        @JvmStatic
        private external fun nativeCreate(
            fd: Int,
            ifId: Int,
            epOut: Int,
            epFb: Int,
            rate: Int,
            ch: Int,
            bits: Int,
            maxPkt: Int,
        ): Long

        @JvmStatic
        private external fun nativeSetAltSetting(handle: Long, alt: Int): Boolean

        @JvmStatic
        private external fun nativeSetSampleRate(handle: Long, rate: Int, csId: Int): Boolean

        @JvmStatic
        private external fun nativeStart(handle: Long): Boolean

        @JvmStatic
        private external fun nativeWrite(handle: Long, pcm: FloatArray)

        @JvmStatic
        private external fun nativeWriteRaw(handle: Long, pcm: ByteArray, inputBitDepth: Int)

        @JvmStatic
        private external fun nativeStop(handle: Long)

        @JvmStatic
        private external fun nativeFlush(handle: Long)

        @JvmStatic
        private external fun nativeDrainUrbs(handle: Long): Int

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeGetFramesWritten(handle: Long): Long

        @JvmStatic
        private external fun nativeIsRunning(handle: Long): Boolean

        fun create(info: UsbAudioDeviceInfo, rate: Int, ch: Int, bits: Int): UsbAudioStream? {
            val h = nativeCreate(
                info.fd,
                info.interfaceId,
                info.endpointOut,
                info.endpointFeedback,
                rate,
                ch,
                bits,
                info.maxPacketSize,
            )
            return if (h != 0L) UsbAudioStream(h) else null
        }
    }

    fun setAltSetting(alt: Int): Boolean = nativeSetAltSetting(nativeHandle, alt)

    fun setSampleRate(rate: Int, csId: Int): Boolean = nativeSetSampleRate(nativeHandle, rate, csId)

    fun start(): Boolean = nativeStart(nativeHandle)

    fun write(pcm: FloatArray) = nativeWrite(nativeHandle, pcm)

    fun writeRaw(pcm: ByteArray, inputBitDepth: Int) = nativeWriteRaw(nativeHandle, pcm, inputBitDepth)

    fun stop() = nativeStop(nativeHandle)

    fun flush() = nativeFlush(nativeHandle)

    fun drain(): Int = nativeDrainUrbs(nativeHandle)

    fun release() {
        if (released) return
        released = true
        nativeDestroy(nativeHandle)
    }

    fun getFramesWritten(): Long = nativeGetFramesWritten(nativeHandle)

    fun isRunning(): Boolean = nativeIsRunning(nativeHandle)
}
