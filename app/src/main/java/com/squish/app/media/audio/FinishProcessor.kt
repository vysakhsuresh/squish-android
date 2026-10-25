@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A clip's finishing (EDGE_PLAN G4, AudioFinish) in Media3's audio chain, on
 * 16-bit PCM: its equaliser, then its compressor, then a limiter that keeps
 * the two from clipping. The same class in a preview player's sink and in the
 * export's processors, so what is heard is what is written.
 *
 * [settingsNow] is read on every buffer, as NoiseProcessor's strength is; a
 * changed setting rebuilds the filters, which costs a few samples of their
 * memory and nothing anyone hears. With nothing set it passes the sound
 * through byte for byte. Nothing here delays the sound by more than a sample:
 * the limiter looks no further ahead than the sample it is on.
 */
class FinishProcessor(private val settingsNow: () -> FinishSettings) : BaseAudioProcessor() {

    private var rate = 48_000
    private var channels = 2
    private var built: FinishSettings? = null
    private var eq: AudioFinish.Equaliser? = null
    private var compressor: AudioFinish.Compressor? = null
    private var limiter: AudioFinish.Limiter? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        built = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val s = settingsNow()
        if (s.isOff) {
            val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (s != built) build(s)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val frame = FloatArray(channels)
        val frames = remaining / 2 / channels
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) {
                var v = input.short / 32768f * s.level
                eq?.let { v = it.run(v, c) }
                frame[c] = v
                peak = maxOf(peak, kotlin.math.abs(v))
            }
            compressor?.let { comp -> val g = comp.gain(peak); for (c in 0 until channels) frame[c] *= g }
            limiter?.run(frame)
            for (c in 0 until channels) out.putShort((frame[c].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.flip()
    }

    private fun build(s: FinishSettings) {
        eq = AudioFinish.Equaliser(s.gains.toFloatArray(), rate, channels).takeIf { !it.isFlat }
        compressor = if (s.compress > 0f) AudioFinish.compressorFor(s.compress, rate) else null
        limiter = AudioFinish.Limiter(AudioFinish.CEILING, rate, channels, lookaheadMs = 0f)
        built = s
    }

    override fun onFlush() { built = null }
    override fun onReset() { built = null; eq = null; compressor = null; limiter = null }
}
