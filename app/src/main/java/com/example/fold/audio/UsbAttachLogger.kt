package com.example.fold.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.example.fold.util.FoldLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB DAC 插入自动日志
 *
 * 监听 ACTION_USB_DEVICE_ATTACHED，检测到 USB Audio 设备后：
 * 1. 打开设备读取原始描述符
 * 2. 解析 UAC1/UAC2 接口信息
 * 3. 写入 /storage/emulated/0/fold/usb-HH:mm:ss 文件
 */
object UsbAttachLogger {

    private const val TAG = "UsbAttachLogger"
    private const val USB_CLASS_AUDIO = 0x01
    private const val ACTION_USB_PERMISSION_LOG = "com.example.fold.USB_PERMISSION_LOG"
    private const val LOG_DIR = "/storage/emulated/0/fold"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * 初始化：仅记录日志，USB_DEVICE_ATTACHED 通过 manifest receiver 接收
     */
    fun start(context: Context) {
        Log.i(TAG, "UsbAttachLogger disabled")
    }

    fun onDeviceAttached(context: Context, device: UsbDevice) {
        // 暂时禁用，不处理 USB 设备插入
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_AUDIO) return true
        }
        return false
    }

    private fun handleDevice(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            logDevice(context, usbManager, device)
        } else {
            requestAndLog(context, usbManager, device)
        }
    }

    private fun requestAndLog(context: Context, usbManager: UsbManager, device: UsbDevice) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION_LOG) return
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    logDevice(ctx, usbManager, device)
                } else {
                    // 权限拒绝 → 仅记录基本信息
                    writeLog(buildBasicInfo(device, "权限被拒绝，仅记录基本信息"))
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION_LOG)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {
            writeLog(buildBasicInfo(device, "无法注册权限接收器"))
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION_LOG), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun logDevice(context: Context, usbManager: UsbManager, device: UsbDevice) {
        var connection: UsbDeviceConnection? = null
        try {
            connection = usbManager.openDevice(device)
            if (connection == null) {
                writeLog(buildBasicInfo(device, "openDevice 返回 null"))
                return
            }
            val rawDesc = connection.rawDescriptors
            if (rawDesc == null) {
                writeLog(buildBasicInfo(device, "rawDescriptors 为 null"))
                return
            }
            writeLog(parseDescriptors(device, rawDesc))
        } catch (e: Exception) {
            Log.e(TAG, "logDevice failed", e)
            writeLog(buildBasicInfo(device, "异常: ${e.message}"))
        } finally {
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    // ===================== 描述符解析 =====================

    private fun parseDescriptors(device: UsbDevice, raw: ByteArray): String {
        val sb = StringBuilder()
        sb.appendLine("========== USB Audio 设备日志 ==========")
        sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()

        // 设备信息
        sb.appendLine("── 设备信息 ──")
        sb.appendLine("名称: ${device.deviceName}")
        sb.appendLine("VID:  0x${device.vendorId.toString(16).uppercase().padStart(4, '0')}")
        sb.appendLine("PID:  0x${device.productId.toString(16).uppercase().padStart(4, '0')}")

        if (raw.size >= 18) {
            val iManufacturer = raw[14].toInt() and 0xFF
            val iProduct = raw[15].toInt() and 0xFF
            val iSerial = raw[16].toInt() and 0xFF
            if (iManufacturer != 0) sb.appendLine("厂商: ${getStringDescriptor(raw, iManufacturer)}")
            if (iProduct != 0) sb.appendLine("产品: ${getStringDescriptor(raw, iProduct)}")
            if (iSerial != 0) sb.appendLine("序列号: ${getStringDescriptor(raw, iSerial)}")
        }
        sb.appendLine("USB 版本: ${bcdToString(raw[2], raw[3])}")
        sb.appendLine()

        // UAC 协议检测
        var uacProtocol = 1
        for (off in raw.indices) {
            if (off + 3 < raw.size) {
                val bLength = raw[off].toInt() and 0xFF
                val bDescriptorType = raw[off + 1].toInt() and 0xFF
                val bInterfaceProtocol = raw[off + 3].toInt() and 0xFF
                if (bLength == 9 && bDescriptorType == 4 && bInterfaceProtocol == 0x20) {
                    uacProtocol = 2; break
                }
            }
        }
        sb.appendLine("UAC 协议: UAC${uacProtocol}")
        sb.appendLine()

        // 遍历所有描述符，收集音频接口信息
        var off = 0
        var currentInterface = -1
        var currentAltSetting = -1
        var currentSubClass = 0
        var hasAudioStreaming = false

        while (off + 1 < raw.size) {
            val bLength = raw[off].toInt() and 0xFF
            if (bLength < 2 || off + bLength > raw.size) break
            val bDescriptorType = raw[off + 1].toInt() and 0xFF

            when (bDescriptorType) {
                4 -> { // INTERFACE
                    if (bLength >= 9) {
                        currentInterface = raw[off + 2].toInt() and 0xFF
                        currentAltSetting = raw[off + 3].toInt() and 0xFF
                        val bNumEndpoints = raw[off + 4].toInt() and 0xFF
                        val bInterfaceClass = raw[off + 5].toInt() and 0xFF
                        currentSubClass = raw[off + 6].toInt() and 0xFF

                        if (bInterfaceClass == 0x01 && currentAltSetting == 0) {
                            sb.appendLine("── Audio Interface #$currentInterface ──")
                            val subClassName = when (currentSubClass) {
                                0x01 -> "AudioControl"
                                0x02 -> "AudioStreaming"
                                0x03 -> "MIDIStreaming"
                                else -> "0x${currentSubClass.toString(16)}"
                            }
                            sb.appendLine("  SubClass: $subClassName")
                        }
                        if (bInterfaceClass == 0x01 && currentSubClass == 0x02 && currentAltSetting > 0) {
                            hasAudioStreaming = true
                            sb.appendLine("  ── Alt Setting $currentAltSetting ──")
                            sb.appendLine("  Endpoints: $bNumEndpoints")
                        }
                    }
                }
                0x24 -> { // CS_INTERFACE
                    if (currentSubClass == 0x02 && currentAltSetting > 0) {
                        parseCsInterface(raw, off, bLength, uacProtocol, sb)
                    }
                }
                5 -> { // ENDPOINT
                    if (bLength >= 7 && currentSubClass == 0x02 && currentAltSetting > 0) {
                        val bEndpointAddress = raw[off + 2].toInt() and 0xFF
                        val bEndpointAttributes = raw[off + 3].toInt() and 0xFF
                        val wMaxPacketSize = (raw[off + 4].toInt() and 0xFF) or ((raw[off + 5].toInt() and 0xFF) shl 8)
                        val bInterval = raw[off + 6].toInt() and 0xFF
                        val dir = if ((bEndpointAddress and 0x80) != 0) "IN" else "OUT"
                        val transferType = when (bEndpointAttributes and 0x03) {
                            0x01 -> "Isochronous"
                            0x02 -> "Bulk"
                            0x03 -> "Interrupt"
                            else -> "Control"
                        }
                        sb.appendLine("  Endpoint: 0x${bEndpointAddress.toString(16).padStart(2, '0')} $dir $transferType")
                        sb.appendLine("    MaxPacketSize: $wMaxPacketSize bytes, Interval: $bInterval")
                    }
                }
            }
            off += bLength
        }

        if (!hasAudioStreaming) {
            sb.appendLine("(未发现 AudioStreaming 接口)")
        }
        sb.appendLine()
        sb.appendLine("========== 日志结束 ==========")
        return sb.toString()
    }

    private fun parseCsInterface(raw: ByteArray, off: Int, bLength: Int, uacProtocol: Int, sb: StringBuilder) {
        if (bLength < 3) return
        val bDescriptorSubtype = raw[off + 2].toInt() and 0xFF

        when (uacProtocol) {
            1 -> parseUac1CsInterface(raw, off, bLength, bDescriptorSubtype, sb)
            2 -> parseUac2CsInterface(raw, off, bLength, bDescriptorSubtype, sb)
        }
    }

    private fun parseUac1CsInterface(raw: ByteArray, off: Int, bLength: Int, subtype: Int, sb: StringBuilder) {
        when (subtype) {
            0x01 -> { // AS_GENERAL
                if (bLength >= 7) {
                    val bTerminalLink = raw[off + 3].toInt() and 0xFF
                    val bDelay = raw[off + 4].toInt() and 0xFF
                    val wFormatTag = (raw[off + 5].toInt() and 0xFF) or ((raw[off + 6].toInt() and 0xFF) shl 8)
                    sb.appendLine("  AS_GENERAL: Terminal=$bTerminalLink, Delay=$bDelay, FormatTag=${formatTagToString(wFormatTag)}")
                }
            }
            0x02 -> { // FORMAT_TYPE
                if (bLength >= 8) {
                    val bFormatType = raw[off + 3].toInt() and 0xFF
                    if (bFormatType == 1) { // TYPE_I
                        val bNrChannels = raw[off + 4].toInt() and 0xFF
                        val bSubframeSize = raw[off + 5].toInt() and 0xFF
                        val bBitResolution = raw[off + 6].toInt() and 0xFF
                        val bSamFreqType = raw[off + 7].toInt() and 0xFF
                        sb.appendLine("  FORMAT_TYPE_I: ${bNrChannels}ch, ${bSubframeSize * 8}bit subframe, ${bBitResolution}bit resolution")
                        if (bSamFreqType != 0 && bLength >= 8 + bSamFreqType * 3) {
                            sb.appendLine("    采样率列表 (${bSamFreqType} 个):")
                            for (i in 0 until bSamFreqType) {
                                val base = off + 8 + i * 3
                                if (base + 2 < raw.size) {
                                    val sr = (raw[base].toInt() and 0xFF) or
                                            ((raw[base + 1].toInt() and 0xFF) shl 8) or
                                            ((raw[base + 2].toInt() and 0xFF) shl 16)
                                    sb.appendLine("      ${sr}Hz")
                                }
                            }
                        } else if (bSamFreqType == 0 && bLength >= 14) {
                            val min = (raw[off + 8].toInt() and 0xFF) or
                                    ((raw[off + 9].toInt() and 0xFF) shl 8) or
                                    ((raw[off + 10].toInt() and 0xFF) shl 16)
                            val max = (raw[off + 11].toInt() and 0xFF) or
                                    ((raw[off + 12].toInt() and 0xFF) shl 8) or
                                    ((raw[off + 13].toInt() and 0xFF) shl 16)
                            sb.appendLine("    连续采样率: ${min}Hz - ${max}Hz")
                        }
                    }
                }
            }
        }
    }

    private fun parseUac2CsInterface(raw: ByteArray, off: Int, bLength: Int, subtype: Int, sb: StringBuilder) {
        when (subtype) {
            0x01 -> { // AS_GENERAL
                if (bLength >= 16) {
                    val bTerminalLink = raw[off + 3].toInt() and 0xFF
                    val bmControls = raw[off + 4].toInt() and 0xFF
                    val bFormatType = raw[off + 5].toInt() and 0xFF
                    val bmFormats = (raw[off + 6].toInt() and 0xFF) or
                            ((raw[off + 7].toInt() and 0xFF) shl 8) or
                            ((raw[off + 8].toInt() and 0xFF) shl 16) or
                            ((raw[off + 9].toInt() and 0xFF) shl 24)
                    val bNrChannels = raw[off + 10].toInt() and 0xFF
                    val bmChannelConfig = (raw[off + 11].toInt() and 0xFF) or
                            ((raw[off + 12].toInt() and 0xFF) shl 8) or
                            ((raw[off + 13].toInt() and 0xFF) shl 16) or
                            ((raw[off + 14].toInt() and 0xFF) shl 24)
                    val iChannelNames = raw[off + 15].toInt() and 0xFF
                    val formatStr = if ((bmFormats and 0x01) != 0) "PCM" else "0x${bmFormats.toString(16)}"
                    sb.appendLine("  AS_GENERAL(UAC2): Terminal=$bTerminalLink, Format=$formatStr, ${bNrChannels}ch")
                    sb.appendLine("    ChannelConfig=0x${bmChannelConfig.toString(16).padStart(8, '0')}")
                }
            }
            0x02 -> { // FORMAT_TYPE
                if (bLength >= 6) {
                    val bFormatType = raw[off + 3].toInt() and 0xFF
                    if (bFormatType == 1) {
                        // UAC2 FORMAT_TYPE: bSubslotSize at [4], bBitResolution at [5]
                        val bSubslotSize = raw[off + 4].toInt() and 0xFF
                        val bBitResolution = if (bLength > 5) raw[off + 5].toInt() and 0xFF else 0
                        val effectiveBits = if (bBitResolution != 0) bBitResolution else bSubslotSize * 8
                        sb.appendLine("  FORMAT_TYPE_I(UAC2): SubslotSize=${bSubslotSize}bytes (${bSubslotSize * 8}bit), Resolution=${effectiveBits}bit")
                    }
                }
            }
            0x03 -> { // UAC2 Clock Source
                if (bLength >= 5) {
                    val bClockID = raw[off + 3].toInt() and 0xFF
                    val bmAttributes = raw[off + 4].toInt() and 0xFF
                    val clockType = when (bmAttributes and 0x03) {
                        0x00 -> "External"
                        0x01 -> "Internal fixed"
                        0x02 -> "Internal programmable"
                        0x03 -> "Internal + PLL"
                        else -> "Unknown"
                    }
                    sb.appendLine("  Clock Source: ID=$bClockID, Type=$clockType")
                }
            }
        }
    }

    // ===================== 辅助函数 =====================

    private fun getStringDescriptor(raw: ByteArray, index: Int): String {
        // 查找 USB STRING 描述符 (bDescriptorType=3)
        var off = 0
        var count = 0
        while (off + 1 < raw.size) {
            val bLength = raw[off].toInt() and 0xFF
            if (bLength < 2 || off + bLength > raw.size) break
            val bDescriptorType = raw[off + 1].toInt() and 0xFF
            if (bDescriptorType == 3) {
                count++
                if (count == index) {
                    return decodeStringDescriptor(raw, off, bLength)
                }
            }
            off += bLength
        }
        return "[index=$index not found]"
    }

    private fun decodeStringDescriptor(raw: ByteArray, off: Int, bLength: Int): String {
        if (bLength <= 2) return ""
        val charCount = (bLength - 2) / 2
        val chars = CharArray(charCount)
        for (i in 0 until charCount) {
            val lo = raw[off + 2 + i * 2].toInt() and 0xFF
            val hi = raw[off + 3 + i * 2].toInt() and 0xFF
            chars[i] = ((hi shl 8) or lo).toChar()
        }
        return String(chars)
    }

    private fun bcdToString(lo: Byte, hi: Byte): String {
        val l = lo.toInt() and 0xFF
        val h = hi.toInt() and 0xFF
        return "${h shr 4}.${h and 0xF}.${l shr 4}.${l and 0xF}"
    }

    private fun formatTagToString(tag: Int): String = when (tag) {
        0x0001 -> "PCM"
        0x0002 -> "PCM8"
        0x0003 -> "IEEE_FLOAT"
        0x0004 -> "AC-3"
        0x0005 -> "MPEG"
        0x0006 -> "WMA"
        0x0007 -> "DTS"
        0x0008 -> "DRM"
        0x0009 -> "WMA Pro"
        0x000A -> "MPEG-2 AAC"
        0x000B -> "MPEG-4 AAC"
        0x0010 -> "MPEG-4 AAC LATM"
        else -> "0x${tag.toString(16).padStart(4, '0')}"
    }

    private fun buildBasicInfo(device: UsbDevice, note: String): String {
        val sb = StringBuilder()
        sb.appendLine("========== USB Audio 设备日志 ==========")
        sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()
        sb.appendLine("── 设备信息 ──")
        sb.appendLine("名称: ${device.deviceName}")
        sb.appendLine("VID:  0x${device.vendorId.toString(16).uppercase().padStart(4, '0')}")
        sb.appendLine("PID:  0x${device.productId.toString(16).uppercase().padStart(4, '0')}")
        sb.appendLine("产品: ${device.productName ?: "未知"}")
        sb.appendLine("厂商: ${device.manufacturerName ?: "未知"}")
        sb.appendNote("注意: $note")
        sb.appendLine()
        sb.appendLine("========== 日志结束 ==========")
        return sb.toString()
    }

    private fun StringBuilder.appendNote(note: String): StringBuilder = this.appendLine(note)

    // ===================== 文件写入 =====================

    private fun writeLog(content: String) {
        try {
            val dir = File(LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val fileName = "usb-${timeFormat.format(Date())}"
            val file = File(dir, fileName)
            file.writeText(content)
            Log.i(TAG, "日志已写入: ${file.absolutePath}")
            FoldLogger.i(TAG, "USB attach log: ${file.absolutePath}, size=${content.length}")
        } catch (e: Exception) {
            Log.e(TAG, "写入日志失败", e)
            FoldLogger.e(TAG, "写入USB日志失败: ${e.message}")
        }
    }
}
