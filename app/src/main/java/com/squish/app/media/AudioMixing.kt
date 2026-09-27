@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi

/**
 * Per-track gain, so the original camera audio and a separate track can be balanced
 * against each other instead of one drowning the other.
 *
 * Deliberately isolated in its own file: ChannelMixingMatrix is the least
 * battle-tested API in this app, so if it needs a signature tweak it is a
 * one-file fix and nothing else in the export path is touched.
 */
object AudioMixing {

    fun gain(volume: Float): AudioProcessor? {
        if (volume >= 0.999f) return null
        val processor = ChannelMixingAudioProcessor()
        for (channelCount in 1..2) {
            processor.putChannelMixingMatrix(
                ChannelMixingMatrix.create(channelCount, channelCount).scaleBy(volume.coerceIn(0f, 1f))
            )
        }
        return processor
    }
}
