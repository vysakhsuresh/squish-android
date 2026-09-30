package com.squish.app.media.effects

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto adjust: exposure, white balance, contrast and colour set for a shot
 * from its own pixels, in one tap - CapCut's "Auto adjust".
 *
 * Closed-loop rather than guessed: each value is searched for by running the
 * same grade the preview's shader and the export draw with (Grade.applyTo)
 * over a small copy of the frame, until the result lands on its target - a
 * mid-grey average, neutral whites, a full tonal range, a natural amount of
 * colour. So what the sliders then show is what that frame needs, measured.
 * The sliders Auto does not set (sharpen, vignette, the wheel and the rest)
 * are kept. Kept free of Android so it is executed on the JVM
 * (tools/jvm/AutoAdjustChecks.kt).
 */
object AutoAdjust {

    const val TARGET_LUMA = 0.46f
    const val TARGET_SPREAD = 0.78f
    const val TARGET_CHROMA = 0.16f

    /** The Adjust for [pixels] (ARGB, any size - a few thousand are plenty), starting from [from]. */
    fun of(pixels: IntArray, from: Adjust = Adjust.NONE): Adjust {
        if (pixels.isEmpty()) return from
        var a = from.copy(exposure = 0f, temperature = 0f, tint = 0f, contrast = 0f, saturation = 0f, brightness = 0f)

        // White balance first: neutral midtones, red against blue, then green against both.
        a = a.copy(temperature = solve(-0.6f, 0.6f) { t -> stats(pixels, a.copy(temperature = t)).let { it.b - it.r } })
        a = a.copy(tint = solve(-0.5f, 0.5f) { t -> stats(pixels, a.copy(tint = t)).let { (it.r + it.b) / 2f - it.g } })
        // Exposure to a mid-grey average.
        a = a.copy(exposure = solve(-0.9f, 0.9f) { e -> TARGET_LUMA - stats(pixels, a.copy(exposure = e)).luma })
        // Contrast for a full range, gently: a flat, grey picture gains; a harsh one gives a little.
        val spread = stats(pixels, a).spread
        a = a.copy(contrast = ((TARGET_SPREAD - spread) * 0.8f).coerceIn(-0.2f, 0.4f))
        // Colour to a natural amount, never to neon.
        a = a.copy(saturation = solve(-0.4f, 0.4f) { s -> TARGET_CHROMA - stats(pixels, a.copy(saturation = s)).chroma })
        // Contrast moved the average: bring it back.
        a = a.copy(exposure = solve(-0.9f, 0.9f) { e -> TARGET_LUMA - stats(pixels, a.copy(exposure = e)).luma })
        return a
    }

    class Stats(val r: Float, val g: Float, val b: Float, val luma: Float, val spread: Float, val chroma: Float)

    /** Averages of [pixels] once graded by [adjust]: midtone colour, mean luma, the 5-95% range, mean chroma. */
    fun stats(pixels: IntArray, adjust: Adjust): Stats {
        val grade = Looks.grade(null, 1f, adjust)
        val lumas = FloatArray(pixels.size)
        var r = 0.0; var g = 0.0; var b = 0.0; var mid = 0
        var chroma = 0.0; var sum = 0.0
        for (i in pixels.indices) {
            val c = grade.applyTo(pixels[i])
            val pr = ((c shr 16) and 0xFF) / 255f
            val pg = ((c shr 8) and 0xFF) / 255f
            val pb = (c and 0xFF) / 255f
            val l = 0.2126f * pr + 0.7152f * pg + 0.0722f * pb
            lumas[i] = l
            sum += l
            chroma += max(pr, max(pg, pb)) - min(pr, min(pg, pb))
            if (l in 0.12f..0.88f) { r += pr; g += pg; b += pb; mid++ }
        }
        lumas.sort()
        val n = pixels.size
        val m = mid.coerceAtLeast(1)
        return Stats(
            (r / m).toFloat(), (g / m).toFloat(), (b / m).toFloat(),
            (sum / n).toFloat(),
            lumas[(n * 95 / 100).coerceAtMost(n - 1)] - lumas[n * 5 / 100],
            (chroma / n).toFloat()
        )
    }

    /**
     * The value in [lo]..[hi] where [f] crosses zero, by bisection; when it
     * does not cross, the end nearer zero - the slider's limit, not a wild
     * value outside it.
     */
    private fun solve(lo: Float, hi: Float, f: (Float) -> Float): Float {
        var a = lo
        var b = hi
        var fa = f(a)
        val fb = f(b)
        if (fa == 0f) return a
        if (fb == 0f) return b
        if ((fa > 0f) == (fb > 0f)) return if (abs(fa) < abs(fb)) a else b
        repeat(14) {
            val m = (a + b) / 2f
            val fm = f(m)
            if ((fm > 0f) == (fa > 0f)) { a = m; fa = fm } else b = m
        }
        return (a + b) / 2f
    }
}
