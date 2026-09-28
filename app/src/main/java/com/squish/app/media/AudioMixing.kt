@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi

/**
 * Per-track gain, so the original camera audio and a separate track can be balanced
 * against each other instead of one drowning the other - and the fold-down of
 * anything wider than stereo, which has to happen in the same place.
 *
 * Deliberately isolated in its own file: ChannelMixingMatrix is the least
 * battle-tested API in this app, so if it needs a signature tweak it is a
 * one-file fix and nothing else in the export path is touched.
 */
object AudioMixing {

    /**
     * Every input's sound as mono or stereo, at [volume].
     *
     * Always present, not only below full volume. A 5.1 film rip or a phone that
     * records six channels used to fail the export twice over: the gain had
     * matrices for one and two channels only, so any level below 100% threw, and
     * even at 100% Media3's mixer has no default for folding stereo music into a
     * six-channel bed. Folded to stereo here, first, every input reaches the mixer
     * in a shape it takes. A mono or stereo input at full volume is an identity
     * matrix, which Media3 skips outright, so the common case costs nothing.
     */
    fun processor(volume: Float): AudioProcessor {
        val gain = volume.coerceIn(0f, MAX_GAIN)
        val processor = ChannelMixingAudioProcessor()
        for (channels in 1..ExportPlan.MAX_INPUT_CHANNELS) {
            val out = ExportPlan.downmixOutputChannels(channels)
            processor.putChannelMixingMatrix(
                ChannelMixingMatrix(channels, out, ExportPlan.downmixCoefficients(channels)).scaleBy(gain)
            )
        }
        return processor
    }

    /** Gain alone, null at full volume - the level-only form the export still calls. */
    fun gain(volume: Float): AudioProcessor? = if (volume >= 0.999f) null else processor(volume)

    /**
     * No louder than the source. The preview's players cannot turn a sound up
     * past its own level, and a file that is louder than what was heard while
     * editing is not what was mixed.
     */
    private const val MAX_GAIN = 1f
}
