package com.example.fold.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Manifest 注册的 USB 设备插入接收器
 * 当 app 未运行时系统会唤醒 app 并触发此 receiver
 * 委托给 UsbAttachLogger 处理实际日志逻辑
 */
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        Log.i("UsbAttachReceiver", "USB_DEVICE_ATTACHED: ${device.deviceName}")
        UsbAttachLogger.onDeviceAttached(context, device)
    }
}
