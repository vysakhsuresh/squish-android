package com.squish.app.media.audio

import kotlin.math.sqrt

/**
 * Even out volume: how loud each shot sounds, and the level that brings the
 * loud ones down to the rest.
 *
 * Loudness is taken the way a broadcast meter gates it, roughly: the level of
 * each 50 ms frame, the frames under [GATE] (silence) left out, and the RMS of
 * the rest - so a shot with long pauses is not read as quiet because of them.
 * Levels only come down (a shot's level tops out at 100%): each shot is taken
 * to the level of the quieter ones, never up past its own. Kept free of Android
 * so it is executed on the JVM (tools/jvm/LoudnessChecks.kt).
 */
object Loudness {

    const val FRAME_MS = 50
    /** About -50 dBFS: below this a frame is silence and does not count. */
    const val GATE = 0.003f
    /** Never turned down past this: a shot is levelled, not muted. */
    const val MIN_LEVEL = 0.2f

    /** The gated RMS of [samples] at [sampleRate], or 0 when nothing in it is above the gate. */
    fun of(samples: FloatArray, sampleRate: Int): Float {
        val frame = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        var sum = 0.0
        var counted = 0L
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + frame, samples.size)
            var s = 0.0
            for (j in i until end) s += samples[j] * samples[j]
            val rms = sqrt(s / (end - i))
            if (rms >= GATE) {
                sum += s
                counted += (end - i)
            }
            i = end
        }
        return if (counted == 0L) 0f else sqrt(sum / counted).toFloat()
    }

    /**
     * The level for each shot, by its [loudness] (0 = silent, left as it is):
     * all brought to the quietest third's loudness, and only ever down.
     */
    fun levels(loudness: List<Float>): List<Float> {
        val heard = loudness.filter { it > 0f }.sorted()
        if (heard.size < 2) return loudness.map { 1f }
        val target = heard[(heard.size - 1) / 3]
        return loudness.map { l -> if (l <= 0f) 1f else (target / l).coerceIn(MIN_LEVEL, 1f) }
    }
}
