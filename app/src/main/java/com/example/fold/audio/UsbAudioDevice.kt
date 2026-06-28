package com.example.fold.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

data class UsbAudioDevice(val usbDevice: UsbDevice, val productName: String)

data class AudioFormat(val sampleRate: Int, val bitDepth: Int, val channels: Int) {
    val label: String get() = "${sampleRate / 1000}k ${bitDepth}bit"
}

data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceName: String,
    val interfaceId: Int,
    val endpointOut: Int,
    val endpointFeedback: Int,
    val maxPacketSize: Int,
    val clockSourceId: Int,
    val bestAltSetting: Int,
    val bestBitDepth: Int,
)

object UsbAudioDeviceManager {

    private const val TAG = "UsbAudioDevice"
    private const val ACTION_USB_PERMISSION = "com.example.fold.USB_PERMISSION"
    private const val USB_CLASS_AUDIO = 0x01
    private const val USB_SUBCLASS_AUDIOCONTROL = 0x01
    private const val USB_SUBCLASS_AUDIOSTREAMING = 0x02

    fun scanDevices(usbManager: UsbManager): List<UsbAudioDevice> {
        val result = mutableListOf<UsbAudioDevice>()
        Log.i(TAG, "Scanning USB devices, total devices: ${usbManager.deviceList.size}")
        for (device in usbManager.deviceList.values) {
            Log.i(TAG, "Checking device: ${device.productName ?: "unknown"}, " +
                "VID=${device.vendorId.toString(16)}, PID=${device.productId.toString(16)}, " +
                "interfaces=${device.interfaceCount}")
            if (isUsbAudioDevice(device)) {
                Log.i(TAG, "Found USB audio device: ${device.productName}")
                result.add(
                    UsbAudioDevice(
                        usbDevice = device,
                        productName = device.productName
                            ?: "USB Audio (VID:${device.vendorId.toString(16)} PID:${device.productId.toString(16)})",
                    )
                )
            }
        }
        Log.i(TAG, "Found ${result.size} USB audio devices")
        return result
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_AUDIO) return true
        }
        return false
    }

    fun requestPermission(device: UsbDevice, context: Context, callback: (Boolean) -> Unit) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
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
        } else {
            0
        }

        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
    }

    fun openAndInit(device: UsbAudioDevice, context: Context): UsbAudioDeviceInfo? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device.usbDevice)) {
            Log.e(TAG, "No USB permission for device")
            return null
        }

        val connection = usbManager.openDevice(device.usbDevice)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device")
            return null
        }

        val audioControlInterface = findAudioControlInterface(device.usbDevice)

        val altInfo = parseBestAltSetting(connection)
        val interfaceId = altInfo.interfaceId
        val bestAlt = altInfo.altSetting
        val bestBits = altInfo.bitDepth

        if (interfaceId < 0) {
            Log.e(TAG, "No valid AudioStreaming interface found")
            connection.close()
            return null
        }

        val clockSourceId = if (audioControlInterface != null) {
            parseClockSourceId(connection)
        } else {
            Log.w(TAG, "No AudioControl interface, using default clock source id=0")
            0
        }

        val endpointOut = findEndpointOut(device.usbDevice, interfaceId, bestAlt)
        val endpointFeedback = findEndpointFeedback(device.usbDevice, interfaceId, bestAlt)
        val maxPacketSize = getMaxPacketSize(device.usbDevice, interfaceId, bestAlt, endpointOut)

        if (endpointOut == -1) {
            Log.e(TAG, "No OUT endpoint found in alt setting $bestAlt")
            connection.close()
            return null
        }

        Log.i(
            TAG, "Device opened: ${device.productName}, " +
                "if=$interfaceId, epOut=0x${endpointOut.toString(16)}, " +
                "epFb=0x${endpointFeedback.toString(16)}, maxPkt=$maxPacketSize, " +
                "csId=$clockSourceId, bestAlt=$bestAlt, bestBits=$bestBits"
        )

        return UsbAudioDeviceInfo(
            connection = connection,
            fd = connection.fileDescriptor,
            deviceName = device.productName,
            interfaceId = interfaceId,
            endpointOut = endpointOut,
            endpointFeedback = endpointFeedback,
            maxPacketSize = maxPacketSize,
            clockSourceId = clockSourceId,
            bestAltSetting = bestAlt,
            bestBitDepth = bestBits,
        )
    }

    fun parseClockSourceId(connection: UsbDeviceConnection): Int {
        val raw = connection.rawDescriptors ?: return 0
        var i = 0
        while (i < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2 || i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                if (bInterfaceClass == USB_CLASS_AUDIO && bInterfaceSubClass == USB_SUBCLASS_AUDIOCONTROL) {
                    var j = i + bLength
                    while (j < raw.size) {
                        val csLength = raw[j].toInt() and 0xFF
                        if (csLength < 2 || j + csLength > raw.size) break
                        val csDescType = raw[j + 1].toInt() and 0xFF
                        if (csDescType == 0x24 && csLength >= 8) {
                            val bDescriptorSubtype = raw[j + 2].toInt() and 0xFF
                            if (bDescriptorSubtype == 0x0A) {
                                val csId = raw[j + 3].toInt() and 0xFF
                                Log.i(TAG, "Found clock source id=$csId")
                                return csId
                            }
                        }
                        j += csLength
                    }
                }
            }
            i += bLength
        }
        Log.w(TAG, "Clock source descriptor not found, defaulting to 0")
        return 0
    }

    data class AltSettingInfo(val interfaceId: Int, val altSetting: Int, val bitDepth: Int)

    fun parseBestAltSetting(connection: UsbDeviceConnection): AltSettingInfo {
        val raw = connection.rawDescriptors ?: return AltSettingInfo(-1, 1, 16)

        // First pass: find which interfaces have ISO OUT endpoints
        val interfacesWithIsoOut = findInterfacesWithIsoOut(raw)

        var bestAlt = -1
        var bestBitDepth = 0
        var bestInterfaceId = -1
        var bestMaxPkt = 0
        var currentAltSetting = -1
        var currentInterfaceId = -1
        var currentMaxPkt = 0
        var inAudioStreaming = false

        var i = 0
        while (i < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2 || i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceNumber = raw[i + 2].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF

                if (bInterfaceClass == USB_CLASS_AUDIO && bInterfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING) {
                    inAudioStreaming = true
                    currentInterfaceId = bInterfaceNumber
                    currentAltSetting = bAlternateSetting
                    currentMaxPkt = 0
                } else {
                    inAudioStreaming = false
                }
            } else if (inAudioStreaming && bDescriptorType == 0x05) {
                val epAddr = raw[i + 2].toInt() and 0xFF
                if (epAddr and 0x80 == 0) { // OUT endpoint
                    currentMaxPkt = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                    currentMaxPkt = currentMaxPkt and 0x7FF
                }
            } else if (inAudioStreaming && bDescriptorType == 0x24) {
                val bDescriptorSubtype = if (i + 2 < raw.size) raw[i + 2].toInt() and 0xFF else 0
                if (bDescriptorSubtype == 0x02 && i + 3 < raw.size) {
                    val bFormatType = raw[i + 3].toInt() and 0xFF
                    if (bFormatType == 0x01) {
                        val nrCh = if (i + 4 < raw.size) raw[i + 4].toInt() and 0xFF else 0
                        val subSlotSize = if (i + 5 < raw.size) raw[i + 5].toInt() and 0xFF else 0
                        val bitResolution = if (i + 6 < raw.size && bLength >= 7) raw[i + 6].toInt() and 0xFF else 0

                        val validSubSlot = subSlotSize in 1..4
                        val bitDepth = when {
                            validSubSlot -> subSlotSize * 8
                            bitResolution > 0 -> bitResolution
                            else -> 0
                        }

                        Log.i(TAG, "FORMAT_TYPE_I: iface=$currentInterfaceId alt=$currentAltSetting " +
                            "nrCh=$nrCh subSlot=$subSlotSize bitRes=$bitResolution maxPkt=$currentMaxPkt" +
                            if (validSubSlot) " -> bitDepth=$bitDepth" else " -> INVALID subSlot")

                        val effectiveBitDepth = if (bitDepth > 0) bitDepth else {
                            val samplesPerUf = (48000 + 7999) / 8000
                            when {
                                currentMaxPkt >= samplesPerUf * nrCh * 4 -> 32
                                currentMaxPkt >= samplesPerUf * nrCh * 3 -> 24
                                currentMaxPkt >= samplesPerUf * nrCh * 2 -> 16
                                else -> 16
                            }.also { inferred ->
                                Log.i(TAG, "  Inferred bitDepth=$inferred from maxPkt=$currentMaxPkt nrCh=$nrCh")
                            }
                        }

                        // Prefer interface with ISO OUT endpoint; if tied, prefer higher bitDepth
                        val hasIsoOut = currentInterfaceId in interfacesWithIsoOut
                        val isBetter = when {
                            effectiveBitDepth > bestBitDepth -> true
                            effectiveBitDepth == bestBitDepth && hasIsoOut && bestInterfaceId !in interfacesWithIsoOut -> true
                            else -> false
                        }
                        if (isBetter) {
                            bestBitDepth = effectiveBitDepth
                            bestAlt = currentAltSetting
                            bestInterfaceId = currentInterfaceId
                            bestMaxPkt = currentMaxPkt
                        }
                    }
                }
            }
            i += bLength
        }

        if (bestAlt < 0 || bestBitDepth == 0) {
            Log.w(TAG, "No FORMAT_TYPE_I descriptor found, scanning for any ISO OUT endpoint")
            return fallbackFindAltSetting(raw)
        }
        Log.i(TAG, "Best alt setting: iface=$bestInterfaceId alt=$bestAlt bitDepth=$bestBitDepth maxPkt=$bestMaxPkt")
        return AltSettingInfo(bestInterfaceId, bestAlt, bestBitDepth)
    }

    /** Find interface IDs that have ISO OUT endpoints */
    private fun findInterfacesWithIsoOut(raw: ByteArray): Set<Int> {
        val result = mutableSetOf<Int>()
        var i = 0
        var currentIface = -1
        var inAudioStreaming = false
        while (i < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2 || i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                if (bInterfaceClass == USB_CLASS_AUDIO && bInterfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING) {
                    inAudioStreaming = true
                    currentIface = raw[i + 2].toInt() and 0xFF
                } else {
                    inAudioStreaming = false
                }
            } else if (inAudioStreaming && bDescriptorType == 0x05 && bLength >= 7) {
                val epAddr = raw[i + 2].toInt() and 0xFF
                val epAttr = raw[i + 3].toInt() and 0xFF
                // OUT isochronous endpoint
                if (epAddr and 0x80 == 0 && (epAttr and 0x03) == 0x01) {
                    result.add(currentIface)
                }
            }
            i += bLength
        }
        Log.i(TAG, "Interfaces with ISO OUT endpoint: $result")
        return result
    }

    private fun fallbackFindAltSetting(raw: ByteArray): AltSettingInfo {
        var i = 0
        var bestInterfaceId = -1
        var bestAlt = -1
        while (i < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2 || i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceNumber = raw[i + 2].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                val bNumEndpoints = raw[i + 4].toInt() and 0xFF

                if (bInterfaceClass == USB_CLASS_AUDIO && bInterfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING
                    && bAlternateSetting > 0 && bNumEndpoints > 0) {
                    bestInterfaceId = bInterfaceNumber
                    bestAlt = bAlternateSetting
                }
            }
            i += bLength
        }
        if (bestAlt < 0) {
            Log.e(TAG, "fallbackFindAltSetting: no AudioStreaming alt setting found")
            return AltSettingInfo(-1, 1, 16)
        }
        Log.i(TAG, "fallbackFindAltSetting: iface=$bestInterfaceId alt=$bestAlt bitDepth=16")
        return AltSettingInfo(bestInterfaceId, bestAlt, 16)
    }

    fun setSampleRate(connection: UsbDeviceConnection, rate: Int, csId: Int) {
        val data = ByteArray(4)
        data[0] = (rate and 0xFF).toByte()
        data[1] = ((rate shr 8) and 0xFF).toByte()
        data[2] = ((rate shr 16) and 0xFF).toByte()
        data[3] = ((rate shr 24) and 0xFF).toByte()

        val result = connection.controlTransfer(
            0x21, 0x01, 0x0100,
            (csId shl 8) or 0x0100,
            data, data.size, 1000
        )
        if (result < 0) {
            Log.e(TAG, "SET_CUR sample rate failed: $result")
        } else {
            Log.i(TAG, "SET_CUR sample rate=$rate, csId=$csId, result=$result")
        }
    }

    fun readClockValid(connection: UsbDeviceConnection, csId: Int): Boolean {
        val data = ByteArray(1)
        val result = connection.controlTransfer(
            0xA1, 0x81, 0x0100,
            (csId shl 8) or 0x0100,
            data, data.size, 1000
        )
        if (result < 0) {
            Log.w(TAG, "GET_CUR clock valid failed: $result")
            return false
        }
        val valid = (data[0].toInt() and 0x01) != 0
        Log.i(TAG, "Clock valid=$valid (byte=0x${data[0].toString(16)})")
        return valid
    }

    private fun findAltInterface(device: UsbDevice, interfaceId: Int, altSetting: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == interfaceId && iface.alternateSetting == altSetting) {
                return iface
            }
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == interfaceId) return iface
        }
        return null
    }

    private fun findEndpointOut(device: UsbDevice, interfaceId: Int, altSetting: Int): Int {
        val alt = findAltInterface(device, interfaceId, altSetting) ?: return -1
        for (j in 0 until alt.endpointCount) {
            val ep = alt.getEndpoint(j)
            if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                return ep.address
            }
        }
        return -1
    }

    private fun findEndpointFeedback(device: UsbDevice, interfaceId: Int, altSetting: Int): Int {
        val alt = findAltInterface(device, interfaceId, altSetting) ?: return 0
        for (j in 0 until alt.endpointCount) {
            val ep = alt.getEndpoint(j)
            if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                return ep.address
            }
        }
        return 0
    }

    private fun getMaxPacketSize(device: UsbDevice, interfaceId: Int, altSetting: Int, endpointOut: Int): Int {
        val alt = findAltInterface(device, interfaceId, altSetting) ?: return 512
        for (j in 0 until alt.endpointCount) {
            val ep = alt.getEndpoint(j)
            if (ep.address == endpointOut) {
                return ep.maxPacketSize
            }
        }
        return 512
    }

    private fun findAudioControlInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == USB_SUBCLASS_AUDIOCONTROL) {
                return iface
            }
        }
        return null
    }

    private fun findAudioStreamingInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING) {
                return iface
            }
        }
        return null
    }
}
