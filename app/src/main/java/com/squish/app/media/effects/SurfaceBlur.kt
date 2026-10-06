package com.squish.app.media.effects

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Smooth skin on the CPU: the surface blur the look shader does, for the one
 * surface the preview draws itself.
 *
 * A photo on an overlay row is a PNG drawn by Compose, not a player, so that it
 * can carry its alpha - which means its grade is applied pixel by pixel
 * (`Grade.applyTo`) rather than by the shader. Three of the shader's moves are
 * deliberately left out of that path, and `applyTo`'s own comment says which and
 * why: grain frozen on a still reads as dirt, and bloom and sharpening are a
 * texture the file has and the preview does without.
 *
 * **Smooth skin was the fourth, and it was left out by accident.** It arrived
 * with B12's thirteenth slider, after that comment was written, so nobody added
 * it and nobody wrote down a decision not to. The result was a slider offered on
 * a photo overlay that did nothing on screen and something in the exported
 * file - and of the four it is the one a person drags *expecting to watch the
 * subject change*, which makes it the one that reads as broken rather than as
 * subtle. Grain, bloom and sharpening stay out, by the decision already written
 * down; this one comes in.
 *
 * It can be exact rather than close, which is why it is worth doing at all: the
 * shader sharpens and smooths **before** the colour chain (on the untouched
 * picture), and `Grade.graded` is the colour chain alone. So the preview can run
 * this pass over the source pixels and then grade them, and the order is the
 * shader's order - no splitting of the colour chain, no "near enough".
 *
 * The numbers are [SkinTone]'s, which are the shader's, and
 * tools/jvm/SkinChecks.kt parses them back out of squish_look_es2.glsl so the
 * three cannot drift.
 */
object SurfaceBlur {

    /** Twelve taps on two rings, as the shader's loop runs. */
    const val TAPS = 12

    /** The first six are the outer ring; the rest come in to [INNER_SCALE]. */
    const val OUTER_TAPS = 6

    /** How far in the second ring sits, as a fraction of the radius. */
    const val INNER_SCALE = 0.55f

    /**
     * The angle between taps. The shader's own literal, which is 2*pi/12 to
     * seven places - kept as the literal rather than computed, because the
     * check that holds the two together compares the text.
     */
    const val STEP_RADIANS = 0.5235987f

    /**
     * The blur's reach in **texture** coordinates, exactly as the shader
     * computes it: `max(uTexel.x, uTexel.y) * (2 + 4 * amount)`.
     *
     * In texture coordinates, which is why it is worth a function of its own: a
     * circle in texture space is an *ellipse* in pixels on any picture that is
     * not square, and taking the radius in pixels instead would soften a
     * landscape photo more across than down while the file did the opposite.
     * The longer side gets the smaller texel, and the max picks the other one,
     * so the reach is set by the *shorter* side.
     */
    fun radiusUv(width: Int, height: Int, amount: Float): Float {
        if (width <= 0 || height <= 0) return 0f
        return maxOf(1f / width, 1f / height) * (SkinTone.MIN_RADIUS + SkinTone.RADIUS_REACH * amount)
    }

