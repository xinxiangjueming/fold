package com.example.fold.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Creates UsbExclusiveAudioSink with a muted DefaultAudioSink delegate.
 * The delegate stays alive for ExoPlayer's clock/position tracking while
 * audio is routed to the USB DAC via isochronous transfers.
 */
class UsbExclusiveRenderersFactory(
    context: Context,
    private val stream: UsbAudioStream,
    private val deviceInfo: UsbAudioDeviceInfo? = null,
    private val onStreamStopped: (() -> Unit)? = null,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val delegate = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        return UsbExclusiveAudioSink(delegate, stream, deviceInfo, onStreamStopped)
    }
}
