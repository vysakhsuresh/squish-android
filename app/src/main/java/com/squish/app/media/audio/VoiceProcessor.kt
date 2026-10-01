@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.squish.app.timeline.VoiceEffect
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
    private var lastEffect: VoiceEffect? = null
    private var alienPhase = 0.0
    private var wobblePhase = 0.0

    // Radio: one-pole filter state per channel.
    private var hp = FloatArray(0)
    private var hpPrev = FloatArray(0)
    private var lp = FloatArray(0)

    // Enhance: its own state, made for the format.
    private var cleaner: VoiceCleaner? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        // Long enough for Cave, the longest; Echo uses the first part of it.
        val delayFrames = (sampleRate * CAVE_SECONDS).toInt().coerceAtLeast(1)
        delay = FloatArray(delayFrames * channels)
        delayPos = 0
        hp = FloatArray(channels)
        hpPrev = FloatArray(channels)
        lp = FloatArray(channels)
        cleaner = VoiceCleaner(sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val effect = effectNow()
        // A new effect starts from silence: Echo and Cave share one delay line,
        // and a switch with no seek between played the last one's tail.
        if (effect != lastEffect) {
            onFlush()
            lastEffect = effect
        }
        val carrierStep = 2.0 * PI * ROBOT_HZ / sampleRate
        // One-pole coefficients for a 400 Hz high-pass and a 3 kHz low-pass: a phone
        // speaker's range, which is what "radio" means to an ear.
        val hpA = (1.0 / (1.0 + 2.0 * PI * 400.0 / sampleRate)).toFloat()
        val lpA = (1.0 - kotlin.math.exp(-2.0 * PI * 3000.0 / sampleRate)).toFloat()
        // Telephone: 700 Hz to 2.4 kHz. Megaphone: 500 Hz to 3.5 kHz.
        val telHpA = (1.0 / (1.0 + 2.0 * PI * 700.0 / sampleRate)).toFloat()
        val telLpA = (1.0 - kotlin.math.exp(-2.0 * PI * 2400.0 / sampleRate)).toFloat()
        val megHpA = (1.0 / (1.0 + 2.0 * PI * 500.0 / sampleRate)).toFloat()
        val megLpA = (1.0 - kotlin.math.exp(-2.0 * PI * 3500.0 / sampleRate)).toFloat()
        val echoFrames = (sampleRate * ECHO_SECONDS).toInt().coerceIn(1, delay.size / channels.coerceAtLeast(1))
        val caveFrames = (delay.size / channels.coerceAtLeast(1)).coerceAtLeast(1)
        val delayFrames = if (effect == VoiceEffect.Cave) caveFrames else echoFrames
        if (delayPos >= delayFrames) delayPos = 0
        val alienStep = 2.0 * PI * ALIEN_HZ / sampleRate
        val wobbleStep = 2.0 * PI * WOBBLE_HZ / sampleRate

        var channel = 0
        while (input.remaining() >= 2) {
            val raw = input.short
            val s = raw / 32768f
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
                VoiceEffect.Enhance -> cleaner?.process(s, channel) ?: s
                VoiceEffect.Radio -> band(s, channel, hpA, lpA, drive = 2.2f, level = 0.8f)
                VoiceEffect.Telephone -> band(s, channel, telHpA, telLpA, drive = 3.2f, level = 0.75f)
                VoiceEffect.Megaphone -> band(s, channel, megHpA, megLpA, drive = 5f, level = 0.85f)
                VoiceEffect.Cave -> {
                    val index = delayPos * channels + channel
                    val echoed = s + delay[index] * CAVE_MIX
                    // Darkened on each pass, as stone swallows the highs.
                    delay[index] = (s + delay[index] * CAVE_FEEDBACK) * 0.92f
                    echoed * 0.8f
                }
                VoiceEffect.Wobble -> s * (0.72f + 0.28f * sin(wobblePhase).toFloat())
                VoiceEffect.Alien -> s * (0.55f + 0.45f * sin(alienPhase).toFloat())
                else -> s
            }
            // Untouched when nothing processes it: through the float and back it
            // lost a part in 32768 on every sample of every clip in the preview.
            out.putShort(if (y == s) raw else (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            channel++
            if (channel == channels) {
                channel = 0
                phase += carrierStep
                if (phase > 2 * PI) phase -= 2 * PI
                alienPhase += alienStep
                if (alienPhase > 2 * PI) alienPhase -= 2 * PI
                wobblePhase += wobbleStep
                if (wobblePhase > 2 * PI) wobblePhase -= 2 * PI
                delayPos = (delayPos + 1) % delayFrames
            }
        }
        out.flip()
    }

    /** A band of the voice, a high-pass then a low-pass, then driven into a soft clip. */
    private fun band(s: Float, channel: Int, hpA: Float, lpA: Float, drive: Float, level: Float): Float {
        val high = hpA * (hp[channel] + s - hpPrev[channel])
        hpPrev[channel] = s
        hp[channel] = high
        lp[channel] += lpA * (high - lp[channel])
        return tanh(lp[channel] * drive) * level
    }

    override fun onFlush() {
        delay.fill(0f)
        delayPos = 0
        hp.fill(0f)
        hpPrev.fill(0f)
        lp.fill(0f)
        cleaner?.reset()
    }

    private companion object {
        const val ROBOT_HZ = 55.0
        const val ECHO_SECONDS = 0.24
        const val ECHO_MIX = 0.55f
        const val ECHO_FEEDBACK = 0.42f
        const val CAVE_SECONDS = 0.5
        const val CAVE_MIX = 0.6f
        const val CAVE_FEEDBACK = 0.55f
        const val ALIEN_HZ = 180.0
        const val WOBBLE_HZ = 6.0
    }
}
