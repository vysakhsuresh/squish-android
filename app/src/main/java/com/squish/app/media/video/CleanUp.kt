package com.squish.app.media.video

import kotlin.math.abs
import kotlin.math.exp

/**
 * Picture clean-up over time (EDGE_PLAN F9): Remove flicker and Reduce noise,
 * the arithmetic of CleanUpEffect's pass, here so tools/jvm/CleanUpChecks.kt
 * runs it on sequences whose truth is known.
 *
 * Flicker - mains-lit rooms, timelapses - is the frame's whole brightness
 * jumping from one frame to the next. [Deflicker] follows the brightness
 * slowly (about a third of a second) and lifts or lowers each frame towards
 * that, so a jump goes and a fade or a cut, which last, stay: a jump larger
 * than [Deflicker.CUT] is a new shot, followed at once.
 *
 * Noise is each pixel jittering about its true value. [denoiseWeight] is how
 * much of the last output a pixel takes - most of it where the two agree
 * (still picture), none where they differ by more than noise does (something
 * moved), so a still room goes quiet and a hand waving does not leave ghosts.
 */
object CleanUp {

    /** Under this the two frames agree as noise does; over [MOVED] something moved. */
    const val QUIET = 0.025f
    const val MOVED = 0.09f

    /** The most of the last output a still pixel takes, at full strength. */
    const val MAX_KEEP = 0.82f

    fun denoiseWeight(diff: Float, strength: Float): Float {
        val t = ((diff - QUIET) / (MOVED - QUIET)).coerceIn(0f, 1f)
        val still = 1f - t * t * (3f - 2f * t)
        return MAX_KEEP * strength.coerceIn(0f, 1f) * still
    }

    /**
     * A whole frame's next output, as squish_cleanup_es2.glsl draws it: each
     * pixel mixed towards the last output by how still its 3 x 3 neighbourhood
     * is - the difference of the two frames' means round it, not of the pixel
     * alone. Judged pixel by pixel, grain is its own difference: sd-7.5 grain
     * (low-light phone footage) read as half moving and kept only a third off
     * (27 Oct, on exported files from the moto), the heavier the grain the
     * less done. Over nine pixels grain averages to a third while a moving
     * edge does not, so the room goes quiet and a hand still leaves no ghost.
     */
    fun denoiseFrame(cur: FloatArray, last: FloatArray, w: Int, h: Int, strength: Float): FloatArray {
        if (strength <= 0f) return cur.copyOf()
        val out = FloatArray(w * h * 3)
        val sc = FloatArray(3); val sl = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            sc.fill(0f); sl.fill(0f)
            for (dy in -1..1) for (dx in -1..1) {
                val j = ((y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)) * 3
                for (c in 0 until 3) { sc[c] += cur[j + c]; sl[c] += last[j + c] }
            }
            var diff = 0f
            // Each channel weighed apart: by luma alone, red moving over a teal
            // as bright read as still and left a trail (seen on the moto).
            for (c in 0 until 3) diff += LUMA[c] * abs(sc[c] - sl[c])
            val k = denoiseWeight(diff / 9f, strength)
            val i = (y * w + x) * 3
            for (c in 0 until 3) out[i + c] = cur[i + c] + (last[i + c] - cur[i + c]) * k
        }
        return out
    }

    /** Rec. 709's weights, the shader's LUMA. */
    private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    /** One pixel's next output from its new value and the last output. */
    fun denoise(current: Float, last: Float, strength: Float): Float {
        val k = denoiseWeight(abs(current - last), strength)
        return current + (last - current) * k
    }

    /**
     * The brightness followed through a clip, frame by frame: a straight line
     * fitted to the last [WINDOW] frames' brightness, read at this frame. A
     * fade is a straight line and is followed without lag; a jump from one
     * frame to the next lies off any line through its neighbours and is
     * evened out; at the end of a fade the line bends round within a few
     * frames rather than carrying on past it.
     */
    class Deflicker(private val strength: Float) {
        private val times = ArrayDeque<Double>()
        private val means = ArrayDeque<Double>()
        private var lastUs = Long.MIN_VALUE
        private var reference = Float.NaN

        /**
         * The gain for a frame whose mean brightness is [mean] (0..1), at
         * [presentationUs]. A jump back in time, or a gap, starts afresh.
         */
        fun gain(mean: Float, presentationUs: Long): Float {
            val dtUs = presentationUs - lastUs
            if (lastUs == Long.MIN_VALUE || dtUs <= 0L || dtUs > 500_000L) { times.clear(); means.clear() }
            // A change this big is a cut or a light switched on: follow it, do not fight it.
            if (!reference.isNaN() && reference > 1e-4f && (mean / reference > 1f + CUT || mean / reference < 1f - CUT)) {
                times.clear(); means.clear()
            }
            lastUs = presentationUs
            times.addLast(presentationUs / 1_000_000.0); means.addLast(mean.toDouble())
            while (times.size > WINDOW) { times.removeFirst(); means.removeFirst() }
            // Neighbouring frames averaged first, so a flicker every other frame cancels before the fit.
            val pt = ArrayDeque<Double>(); val pm = ArrayDeque<Double>()
            for (i in 1 until times.size) { pt.addLast((times[i] + times[i - 1]) / 2); pm.addLast((means[i] + means[i - 1]) / 2) }
            val level = (if (pt.size >= 2) fitAt(pt, pm, presentationUs / 1_000_000.0) else means.last()).toFloat()
            reference = level
            if (mean < 1e-3f || times.size < 3) return 1f
            val full = (level / mean).coerceIn(MIN_GAIN, MAX_GAIN)
            return 1f + (full - 1f) * strength.coerceIn(0f, 1f)
        }

        private fun fitAt(t: ArrayDeque<Double>, m: ArrayDeque<Double>, at: Double): Double {
            val n = t.size
            val tm = t.average(); val mm = m.average()
            var sxy = 0.0; var sxx = 0.0
            for (i in 0 until n) { sxy += (t[i] - tm) * (m[i] - mm); sxx += (t[i] - tm) * (t[i] - tm) }
            val slope = if (sxx > 1e-12) sxy / sxx else 0.0
            return mm + slope * (at - tm)
        }

        companion object {
            /** Frames the line is fitted over: long enough to straddle a flicker, short enough to round a knee. */
            const val WINDOW = 7
            const val CUT = 0.45f
            const val MIN_GAIN = 0.6f
            const val MAX_GAIN = 1.6f
        }
    }
}
