@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.squish.app.editor.AudioRules
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A clip's fades, for the export: the level at each frame from
 * [AudioRules.fadeGain], the same arithmetic the preview applies per tick.
 *
 * Time is counted in frames written, so it must sit *after* the speed change
 * in a clip's processors: there the stream runs in played time, which is the
 * clock the fades are set in. Before it, a song at half speed would fade in
 * over half the seconds the strip shows.
 *
 * [startMs] is how far into the clip's played time the stream begins - a
 * sound dragged back past zero is heard from where zero falls in it, and its
 * fade in has partly happened by then.
 */
class FadeProcessor(
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val lengthMs: Long,
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

    override fun isActive(): Boolean = super.isActive() && (fadeInMs > 0L || fadeOutMs > 0L)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        var channel = 0
        // One level per frame, not per sample: the channels of a frame are the
        // same moment, and a fade moving within a frame would tilt the stereo.
        var gain = gainAt(framesDone)
        while (input.remaining() >= 2) {
            val s = input.short
            out.putShort((s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            channel++
            if (channel == channels) {
                channel = 0
                framesDone++
                gain = gainAt(framesDone)
            }
        }
        out.flip()
    }

    private fun gainAt(frame: Long): Float {
        val playedMs = startMs + frame * 1000L / sampleRate
        return AudioRules.fadeGain(playedMs, lengthMs, fadeInMs, fadeOutMs)
    }

    override fun onFlush() {
        framesDone = 0L
    }
}
