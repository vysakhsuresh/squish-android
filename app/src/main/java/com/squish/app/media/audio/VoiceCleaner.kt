package com.squish.app.media.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * "Enhance voice": what a phone recording of someone talking needs, sample by
 * sample, with no look-ahead so the preview can run it live.
 *
 * - Rumble goes: a high-pass at 80 Hz takes out handling noise, wind thump and
 *   the hum of a room, which carry no speech.
 * - Hiss between words goes down: the level is followed per channel and the
 *   quietest level heard lately is taken as the noise floor; while the voice is
 *   not clearly above it the sound is turned down by [FLOOR_GAIN] (a downward
 *   expander, eased in and out so words are not clipped at their edges).
 * - The voice is brought forward: a gentle lift of the whole, soft-limited so a
 *   shout does not clip.
 *
 * Kept free of Android so it is executed on the JVM (tools/jvm/VoiceCleanerChecks.kt).
 */
class VoiceCleaner(private val sampleRate: Int, private val channels: Int) {

    private val hpA = (1.0 / (1.0 + 2.0 * PI * HIGH_PASS_HZ / sampleRate)).toFloat()
    private val attack = coefficient(ATTACK_MS)
    private val release = coefficient(RELEASE_MS)
    private val gainAttack = coefficient(GAIN_OPEN_MS)
    private val gainRelease = coefficient(GAIN_CLOSE_MS)
    /** How fast the floor may rise, per sample: a room getting louder is followed in a few seconds. */
    private val floorRise = (1.0 + FLOOR_RISE_PER_SECOND / sampleRate).toFloat()

    private val hp = FloatArray(channels)
    private val hpPrev = FloatArray(channels)
    private val env = FloatArray(channels)
    private val floor = FloatArray(channels) { INITIAL_FLOOR }
    private val gain = FloatArray(channels) { 1f }

    /** One sample of [channel], -1..1 in and out. */
    fun process(sample: Float, channel: Int): Float {
        // Rumble out.
        val high = hpA * (hp[channel] + sample - hpPrev[channel])
        hpPrev[channel] = sample
        hp[channel] = high

        // The level, fast up and slow down.
        val level = abs(high)
        val e = env[channel]
        env[channel] = if (level > e) e + attack * (level - e) else e + release * (level - e)

        // The floor: the lowest level lately, allowed to creep up so it follows the room.
        floor[channel] = max(MIN_FLOOR, min(floor[channel] * floorRise, env[channel]))

        // Open while the voice stands clear of the floor, closed to FLOOR_GAIN otherwise.
        val target = if (env[channel] > floor[channel] * OPEN_RATIO) 1f else FLOOR_GAIN
        val g = gain[channel]
        gain[channel] = if (target > g) g + gainAttack * (target - g) else g + gainRelease * (target - g)

        return softLimit(high * gain[channel] * PRESENCE)
    }

    fun reset() {
        hp.fill(0f); hpPrev.fill(0f); env.fill(0f); floor.fill(INITIAL_FLOOR); gain.fill(1f)
    }

    private fun coefficient(ms: Double): Float = (1.0 - exp(-1.0 / (sampleRate * ms / 1000.0))).toFloat()

    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        // Above the knee the curve bends smoothly towards 1, never past it.
        val over = (a - KNEE) / (1f - KNEE)
        val bent = KNEE + (1f - KNEE) * (over / (1f + over))
        return if (x < 0) -bent else bent
    }

    companion object {
        const val HIGH_PASS_HZ = 80.0
        const val ATTACK_MS = 10.0
        const val RELEASE_MS = 120.0
        const val GAIN_OPEN_MS = 4.0
        const val GAIN_CLOSE_MS = 180.0
        /** How far above the floor the voice must stand to count as speech: about 10 dB. */
        const val OPEN_RATIO = 3.2f
        /** What is left of the sound between words: about -16 dB. */
        const val FLOOR_GAIN = 0.16f
        const val PRESENCE = 1.4f
        const val KNEE = 0.8f
        const val INITIAL_FLOOR = 0.01f
        const val MIN_FLOOR = 0.0005f
        const val FLOOR_RISE_PER_SECOND = 0.5
    }
}
