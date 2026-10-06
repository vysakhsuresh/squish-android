package com.squish.app.media.effects

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The look shader's two neighbourhood moves - Sharpen and Smooth skin - on the
 * CPU, for the one surface the preview draws itself.
 *
 * A photo on an overlay row is a PNG drawn by Compose, not a player, so that it
 * can carry its alpha - which means its grade is applied pixel by pixel
 * (`Grade.applyTo`) rather than by the shader. The shader's spatial moves were
 * all left out of that path, and `applyTo`'s comment says which and why: grain
 * frozen on a still reads as dirt, and bloom is a texture the file has and the
 * preview does without.
 *
 * **Sharpen and Smooth skin were in that list by accident, not by decision.**
 * They are two of the thirteen Adjust sliders, the panel offers all thirteen on
 * every clip, and on a photo overlay both did nothing on screen and something
 * in the exported file. A control that lies is worse than a control that is
 * missing, and these two are the ones a person drags while *watching the
 * subject* - unlike grain and bloom, which are atmosphere.
 *
 * They can be exact rather than close, which is why they are worth doing at
 * all: the shader does both **before** the colour chain, on the untouched
 * picture, and `Grade.graded` is the colour chain alone. So the preview runs
 * this pass over the source pixels and then grades them, in the shader's own
 * order - no splitting of the colour chain and no "near enough".
 *
 * Three details of that order are the whole reason this can claim to match, and
 * each was read off the shader rather than guessed:
 *
 *  - Sharpen comes first, against the four neighbours one pixel away. After the
 *    grade it would sharpen the grain and the vignette's edge with the picture.
 *  - The smooth's **taps come from the untouched picture** while its centre, its
 *    weighting reference and its skin weight are all the *sharpened* colour -
 *    `surfaceBlur(uv, radius, c)` samples `uTexSampler` and is handed `c`. Turn
 *    both sliders up and the face softens while everything else stays crisp,
 *    which is what somebody who turned both up meant.
 *  - The blur's radius is in **texture** coordinates, so a circle there is the
 *    same ellipse in pixels that the file draws. Taking it in pixels instead
 *    would soften a landscape photo more across than down while the file did
 *    the opposite.
 *
 * The numbers are [SkinTone]'s, which are the shader's, and
 * tools/jvm/SkinChecks.kt parses them back out of squish_look_es2.glsl so the
 * three cannot drift.
 */
object SpatialMoves {

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

    /** How hard the unsharp mask pulls: the shader's `uSharpen * 1.5`. */
    const val SHARPEN_REACH = 1.5f

    /** The four neighbours, averaged: the shader's `around * 0.25`. */
    const val AROUND_WEIGHT = 0.25f

    /**
     * The blur's reach in **texture** coordinates, exactly as the shader
     * computes it: `max(uTexel.x, uTexel.y) * (2 + 4 * amount)`.
     *
     * The longer side gets the smaller texel, so the max picks the other one
     * and the reach is set by the *shorter* side - see this file's note on why
     * texture coordinates rather than pixels.
     */
    fun radiusUv(width: Int, height: Int, amount: Float): Float {
        if (width <= 0 || height <= 0) return 0f
        return maxOf(1f / width, 1f / height) * (SkinTone.MIN_RADIUS + SkinTone.RADIUS_REACH * amount)
    }