    /**
     * Smooth skin over a whole picture, in place.
     *
     * [pixels] is read and written, so the source is copied once first: a
     * neighbourhood read against an array being written would blur the already
     * blurred and smear a face across the picture, row by row. One copy of a
     * 960-pixel preview is under four megabytes, and it is made only when the
     * slider is off zero.
     *
     * The alpha of every pixel is kept untouched - a photo overlay's whole
     * reason for being drawn this way.
     */
    fun smoothSkin(pixels: IntArray, width: Int, height: Int, amount: Float) {
        if (width <= 0 || height <= 0 || amount <= 1e-3f) return
        if (pixels.size < width * height) return
        val radius = radiusUv(width, height, amount)
        if (radius <= 0f) return
        val src = pixels.copyOf()
        // The ring's offsets in pixels, worked out once rather than twelve
        // times per pixel: cos and sin of the same twelve angles, scaled by the
        // radius in texture space and then into pixels on each axis.
        val dx = FloatArray(TAPS)
        val dy = FloatArray(TAPS)
        for (i in 0 until TAPS) {
            val a = i * STEP_RADIANS
            val d = if (i < OUTER_TAPS) radius else radius * INNER_SCALE
            dx[i] = (cos(a) * d) * width
            dy[i] = (sin(a) * d) * height
        }
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = src[i]
                val cr = ((argb shr 16) and 0xFF) / 255f
                val cg = ((argb shr 8) and 0xFF) / 255f
                val cb = (argb and 0xFF) / 255f
                val skin = SkinTone.weight(cr, cg, cb)
                // Nothing to do where there is no skin, which is most of a
                // picture: the twelve taps are skipped rather than taken and
                // then mixed by nothing.
                if (skin <= 1e-4f) { i++; continue }
                var sumR = cr
                var sumG = cg
                var sumB = cb
                var weight = 1f
                for (t in 0 until TAPS) {
                    val sx = x + dx[t]
                    val sy = y + dy[t]
                    val sr: Float
                    val sg: Float
                    val sb: Float
                    // Bilinear, because `texture2D` is, and the point of this
                    // pass is to land on the same picture as the file rather
                    // than on a similar one.
                    //
                    // Said plainly, because the first version of this comment
                    // claimed more and was wrong: at these radii the nearest
                    // pixel would look much the same. The reach is two to six
                    // *pixels* whatever the picture's size - the texel scales
                    // with it - so twelve taps round a ring that size land on
                    // twelve distinct pixels either way, and the suite could not
                    // tell the two apart. This is fidelity, not visible
                    // softening.
                    val x0 = floor(sx).toInt()
                    val y0 = floor(sy).toInt()
                    val fx = sx - x0
                    val fy = sy - y0
                    val x1 = (x0 + 1).coerceIn(0, width - 1)
                    val y1 = (y0 + 1).coerceIn(0, height - 1)
                    val xa = x0.coerceIn(0, width - 1)
                    val ya = y0.coerceIn(0, height - 1)
                    val p00 = src[ya * width + xa]
                    val p10 = src[ya * width + x1]
                    val p01 = src[y1 * width + xa]
                    val p11 = src[y1 * width + x1]
                    sr = bilinear(p00, p10, p01, p11, 16, fx, fy)
                    sg = bilinear(p00, p10, p01, p11, 8, fx, fy)
                    sb = bilinear(p00, p10, p01, p11, 0, fx, fy)
                    // The weighting is the whole feature: a tap unlike the
                    // middle counts for less, so the blur takes the pores and
                    // leaves the eyes, the lashes and the edge of the face.
                    val diff = sqrt(
                        (sr - cr) * (sr - cr) + (sg - cg) * (sg - cg) + (sb - cb) * (sb - cb)
                    )
                    val w = 1f - SkinTone.smoothstep(0f, SkinTone.DETAIL_KEEP, diff)
                    sumR += sr * w
                    sumG += sg * w
                    sumB += sb * w
                    weight += w
                }
                val mix = amount * skin
                val outR = cr + (sumR / weight - cr) * mix
                val outG = cg + (sumG / weight - cg) * mix
                val outB = cb + (sumB / weight - cb) * mix
                pixels[i] = (argb and 0xFF000000.toInt()) or
                    (byte(outR) shl 16) or (byte(outG) shl 8) or byte(outB)
                i++
            }
        }
    }

    private fun bilinear(p00: Int, p10: Int, p01: Int, p11: Int, shift: Int, fx: Float, fy: Float): Float {
        val a = ((p00 shr shift) and 0xFF) / 255f
        val b = ((p10 shr shift) and 0xFF) / 255f
        val c = ((p01 shr shift) and 0xFF) / 255f
        val d = ((p11 shr shift) and 0xFF) / 255f
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    private fun byte(v: Float) = ((if (v < 0f) 0f else if (v > 1f) 1f else v) * 255f + 0.5f).toInt()
}
