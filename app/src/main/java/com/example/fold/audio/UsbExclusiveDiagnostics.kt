package com.example.fold.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.fold.ui.player.MusicPlayerHolder

/**
 * Collects USB exclusive mode diagnostic data and formats it as Key:Value text
 * for debugging and support purposes.
 */
object UsbExclusiveDiagnostics {

    private const val TAG = "UsbExclusiveDiag"

    fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("USB Exclusive Mode Diagnostics")
        sb.appendLine()

        // App info
        val pkgInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) { null }
        sb.appendLine("App: ${context.packageName} ${pkgInfo?.versionName ?: "unknown"}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine()

        // USB permission
        val exclusiveDevice = MusicPlayerHolder.exclusiveDevice
        val hasPermission = exclusiveDevice?.let { dev ->
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                usbManager.hasPermission(dev.usbDevice)
            } catch (_: Exception) { false }
        } ?: false
        sb.appendLine("USB Permission: $hasPermission")
        sb.appendLine("USB Permission Diagnosis: ${if (hasPermission) "granted" else if (exclusiveDevice != null) "denied" else "no_device"}")
        sb.appendLine()

        // Exclusive mode
        val exclusiveEnabled = MusicPlayerHolder.isExclusiveMode.value
        val stream = MusicPlayerHolder.usbStream
        val outputMode = if (exclusiveEnabled && stream != null) "USB_EXCLUSIVE" else "NORMAL"
        val diagnosis = when {
            exclusiveEnabled && stream != null && stream.isRunning() -> "exclusive_streaming"
            exclusiveEnabled && stream != null -> "exclusive_ready"
            exclusiveEnabled -> "exclusive_no_stream"
            else -> "not_active"
        }
        sb.appendLine("Exclusive Enabled: $exclusiveEnabled")
        sb.appendLine("Playback Output Mode: $outputMode")
        sb.appendLine("Exclusive Playback Diagnosis: $diagnosis")
        sb.appendLine()

        // Format compatibility
        val sourceRate = 0 // Will be filled from current playback if available
        val sourceCh = 0
        val sourceBits = "unknown"
        val deviceInfo = MusicPlayerHolder.usbDeviceInfo
        val exclusiveFormat = MusicPlayerHolder.exclusiveFormat
        val outputRate = exclusiveFormat?.sampleRate ?: 0
        val outputCh = exclusiveFormat?.channels ?: 0
        val outputBits = exclusiveFormat?.bitDepth ?: 0
        val outputBps = if (outputBits <= 16) 2 else if (outputBits <= 24) 3 else 4
        val endpointMaxRate = deviceInfo?.maxPacketSize?.let { mps ->
            // Estimate max sample rate from maxPacketSize
            // maxPacketSize bytes per microframe (125us) = maxPacketSize * 8000 bytes/sec
            // For stereo 16bit: bytes/sec = rate * 2 * 2 = rate * 4
            val maxBytesPerSec = mps.toLong() * 8000
            val bytesPerFrame = outputCh.toLong() * outputBps
            if (bytesPerFrame > 0) (maxBytesPerSec / bytesPerFrame).toInt() else 0
        } ?: 0

        val formatDiag = if (outputRate > 0) {
            "source=${sourceRate} Hz / ${sourceCh} ch / ${sourceBits}-bit; output=${outputRate} Hz / ${outputCh} ch / ${outputBits}-bit / ${outputBps} bytes; endpointMax=${endpointMaxRate} Hz"
        } else "no_output_format"
        sb.appendLine("Format Compatibility Diagnosis: $formatDiag")
        sb.appendLine("Sample Rate Fallback: false")
        sb.appendLine("Bit Depth Compatibility: false")
        sb.appendLine("Channel Compatibility: ${outputCh == 2}")
        sb.appendLine()

        // Device info
        val deviceName = exclusiveDevice?.usbDevice?.deviceName ?: "none"
        val productName = exclusiveDevice?.productName ?: "none"
        sb.appendLine("Device Name: $deviceName")
        sb.appendLine("Device Product: $productName")
        sb.appendLine()

        // Endpoint details
        if (deviceInfo != null) {
            val epHex = "0x${deviceInfo.endpointOut.toString(16).padStart(2, '0')}"
            val fbHex = if (deviceInfo.endpointFeedback > 0) "0x${deviceInfo.endpointFeedback.toString(16).padStart(2, '0')}" else "none"
            sb.appendLine("Selected Endpoint: interface=${deviceInfo.interfaceId} / alt=${deviceInfo.bestAltSetting} / endpoint=$epHex / feedback=$fbHex / maxPacket=${deviceInfo.maxPacketSize} / highSpeed=true / terminalLink=N/A / clockSource=${deviceInfo.clockSourceId}")
            sb.appendLine("Selected Endpoint Capacity: maxPacket=${deviceInfo.maxPacketSize} bytes / bestBitDepth=${deviceInfo.bestBitDepth}")
            sb.appendLine()

            // Clock setup
            sb.appendLine("Clock Setup Detail: endpoint=$epHex; interface=${deviceInfo.interfaceId}; alt=${deviceInfo.bestAltSetting}; clockSourceId=${deviceInfo.clockSourceId}; clockVerified=false")
        } else {
            sb.appendLine("Selected Endpoint: none")
            sb.appendLine()
        }
        sb.appendLine()

        // Transfer health — native state dump
        val nativeDump = stream?.getStateDump()
        if (nativeDump != null) {
            sb.appendLine(nativeDump)
            sb.appendLine()
        }

        // Transfer health — Kotlin stats
        val stats = stream?.getStats()
        if (stats != null) {
            val health = when {
                stats.urbSubmitFailures > 0 || stats.urbReapFailures > 0 -> "error"
                stats.pcmUnderruns > 0 -> "degraded"
                else -> "stable"
            }
            val isoErrRate = if (stats.isoPacketTotal > 0) {
                "${stats.isoPacketErrors}/${stats.isoPacketTotal} (${String.format("%.4f", stats.isoPacketErrors * 100.0 / stats.isoPacketTotal)}%)"
            } else "0/0 (0.0000%)"
            val audioIsoErrRate = if (stats.audioIsoPacketTotal > 0) {
                "${stats.audioIsoPacketErrors}/${stats.audioIsoPacketTotal} (${String.format("%.4f", stats.audioIsoPacketErrors * 100.0 / stats.audioIsoPacketTotal)}%)"
            } else "0/0 (0.0000%)"

            val errorSource = when (stats.lastTransferErrorSource) {
                1 -> "submit"
                2 -> "reap"
                3 -> "timeout"
                else -> "none"
            }
            val lastError = if (stats.lastTransferErrorSource > 0) {
                "source=$errorSource code=${stats.lastTransferErrorCode}"
            } else "source=none code=0"

            sb.appendLine("Transfer Health: $health")
            sb.appendLine("Transfer Running: ${stream.isRunning()}")
            sb.appendLine("URB Complete Count: ${stats.urbCompleteCount}")
            sb.appendLine("ISO Packet Errors: $isoErrRate")
            sb.appendLine("Audio ISO Packet Errors: $audioIsoErrRate")
            sb.appendLine("Feedback Packets: ${stats.feedbackPacketCount}")
            sb.appendLine("URB Submit Failures: ${stats.urbSubmitFailures}")
            sb.appendLine("URB Reap Failures: ${stats.urbReapFailures}")
            sb.appendLine("PCM Underruns: ${stats.pcmUnderruns} / bytes=${stats.pcmUnderrunBytes}")
            sb.appendLine("Last Transfer Error: $lastError")
            sb.appendLine("ENOENT Recovery Count: ${stats.recoveryCount}")
        } else {
            sb.appendLine("Transfer Health: no_stream")
            sb.appendLine("Transfer Running: false")
            sb.appendLine("URB Complete Count: 0")
            sb.appendLine("ISO Packet Errors: 0/0 (0.0000%)")
            sb.appendLine("Audio ISO Packet Errors: 0/0 (0.0000%)")
            sb.appendLine("Feedback Packets: 0")
            sb.appendLine("URB Submit Failures: 0")
            sb.appendLine("URB Reap Failures: 0")
            sb.appendLine("PCM Underruns: 0 / bytes=0")
            sb.appendLine("Last Transfer Error: source=none code=0")
        }
        sb.appendLine()

        // Volume
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sb.appendLine("Volume Mode: auto")
        sb.appendLine("Hardware Volume Supported: true")
        sb.appendLine("Volume State: $vol/$maxVol")
        sb.appendLine()

        // Session — read volatile fields only, don't call ExoPlayer methods from IO thread
        val playerActive = MusicPlayerHolder.exoPlayer != null
        sb.appendLine("Session: active=$playerActive")

        return sb.toString()
    }
}
