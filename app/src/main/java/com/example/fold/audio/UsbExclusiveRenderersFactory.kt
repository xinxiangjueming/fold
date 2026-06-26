package com.example.fold.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

/**
 * 自定义 RenderersFactory：覆写 buildAudioSink() 注入 UsbExclusiveAudioSink
 */
class UsbExclusiveRenderersFactory(
    context: Context,
    private val usbAudio: UsbAudioNative,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        return UsbExclusiveAudioSink(usbAudio)
    }
}
