package com.squish.app.media.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Noise taken out from under the voice (EDGE_PLAN G2, INSHOT_PARITY P2): a
 * fan, traffic, an air conditioner, mains hum. Enhance (VoiceCleaner) gates
 * hiss *between* words; this works on every frame, words included.
 *
 * A short-time spectrum, half-overlapped with a square-root Hann window either
 * side, so with every gain at one the sound comes back as it went in. Per bin:
 *
 *  - **The floor**, by minimum statistics: the smoothed power's minimum over
 *    about a second and a quarter, scaled up by [BIAS] since a minimum sits
 *    below the mean of the noise it was taken from. It follows a noise that
 *    gets quieter at once and one that gets louder within that window.
 *  - **Steady is not noise above [HUM_MAX_HZ].** A held note, a test tone,
 *    a sustained vowel is as steady as hum to a minimum, and a minimum alone
 *    would take it out. A bin whose power hardly moves - its mean within
 *    [STEADY] of its minimum - is kept, unless it is low enough to be mains
 *    hum and its harmonics, which are steady and are noise.
 *  - **The gain**, Wiener's, with the "decision-directed" estimate of the
 *    voice's share (Ephraim and Malah): it is what stops the warble ("musical
 *    noise") of a gain worked out afresh on every frame. Smoothed across
 *    neighbouring bins, never below a floor - [strength] 1 is 20 dB down at
 *    most - so the voice is never gated into holes.
 *
 * The output is in step with the input, sample for sample: the frame's
 * latency is taken off the front and [drain] gives the tail at the end, so a
 * clip keeps its length and its sync. Pure: tools/jvm/NoiseReduceChecks.kt
 * executes it, and NoiseProcessor runs it in Media3's chain.
 */
class NoiseReducer(private val sampleRate: Int, private val channels: Int) {

    /** 0 leaves the sound as it is; 1 is the full reduction. */
    var strength = 1f

    private val size = when {
        sampleRate <= 24_000 -> 1_024
        sampleRate <= 48_000 -> 2_048
        else -> 4_096
    }
    private val hop = size / 2
    private val bins = size / 2 + 1
    private val window = FloatArray(size) { i -> sqrt(0.5 - 0.5 * cos(2 * PI * i / size)).toFloat() }
    private val latency = size - hop

    private val subFrames = maxOf(1, (SUB_WINDOW_S * sampleRate / hop).toInt())
    private val humBins = (HUM_MAX_HZ * size / sampleRate).toInt()

    private inner class Channel {
        val input = FloatArray(size)
        val accum = FloatArray(size)
        var filled = latency
        val smoothed = FloatArray(bins)
        val mean = FloatArray(bins)
        val subMins = Array(SUB_WINDOWS) { FloatArray(bins) { Float.MAX_VALUE } }
        val current = FloatArray(bins) { Float.MAX_VALUE }
        var subAt = 0
        var subCount = 0
        val gainPrev = FloatArray(bins) { 1f }
        val postPrev = FloatArray(bins) { 1f }
        var frames = 0
        val pending = ArrayList<Float>()
    }