    /**
     * Sharpen and Smooth skin over a whole picture, in place.
     *
     * [pixels] is read and written, so the source is copied once first: a
     * neighbourhood read against an array being written works partly on its own
     * output. One copy of a 960-pixel preview is under four megabytes, and it
     * is made only when one of the two sliders is off zero.
     *
     * What that costs, measured rather than assumed, because the first version
     * of this comment said "smears a face down the picture, row by row" and
     * that is not what happens: with the copy taken out there is no gradient
     * down the picture at all, only a uniformly softer one - about 17% softer
     * than the file's on a noisy face. The detail weighting is why: a neighbour
     * already pulled towards the middle is *closer* to it, so it weighs more
     * and the thing converges in one step instead of compounding. Small,
     * shapeless, and wrong - which is why tools/jvm/SkinChecks.kt checks for
     * the line of code and says there why it cannot check for the effect.
     *
     * The alpha of every pixel is kept untouched - a photo overlay's whole
     * reason for being drawn this way.
     */
    fun spatial(pixels: IntArray, width: Int, height: Int, sharpen: Float, smooth: Float) {
        val sharpening = sharpen > 1e-3f
        val smoothing = smooth > 1e-3f
        if (width <= 0 || height <= 0 || (!sharpening && !smoothing)) return
        if (pixels.size < width * height) return
        val radius = radiusUv(width, height, smooth)
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
        val colour = FloatArray(3)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = src[i]
                var cr = ((argb shr 16) and 0xFF) / 255f
                var cg = ((argb shr 8) and 0xFF) / 255f
                var cb = (argb and 0xFF) / 255f

                if (sharpening) {
                    // uTexel is one pixel, so the four neighbours land exactly
                    // on pixel centres and there is nothing to interpolate.
                    var ar = 0f
                    var ag = 0f
                    var ab = 0f
                    fun add(px: Int, py: Int) {
                        val p = src[py.coerceIn(0, height - 1) * width + px.coerceIn(0, width - 1)]
                        ar += ((p shr 16) and 0xFF) / 255f
                        ag += ((p shr 8) and 0xFF) / 255f
                        ab += (p and 0xFF) / 255f
                    }
                    add(x + 1, y); add(x - 1, y); add(x, y + 1); add(x, y - 1)
                    val pull = sharpen * SHARPEN_REACH
                    cr += (cr - ar * AROUND_WEIGHT) * pull
                    cg += (cg - ag * AROUND_WEIGHT) * pull
                    cb += (cb - ab * AROUND_WEIGHT) * pull
                }

                if (smoothing && radius > 0f) {
                    // The skin weight and the weighting reference are the
                    // *sharpened* colour, as the shader hands `c` to
                    // surfaceBlur; the taps are the untouched picture, which is
                    // what surfaceBlur samples.
                    val skin = SkinTone.weight(cr, cg, cb)
                    if (skin > 1e-4f) {
                        var sumR = cr
                        var sumG = cg
                        var sumB = cb
                        var weight = 1f
                        for (t in 0 until TAPS) {
                            sample(src, width, height, x + dx[t], y + dy[t], colour)
                            val sr = colour[0]
                            val sg = colour[1]
                            val sb = colour[2]
                            // The weighting is the whole feature: a tap unlike
                            // the middle counts for less, so the blur takes the
                            // pores and leaves the eyes, the lashes and the
                            // edge of the face.
                            val diff = sqrt(
                                (sr - cr) * (sr - cr) + (sg - cg) * (sg - cg) + (sb - cb) * (sb - cb)
                            )
                            val w = 1f - SkinTone.smoothstep(0f, SkinTone.DETAIL_KEEP, diff)
                            sumR += sr * w
                            sumG += sg * w
                            sumB += sb * w
                            weight += w
                        }
                        val mix = smooth * skin
                        cr += (sumR / weight - cr) * mix
                        cg += (sumG / weight - cg) * mix
                        cb += (sumB / weight - cb) * mix
                    }
                }

                pixels[i] = (argb and 0xFF000000.toInt()) or
                    (byte(cr) shl 16) or (byte(cg) shl 8) or byte(cb)
                i++
            }
        }
    }

    /**
     * One bilinear tap, because `texture2D` is bilinear and the point of this
     * pass is to land on the same picture as the file rather than a similar one.
     *
     * Said plainly, because the first version of this comment claimed more and
     * was wrong: at these radii the nearest pixel would look much the same. The
     * reach is two to six *pixels* whatever the picture's size - the texel
     * scales with it - so twelve taps round a ring that size land on twelve
     * distinct pixels either way, and no fixture could tell the two apart. This
     * is fidelity, not visible softening.
     */
    private fun sample(src: IntArray, width: Int, height: Int, sx: Float, sy: Float, out: FloatArray) {
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val fx = sx - x0
        val fy = sy - y0
        val xa = x0.coerceIn(0, width - 1)
        val ya = y0.coerceIn(0, height - 1)
        val xb = (x0 + 1).coerceIn(0, width - 1)
        val yb = (y0 + 1).coerceIn(0, height - 1)
        val p00 = src[ya * width + xa]
        val p10 = src[ya * width + xb]
        val p01 = src[yb * width + xa]
        val p11 = src[yb * width + xb]
        out[0] = bilinear(p00, p10, p01, p11, 16, fx, fy)
        out[1] = bilinear(p00, p10, p01, p11, 8, fx, fy)
        out[2] = bilinear(p00, p10, p01, p11, 0, fx, fy)
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
