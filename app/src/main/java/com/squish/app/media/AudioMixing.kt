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
     * matrix, which Media3 skips outright (`onConfigure` returns
     * `AudioFormat.NOT_SET` when the matrix `isIdentity()`), so the common case
     * costs nothing.
     *
     * A matrix for every count up to [ExportPlan.MAX_ANY_CHANNELS], not only
     * the ones with a layout: a count with no matrix makes `onConfigure` throw,
     * and that is a failed render rather than a worse mix.
     *
     * The preview puts this in front of its own voice processors too, so a
     * multichannel file is folded down by these coefficients on both sides.
     * Left to the sink, it was not folded down before the voice at all -
     * `ChannelMappingAudioProcessor` gets a null map except on two device
     * workarounds, so the preview's voice saw six channels where the file's saw
     * two, and the saturating voices are not linear.
     */
    fun processor(volume: Float): AudioProcessor {
        val gain = volume.coerceIn(0f, MAX_GAIN)
        val processor = ChannelMixingAudioProcessor()
        for (channels in 1..ExportPlan.MAX_ANY_CHANNELS) {
            val out = ExportPlan.downmixOutputChannels(channels)
            processor.putChannelMixingMatrix(
                ChannelMixingMatrix(channels, out, ExportPlan.downmixCoefficients(channels)).scaleBy(gain)
            )
        }
        return processor
    }

    /**
     * As loud as the preview can go: an added sound may be turned up to four
     * times its own level, and the preview boosts the part above the players'
     * ceiling with a processor of its own (GainProcessor), so a file at this
     * level is what was heard while editing. The mixer clips to the sample
     * range, as the preview's processor does.
     */
    const val MAX_GAIN = com.squish.app.editor.AudioRules.MAX_SOUND_GAIN
}
