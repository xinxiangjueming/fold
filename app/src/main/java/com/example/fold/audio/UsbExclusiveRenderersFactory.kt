package com.example.fold.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

class UsbExclusiveRenderersFactory(
    context: Context,
    private val stream: UsbAudioStream,
    private val deviceInfo: UsbAudioDeviceInfo? = null,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val sink = UsbExclusiveAudioSink(stream)
        if (deviceInfo != null) sink.setDeviceInfo(deviceInfo)
        return sink
    }
}
