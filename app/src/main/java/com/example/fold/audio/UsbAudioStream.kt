package com.example.fold.audio

import android.util.Log

class UsbAudioStream private constructor(val nativeHandle: Long) {

    @Volatile private var released = false
    @Volatile private var currentDeviceInfo: UsbAudioDeviceInfo? = null

    /** Total frames written to USB since last start(). Used for position tracking. */
    val framesWritten: Long
        get() = if (nativeHandle != 0L) nativeGetFramesWritten(nativeHandle) else 0L

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
        private external fun nativeReadClockValid(handle: Long, csId: Int): Boolean

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

        @JvmStatic
        private external fun nativeGetStats(handle: Long): LongArray?

        @JvmStatic
        private external fun nativeGetStateDump(handle: Long): String?

        @JvmStatic
        private external fun nativeNeedsRecreate(handle: Long): Boolean

        fun create(info: UsbAudioDeviceInfo, rate: Int, ch: Int, bits: Int): UsbAudioStream? {
            Log.i(TAG, "create: fd=${info.fd}, ifId=${info.interfaceId}, epOut=0x${info.endpointOut.toString(16)}, " +
                "epFb=0x${info.endpointFeedback.toString(16)}, rate=$rate, ch=$ch, bits=$bits, maxPkt=${info.maxPacketSize}")
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
            if (h == 0L) {
                Log.e(TAG, "create: nativeCreate returned 0 - check native logs for details")
                return null
            }
            Log.i(TAG, "create: success, handle=$h")
            return UsbAudioStream(h).also { it.currentDeviceInfo = info }
        }
    }

    fun setAltSetting(alt: Int): Boolean {
        // CRITICAL: For alt=N (activation), must use Java UsbDeviceConnection.setInterface()
        // because native USBDEVFS_SETINTERFACE does NOT trigger xHCI bandwidth allocation.
        // For alt=0 (deactivation), native ioctl is fine (no bandwidth to free).
        if (alt != 0) {
            val info = currentDeviceInfo
            if (info != null) {
                try {
                    val device = info.device
                    for (i in 0 until device.interfaceCount) {
                        val iface = device.getInterface(i)
                        if (iface.id == info.interfaceId && iface.alternateSetting == alt) {
                            val result = info.connection.setInterface(iface)
                            Log.i(TAG, "setAltSetting($alt) via Java setInterface: result=$result")
                            return result
                        }
                    }
                    Log.w(TAG, "setAltSetting($alt): no matching interface found")
                } catch (e: Exception) {
                    Log.w(TAG, "setAltSetting($alt) Java failed: ${e.message}")
                }
            }
        }
        // Fallback: native ioctl (works for alt=0, may not allocate bandwidth for alt=N)
        return nativeSetAltSetting(nativeHandle, alt)
    }

    fun setSampleRate(rate: Int, csId: Int): Boolean = nativeSetSampleRate(nativeHandle, rate, csId)

    fun readClockValid(csId: Int): Boolean = nativeReadClockValid(nativeHandle, csId)

    fun start(): Boolean = nativeStart(nativeHandle)

    fun write(pcm: FloatArray) = nativeWrite(nativeHandle, pcm)

    /**
     * Write raw integer PCM bytes, accepting Media3 encoding constant.
     * Converts encoding to bit depth for the native layer.
     */
    fun writeRaw(pcm: ByteArray, encoding: Int) {
        val inputBitDepth = when (encoding) {
            2 -> 16   // C.ENCODING_PCM_16BIT
            0x15 -> 24 // C.ENCODING_PCM_24BIT
            0x16 -> 32 // C.ENCODING_PCM_32BIT
            else -> return
        }
        nativeWriteRaw(nativeHandle, pcm, inputBitDepth)
    }

    fun stop() = nativeStop(nativeHandle)

    fun flush() = nativeFlush(nativeHandle)

    fun drain(): Int = nativeDrainUrbs(nativeHandle)

    fun release() {
        if (released) return
        released = true
        nativeDestroy(nativeHandle)
    }

    fun isRunning(): Boolean = nativeIsRunning(nativeHandle)

    /** Returns true if ENOENT was detected and the stream needs to be destroyed and recreated. */
    fun needsRecreate(): Boolean = nativeNeedsRecreate(nativeHandle)

    data class TransferStats(
        val urbCompleteCount: Long,
        val isoPacketTotal: Long,
        val isoPacketErrors: Long,
        val audioIsoPacketTotal: Long,
        val audioIsoPacketErrors: Long,
        val feedbackPacketCount: Long,
        val urbSubmitFailures: Long,
        val urbReapFailures: Long,
        val pcmUnderruns: Long,
        val pcmUnderrunBytes: Long,
        val lastTransferErrorCode: Int,
        val lastTransferErrorSource: Int,
        val recoveryCount: Int,
    )

    fun getStats(): TransferStats? {
        if (nativeHandle == 0L) return null
        val arr = nativeGetStats(nativeHandle) ?: return null
        if (arr.size < 13) return null
        return TransferStats(
            urbCompleteCount = arr[0],
            isoPacketTotal = arr[1],
            isoPacketErrors = arr[2],
            audioIsoPacketTotal = arr[3],
            audioIsoPacketErrors = arr[4],
            feedbackPacketCount = arr[5],
            urbSubmitFailures = arr[6],
            urbReapFailures = arr[7],
            pcmUnderruns = arr[8],
            pcmUnderrunBytes = arr[9],
            lastTransferErrorCode = arr[10].toInt(),
            lastTransferErrorSource = arr[11].toInt(),
            recoveryCount = arr[12].toInt(),
        )
    }

    fun getStateDump(): String? {
        if (nativeHandle == 0L) return null
        return nativeGetStateDump(nativeHandle)
    }
}