    private var state = Array(channels) { Channel() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val gain = FloatArray(bins)
    private val smoothGain = FloatArray(bins)

    private var framesIn = 0L
    private var framesOut = 0L
    private var toDrop = latency

    /** Interleaved samples in, the same number out once [drain] has given the tail. */
    fun process(samples: FloatArray): FloatArray {
        val frames = samples.size / channels
        framesIn += frames
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val ch = state[c]
                ch.input[ch.filled++] = samples[f * channels + c]
            }
            if (state[0].filled == size) for (c in 0 until channels) frame(state[c])
        }
        return collect(Long.MAX_VALUE)
    }

    /** The last of the sound, held back by the frame: call once at the end of the stream. */
    fun drain(): FloatArray {
        var zeros = latency
        while (zeros > 0) {
            for (c in 0 until channels) { val ch = state[c]; ch.input[ch.filled++] = 0f }
            zeros--
            if (state[0].filled == size) for (c in 0 until channels) frame(state[c])
        }
        // Whatever is left in the frame, flushed too, then cut to the length that came in.
        if (state[0].filled > latency) {
            while (state[0].filled < size) for (c in 0 until channels) { val ch = state[c]; ch.input[ch.filled++] = 0f }
            for (c in 0 until channels) frame(state[c])
        }
        return collect(framesIn - framesOut)
    }

    /** Back to nothing heard: after a seek, the sound before it is not the noise after it. */
    fun reset() {
        state = Array(channels) { Channel() }
        framesIn = 0
        framesOut = 0
        toDrop = latency
    }

    private fun collect(limit: Long): FloatArray {
        var ready = state.minOf { it.pending.size }
        // The frame's latency, off the front: what comes out is in step with what went in.
        val drop = min(toDrop, ready)
        if (drop > 0) {
            state.forEach { it.pending.subList(0, drop).clear() }
            toDrop -= drop
            ready -= drop
        }
        val take = min(ready.toLong(), limit).toInt().coerceAtLeast(0)
        val out = FloatArray(take * channels)
        for (f in 0 until take) for (c in 0 until channels) out[f * channels + c] = state[c].pending[f]
        state.forEach { it.pending.subList(0, take).clear() }
        framesOut += take
        return out
    }

    private fun frame(ch: Channel) {
        for (i in 0 until size) { re[i] = ch.input[i] * window[i]; im[i] = 0f }
        Fft.transform(re, im)

        val s = strength.coerceIn(0f, 1f)
        val floor = 10f.pow(-FLOOR_DB_AT_FULL * s / 20f)
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            ch.smoothed[k] = if (ch.frames == 0) p else SMOOTH * ch.smoothed[k] + (1 - SMOOTH) * p
            ch.mean[k] = if (ch.frames == 0) ch.smoothed[k] else MEAN * ch.mean[k] + (1 - MEAN) * ch.smoothed[k]
            ch.current[k] = min(ch.current[k], ch.smoothed[k])
            var lowest = ch.current[k]
            for (w in ch.subMins) lowest = min(lowest, w[k])
            val steady = ch.mean[k] < STEADY * lowest && k > humBins
            val noise = max(if (steady) lowest * STEADY_SHARE else lowest * BIAS, EPSILON)
            val post = p / noise
            val prior = DD * ch.gainPrev[k] * ch.gainPrev[k] * ch.postPrev[k] + (1 - DD) * max(post - 1f, 0f)
            val g = max(prior / (1f + prior), floor)
            ch.gainPrev[k] = g
            ch.postPrev[k] = min(post, 1e6f)
            gain[k] = g
        }
        ch.frames++
        if (++ch.subCount >= subFrames) {
            ch.current.copyInto(ch.subMins[ch.subAt])
            ch.subAt = (ch.subAt + 1) % SUB_WINDOWS
            ch.current.fill(Float.MAX_VALUE)
            ch.subCount = 0
        }
        for (k in 0 until bins) {
            val a = gain[max(k - 1, 0)]
            val c = gain[min(k + 1, bins - 1)]
            // Only ever raised by its neighbours: a voice's harmonics are a bin or
            // two wide, and an average pulled each one down towards the noise
            // either side of it. Raised, a lone low bin among high ones - the
            // warble's other half - is filled in.
            smoothGain[k] = 1f - s * (1f - max(gain[k], 0.25f * a + 0.5f * gain[k] + 0.25f * c))
        }
        for (k in 0 until size) {
            val g = smoothGain[if (k < bins) k else size - k]
            re[k] *= g; im[k] *= g
        }
        // The inverse through the forward transform: conjugate, transform, conjugate, scale.
        for (k in 0 until size) im[k] = -im[k]
        Fft.transform(re, im)
        for (i in 0 until size) ch.accum[i] += re[i] / size * window[i]

        for (i in 0 until hop) ch.pending += ch.accum[i]
        System.arraycopy(ch.accum, hop, ch.accum, 0, size - hop)
        java.util.Arrays.fill(ch.accum, size - hop, size, 0f)
        System.arraycopy(ch.input, hop, ch.input, 0, size - hop)
        ch.filled = size - hop
    }

    private companion object {
        /** Each sub-window's length; [SUB_WINDOWS] of them make the span the floor is the minimum over. */
        const val SUB_WINDOW_S = 0.16
        const val SUB_WINDOWS = 8

        /** How far the noise's mean sits above its minimum, for the smoothing below. */
        const val BIAS = 2.5f
        const val SMOOTH = 0.7f
        const val MEAN = 0.96f

        /** A bin whose mean is within this of its minimum is steady. */
        const val STEADY = 1.6f

        /** What of a steady bin above the hum is taken for noise: next to none. */
        const val STEADY_SHARE = 0.02f

        /** Mains hum and its first harmonics sit below this. */
        const val HUM_MAX_HZ = 180.0

        /** The decision-directed weight on the last frame's estimate. */
        const val DD = 0.92f

        /** The furthest a bin is taken down at full strength. */
        const val FLOOR_DB_AT_FULL = 20f

        const val EPSILON = 1e-12f
    }
}
