package com.example.fold.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.fold.ui.player.MusicPlayerHolder

/**
 * Manifest 注册的 USB 设备插入/拔出接收器
 * 当 app 未运行时系统会唤醒 app 并触发此 receiver
 */
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.i(TAG, "USB_DEVICE_ATTACHED: ${device.deviceName}")
                UsbAttachLogger.onDeviceAttached(context, device)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.i(TAG, "USB_DEVICE_DETACHED: ${device.deviceName}")
                handleDetach(context, device)
            }
        }
    }

    private fun handleDetach(context: Context, device: UsbDevice) {
        val exclusive = MusicPlayerHolder.exclusiveDevice ?: return
        if (device.deviceName != exclusive.usbDevice.deviceName) return
        Log.w(TAG, "Active USB DAC detached, disabling exclusive mode")
        MusicPlayerHolder.releasePlayer()
        MusicPlayerHolder.disableExclusiveMode(context.applicationContext)
    }

    companion object {
        private const val TAG = "UsbAttachReceiver"
    }
}
