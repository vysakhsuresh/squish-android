@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.squish.app.editor.VoiceEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The voice effects that are not a pitch change - robot, echo, radio - on 16-bit
 * PCM, any channel count.
 *
 * [effectNow] is read on every buffer, so the preview can install this once and
 * change the effect by writing a value; the export hands it a fixed one. At
 * [VoiceEffect.None], or at a pitch-only effect, it passes audio through untouched.
 */
class VoiceProcessor(private val effectNow: () -> VoiceEffect) : BaseAudioProcessor() {

    private var sampleRate = 44_100
    private var channels = 2

    // Echo: one delay line per channel, a quarter second long.
    private var delay = FloatArray(0)
    private var delayPos = 0

    // Robot: the carrier's phase.
    private var phase = 0.0

    // Radio: one-pole filter state per channel.
    private var hp = FloatArray(0)
    private var hpPrev = FloatArray(0)
    private var lp = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        val delayFrames = (sampleRate * ECHO_SECONDS).toInt().coerceAtLeast(1)
        delay = FloatArray(delayFrames * channels)
        delayPos = 0
        hp = FloatArray(channels)
        hpPrev = FloatArray(channels)
        lp = FloatArray(channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val effect = effectNow()
        val carrierStep = 2.0 * PI * ROBOT_HZ / sampleRate
        // One-pole coefficients for a 400 Hz high-pass and a 3 kHz low-pass: a phone
        // speaker's range, which is what "radio" means to an ear.
        val hpA = (1.0 / (1.0 + 2.0 * PI * 400.0 / sampleRate)).toFloat()
        val lpA = (1.0 - kotlin.math.exp(-2.0 * PI * 3000.0 / sampleRate)).toFloat()

        var channel = 0
        while (input.remaining() >= 2) {
            val s = input.short / 32768f
            val y = when (effect) {
                VoiceEffect.Robot -> {
                    // Ring modulation: the voice multiplied by a low tone, mixed with a
                    // little of the dry voice so the words stay clear.
                    val carrier = sin(phase).toFloat()
                    s * (0.25f + 0.75f * carrier)
                }
                VoiceEffect.Echo -> {
                    val index = delayPos * channels + channel
                    val echoed = s + delay[index] * ECHO_MIX
                    delay[index] = s + delay[index] * ECHO_FEEDBACK
                    echoed
                }
                VoiceEffect.Radio -> {
                    val high = hpA * (hp[channel] + s - hpPrev[channel])
                    hpPrev[channel] = s
                    hp[channel] = high
                    lp[channel] += lpA * (high - lp[channel])
                    tanh(lp[channel] * 2.2f) * 0.8f
                }
                else -> s
            }
            out.putShort((y.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            channel++
            if (channel == channels) {
                channel = 0
                phase += carrierStep
                if (phase > 2 * PI) phase -= 2 * PI
                delayPos = (delayPos + 1) % (delay.size / channels.coerceAtLeast(1)).coerceAtLeast(1)
            }
        }
        out.flip()
    }

    override fun onFlush() {
        delay.fill(0f)
        delayPos = 0
        hp.fill(0f)
        hpPrev.fill(0f)
        lp.fill(0f)
    }

    private companion object {
        const val ROBOT_HZ = 55.0
        const val ECHO_SECONDS = 0.24
        const val ECHO_MIX = 0.55f
        const val ECHO_FEEDBACK = 0.42f
    }
}
