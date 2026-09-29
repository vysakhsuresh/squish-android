@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A boost above a player's own ceiling, for the preview: a player's volume
 * stops at 1, so the part of a sound's level past 100% is applied here, in
 * front of the sink, from a value the tick writes ([gainNow]; see
 * AudioRules.gainSplit). At 1 it passes audio through untouched. Clipped to
 * the sample range the way the export's mixer clips, so a level that
 * distorts in the file distorts the same on the phone's speaker first.
 */
class GainProcessor(private val gainNow: () -> Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val gain = gainNow()
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (gain == 1f) {
            out.put(input)
        } else {
            while (input.remaining() >= 2) {
                val s = input.short * gain
                out.putShort(s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        out.flip()
    }
}
