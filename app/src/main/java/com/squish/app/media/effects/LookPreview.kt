package com.squish.app.media.effects

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A look, applied to a still picture on the CPU.
 *
 * This exists so the effects panel can show every look on *your* frame rather
 * than on a colour swatch. A swatch built from the grade is an honest summary and
 * still tells you almost nothing: two looks that lift the blacks differently make
 * near-identical gradients, and neither shows what either does to a face. Every
 * editor worth the name previews the grade on the shot, because choosing a look
 * is a judgement about the picture, not about the numbers.
 *
 * The pipeline is the one in `squish_look_es2.glsl`, in the same order, with the
 * same constants. Order matters here and is not obvious: bloom is taken from the
 * ungraded picture, contrast uses Media3's own curve so the shader path and the
 * built-in path land on the same image, and fade compresses toward a raised floor
 * rather than adding an offset. Getting any of that wrong would make the
 * thumbnail a confident lie, which is worse than the swatch it replaces.
 *
 * Grain is deliberately left out. It is noise that moves between frames; frozen
 * onto a still thumbnail it reads as a dirty lens rather than as film.
 */
object LookPreview {

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    /**
     * Grades [pixels] in place. ARGB 8888, [width] by [height], row-major.
     *
     * In place because this runs once per look per frame grab, on thumbnails a
     * hundred pixels across; allocating a fresh array per look would be a dozen
     * short-lived arrays every time the playhead moves.
     */
    fun apply(pixels: IntArray, width: Int, height: Int, look: Look) {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return
        if (look.isIdentity) return

        val gainR = look.redScale
        val gainG = look.greenScale
        val gainB = look.blueScale
        // Media3's contrast curve, matching the shader exactly.
        val contrastF = (1f + look.contrast) / (1.0001f - look.contrast)
        val satAmount = maxOf(0f, 1f + look.saturation)

        val shadow = look.shadowTint
        val highlight = look.highlightTint
        val shadowR = ((shadow shr 16) and 0xFF) / 255f
        val shadowG = ((shadow shr 8) and 0xFF) / 255f
        val shadowB = (shadow and 0xFF) / 255f
        val highR = ((highlight shr 16) and 0xFF) / 255f
        val highG = ((highlight shr 8) and 0xFF) / 255f
        val highB = (highlight and 0xFF) / 255f

        // The blurred copy bloom is taken from, made before anything is graded -
        // the shader samples the source texture, not its own output.
        val soft: IntArray? = if (look.bloom > 0.001f) blurred(pixels, width, height) else null

        val aspect = width.toFloat() / height
        val halfDiagonal = sqrt((aspect * 0.5f) * (aspect * 0.5f) + 0.25f)

        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = pixels[i]
                var r = ((argb shr 16) and 0xFF) / 255f
                var g = ((argb shr 8) and 0xFF) / 255f
                var b = (argb and 0xFF) / 255f

                if (soft != null) {
                    val s = soft[i]
                    val sr = ((s shr 16) and 0xFF) / 255f
                    val sg = ((s shr 8) and 0xFF) / 255f
                    val sb = (s and 0xFF) / 255f
                    r += maxOf(sr - 0.62f, 0f) * 2.6f * look.bloom
                    g += maxOf(sg - 0.62f, 0f) * 2.6f * look.bloom
                    b += maxOf(sb - 0.62f, 0f) * 2.6f * look.bloom
                }

                r *= gainR
                g *= gainG
                b *= gainB

                r = contrastF * (r - 0.5f) + 0.5f
                g = contrastF * (g - 0.5f) + 0.5f
                b = contrastF * (b - 0.5f) + 0.5f

                val lum = r * LUMA_R + g * LUMA_G + b * LUMA_B
                r = lum + (r - lum) * satAmount
                g = lum + (g - lum) * satAmount
                b = lum + (b - lum) * satAmount

                if (look.split > 0.001f) {
                    val l = (r * LUMA_R + g * LUMA_G + b * LUMA_B).coerceIn(0f, 1f)
                    val strength = look.split * 0.55f
                    r += (shadowR + (highR - shadowR) * l - 0.5f) * strength
                    g += (shadowG + (highG - shadowG) * l - 0.5f) * strength
                    b += (shadowB + (highB - shadowB) * l - 0.5f) * strength
                }

                val floor = look.fade * 0.16f
                val squeeze = 1f - look.fade * 0.55f
                r = r * squeeze + floor
                g = g * squeeze + floor
                b = b * squeeze + floor

                if (look.vignette > 0.001f) {
                    val px = (x + 0.5f) / width - 0.5f
                    val py = (y + 0.5f) / height - 0.5f
                    val d = sqrt((px * aspect) * (px * aspect) + py * py) / halfDiagonal
                    val falloff = 1f - look.vignette * smoothstep(0.42f, 1.06f, d)
                    r *= falloff
                    g *= falloff
                    b *= falloff
                }

                pixels[i] = (argb and 0xFF000000.toInt()) or
                    (to8Bit(r) shl 16) or (to8Bit(g) shl 8) or to8Bit(b)
                i++
            }
        }
    }

    /** The shader's smoothstep, which is not Kotlin's and has no standard version. */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun to8Bit(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

    /**
     * A cheap blur, standing in for the shader's ring of eight taps.
     *
     * A 3x3 box rather than a ring, because at thumbnail size the ring's radius of
     * 0.014 of the picture is under a pixel and the two are indistinguishable.
     * What matters is that the highlights are softened before the threshold, so
     * the glow spreads instead of hard-edging.
     */
    private fun blurred(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= width) continue
                        val p = pixels[yy * width + xx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                out[y * width + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    /** True when two graded pictures would be told apart at thumbnail size. */
    fun differs(a: IntArray, b: IntArray, threshold: Int = 3): Boolean {
        if (a.size != b.size) return true
        var worst = 0
        for (i in a.indices) {
            worst = maxOf(worst, abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF)))
            worst = maxOf(worst, abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF)))
            worst = maxOf(worst, abs((a[i] and 0xFF) - (b[i] and 0xFF)))
            if (worst >= threshold) return true
        }
        return false
    }
}
