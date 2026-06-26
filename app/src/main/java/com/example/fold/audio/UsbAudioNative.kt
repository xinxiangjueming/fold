package com.example.fold.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * USB 音频设备管理 + JNI 桥接
 */
class UsbAudioNative(private val context: Context) {

    companion object {
        private const val TAG = "UsbAudioNative"
        private const val ACTION_USB_PERMISSION = "com.example.fold.USB_PERMISSION"

        private const val USB_CLASS_AUDIO = 0x01

        init {
            System.loadLibrary("usb_audio_native")
        }
    }

    /* ── JNI native 方法 ── */
    external fun nativeInit(fd: Int): Int
    external fun nativeParseDescriptors(): String
    external fun nativeSetFormat(sampleRate: Int, bitDepth: Int, channels: Int): Int
    external fun nativeStart(): Int
    external fun nativeWritePcm(data: ByteArray, offset: Int, length: Int): Int
    external fun nativeStop()
    external fun nativeGetUnderrunCount(): Int
    external fun nativeGetSampleRate(): Int
    external fun nativeRelease()

    /* ── 设备发现 ── */

    data class UsbAudioDevice(
        val usbDevice: UsbDevice,
        val productName: String,
    )

    data class AudioFormat(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
    ) {
        val label: String get() = "${sampleRate / 1000}k ${bitDepth}bit"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null

    /**
     * 扫描已连接的 USB Audio 设备
     */
    fun scanDevices(): List<UsbAudioDevice> {
        val result = mutableListOf<UsbAudioDevice>()
        for (device in usbManager.deviceList.values) {
            if (isUsbAudioDevice(device)) {
                result.add(UsbAudioDevice(
                    usbDevice = device,
                    productName = device.productName
                        ?: "USB Audio (VID:${device.vendorId.toString(16)} PID:${device.productId.toString(16)})",
                ))
            }
        }
        return result
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_AUDIO) return true
        }
        return false
    }

    /**
     * 请求 USB 设备权限
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            callback(true)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    callback(granted)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else { 0 }

        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
    }

    /**
     * 打开设备并初始化 native 层
     */
    fun openAndInit(device: UsbAudioDevice): Pair<Boolean, String> {
        if (!usbManager.hasPermission(device.usbDevice)) {
            return Pair(false, "没有 USB 设备权限")
        }

        // 关闭旧连接，避免泄漏
        usbConnection?.close()
        val connection = usbManager.openDevice(device.usbDevice)
            ?: return Pair(false, "无法打开 USB 设备")
        usbConnection = connection

        val fd = connection.fileDescriptor
        val initResult = nativeInit(fd)
        if (initResult < 0) {
            return Pair(false, "nativeInit 失败: $initResult")
        }

        val descriptors = nativeParseDescriptors()
        return Pair(true, descriptors)
    }

    /**
     * 扫描设备支持的格式
     */
    fun scanSupportedFormats(): List<AudioFormat> {
        val supported = mutableListOf<AudioFormat>()
        for (rate in listOf(44100, 48000, 96000, 192000, 384000)) {
            for (depth in listOf(16, 24, 32)) {
                val result = nativeSetFormat(rate, depth, 2)
                if (result >= 0) {
                    supported.add(AudioFormat(rate, depth, 2))
                }
            }
        }
        return supported
    }

    /**
     * 仅设置格式，不启动播放（用于 format scan 和 configure）
     * @return 0=成功, -1=不支持
     */
    fun setFormat(sampleRate: Int, bitDepth: Int, channels: Int): Int {
        return nativeSetFormat(sampleRate, bitDepth, channels)
    }

    /**
     * 设置格式并开始播放
     * @return 0=成功, -1=初始化错误, -2=格式不支持
     */
    fun startPlayback(sampleRate: Int, bitDepth: Int, channels: Int): Int {
        val setResult = nativeSetFormat(sampleRate, bitDepth, channels)
        if (setResult < 0) return setResult

        val startResult = nativeStart()
        if (startResult < 0) return -1

        return 0
    }

    fun writePcm(data: ByteArray): Int = nativeWritePcm(data, 0, data.size)
    fun writePcm(data: ByteArray, offset: Int, length: Int): Int = nativeWritePcm(data, offset, length)
    fun stopPlayback() = nativeStop()
    fun getUnderrunCount(): Int = nativeGetUnderrunCount()
    fun release() {
        nativeRelease()
        usbConnection?.close()
        usbConnection = null
    }
}
