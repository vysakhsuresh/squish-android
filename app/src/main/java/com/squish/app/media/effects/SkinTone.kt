package com.squish.app.media.effects

/*
 * Where the skin is in a picture, by its colour alone.
 *
 * Smooth skin is one slider and no model: a surface blur held to the pixels
 * that look like skin. Finding them by *chrominance* rather than by brightness
 * is what makes it work across skin tones - the hue and how colourful a face is
 * barely move from the palest to the darkest, while the brightness moves all the
 * way, which is exactly why every face-detector-free smoother is built this way.
 *
 * Nothing here touches Android: the numbers are the shader's own, parsed back
 * out of squish_look_es2.glsl by tools/jvm/SkinChecks.kt, so the two cannot
 * drift.
 *
 * That suite did not exist for the first weeks this line claimed it did, which
 * is worth one sentence of warning: a check named in a comment and never
 * written says a drift is impossible while nothing is watching. It exists now,
 * and it fails on any one of the eleven numbers parting.
 *
 * Two things read it: the look shader, for everything a player draws, and
 * [SurfaceBlur], for the one surface the preview draws itself - a photo on an
 * overlay row. The CPU path arrived late, after a slider had been offered on a
 * photo overlay for a while and done nothing there.
 */
object SkinTone {

    // The skin locus in YCbCr, with a soft edge each way so a cheek does not
    // end in a hard line. Measured on the standard ranges (Cb 77..127, Cr
    // 133..173 of 255) and widened a little, because a warm grade pushes a face
    // right out of the textbook box.
    const val CB_LOW = 0.26f
    const val CB_LOW_END = 0.33f
    const val CB_HIGH = 0.47f
    const val CB_HIGH_END = 0.54f
    const val CR_LOW = 0.49f
    const val CR_LOW_END = 0.54f
    const val CR_HIGH = 0.65f
    const val CR_HIGH_END = 0.70f

    // Black and blown-out pixels have no chrominance worth trusting.
    const val DARK = 0.08f
    const val DARK_END = 0.18f
    const val BRIGHT = 0.92f
    const val BRIGHT_END = 1.0f

    /** How different a neighbour may be before the blur stops counting it. */
    const val DETAIL_KEEP = 0.22f

    /** How far the blur reaches at full strength, in sharpening steps. */
    const val MIN_RADIUS = 2f
    const val RADIUS_REACH = 4f

    /**
     * Rec.601, deliberately - and the one place in the app that does not use
     * Rec.709.
     *
     * The Cb/Cr formulas below and the skin locus they bound are defined
     * against these weights; swapping in 709 moves the locus and the skin with
     * it. GradeChecks knows about this one exception and asserts the shader's
     * copy equals these three numbers.
     */
    const val LUMA_R = 0.299f
    const val LUMA_G = 0.587f
    const val LUMA_B = 0.114f

    fun luma(r: Float, g: Float, b: Float): Float = r * LUMA_R + g * LUMA_G + b * LUMA_B

    /**
     * How much of this pixel is skin, 0 to 1.
     *
     * A product rather than a sum: a pixel has to be inside the blue-difference
     * band *and* the red-difference band *and* lit, because a wall that is only
     * the right red is not skin.
     */
    fun weight(r: Float, g: Float, b: Float): Float {
        val y = luma(r, g, b)
        val cb = (b - y) * 0.564f + 0.5f
        val cr = (r - y) * 0.713f + 0.5f
        val inCb = smoothstep(CB_LOW, CB_LOW_END, cb) * (1f - smoothstep(CB_HIGH, CB_HIGH_END, cb))
        val inCr = smoothstep(CR_LOW, CR_LOW_END, cr) * (1f - smoothstep(CR_HIGH, CR_HIGH_END, cr))
        val lit = smoothstep(DARK, DARK_END, y) * (1f - smoothstep(BRIGHT, BRIGHT_END, y))
        return inCb * inCr * lit
    }

    /** GLSL's smoothstep, so the two sides read the same. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
