@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A level that changes over a clip, for the export: [gainAt] read per frame
 * in played milliseconds - a clip's volume keys (Clip.volumeAt), the same
 * numbers the preview sets its player to each tick.
 *
 * Like FadeProcessor it counts time in frames written, so it sits after the
 * speed change, where the stream runs in played time; [startMs] is how far into
 * the clip the stream begins. Levels above one are allowed, as the mixer's are
 * (AudioMixing), and clip to the sample range the same way.
 */
class GainCurveProcessor(
    private val gainAt: (playedMs: Long) -> Float,
    private val startMs: Long = 0L
) : BaseAudioProcessor() {

    private var sampleRate = 44_100
    private var channels = 2
    private var framesDone = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        framesDone = 0L
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        var channel = 0
        // One level per frame, so a change never tilts the stereo within a frame.
        var gain = levelAt(framesDone)
        while (input.remaining() >= 2) {
            val s = input.short
            out.putShort((s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            channel++
            if (channel == channels) {
                channel = 0
                framesDone++
                // Read every few milliseconds, not every frame: the curve is
                // smooth at that scale and the lookup walks a list.
                if (framesDone % STEP_FRAMES == 0L) gain = levelAt(framesDone)
            }
        }
        out.flip()
    }

    private fun levelAt(frame: Long): Float = gainAt(startMs + frame * 1000L / sampleRate).coerceAtLeast(0f)

    override fun onFlush() {
        framesDone = 0L
    }

    private companion object {
        /** About a millisecond at 48 kHz. */
        const val STEP_FRAMES = 48L
    }
}
