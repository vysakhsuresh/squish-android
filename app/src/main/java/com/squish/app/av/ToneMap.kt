package com.squish.app.av

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * HDR (HLG or PQ, BT.2020) to SDR (BT.709) for the places that turn frames
 * into 8-bit pictures of their own - Reverse, the filmstrip, thumbnails -
 * where HDR's values written as they are look grey and flat (PQ) or dull and
 * desaturated (HLG). The player and the export do not use it: Media3 has its
 * own tone-mapper, and the player hands HDR to the display as HDR.
 *
 * All the arithmetic is here, as tables; squishav.c only looks them up
 * ([pixel] is that loop, written again so it can be executed on the JVM -
 * tools/jvm/ToneMapChecks.kt). Per pixel: the signal to linear light
 * (the HLG inverse OETF, or the PQ EOTF in nits), HLG's system gamma as a
 * gain on the scene luminance, BT.2020 primaries to BT.709, a curve that is
 * the identity up to [KNEE] of SDR white and rolls [PEAK_NITS] into white
 * above it - applied to the largest channel and the others scaled with it,
 * so a bright colour keeps its hue rather than going to white - and the
 * BT.709 (video) or sRGB (bitmap) OETF.
 */
object ToneMap {

    /** FFmpeg's AVCOL_TRC numbers. */
    const val TRC_PQ = 16
    const val TRC_HLG = 18

    const val SIZE = 4096
    const val SDR_WHITE_NITS = 203.0
    const val PEAK_NITS = 1000.0
    const val KNEE = 0.75

    /** The packed table's layout; squishav.c's TM_* are the same numbers. */
    const val IN = 0
    const val GAIN = SIZE
    const val RATIO = 2 * SIZE
    const val OUT_709 = 3 * SIZE
    const val OUT_SRGB = 4 * SIZE
    const val TOP = 5 * SIZE
    const val MATRIX = TOP + 1
    const val LUMA = MATRIX + 9
    const val LENGTH = LUMA + 3

    /** The peak, in units of SDR white. */
    val top: Double = PEAK_NITS / SDR_WHITE_NITS

    fun isHdr(trc: Int): Boolean = trc == TRC_PQ || trc == TRC_HLG

    /** BT.2100 HLG inverse OETF: the signal to scene light, 0..1. */
    fun hlgToScene(e: Double): Double {
        val a = 0.17883277
        val b = 1 - 4 * a
        val c = 0.5 - a * kotlin.math.ln(4 * a)
        val s = e.coerceIn(0.0, 1.0)
        return if (s <= 0.5) s * s / 3 else (exp((s - c) / a) + b) / 12
    }

    /** SMPTE ST 2084 EOTF: the signal to nits. */
    fun pqToNits(e: Double): Double {
        val m1 = 2610.0 / 16384
        val m2 = 2523.0 / 4096 * 128
        val c1 = 3424.0 / 4096
        val c2 = 2413.0 / 4096 * 32
        val c3 = 2392.0 / 4096 * 32
        val p = e.coerceIn(0.0, 1.0).pow(1 / m2)
        return 10000 * (max(p - c1, 0.0) / (c2 - c3 * p)).pow(1 / m1)
    }

    /**
     * SDR-relative linear light (1 is SDR white) into 0..1: the identity to
     * [KNEE], then the excess rolled off so that [top] lands on 1 - Reinhard
     * extended over the excess, which leaves the slope 1 at the knee.
     */
    fun curve(x: Double): Double {
        if (x <= KNEE) return max(x, 0.0)
        val span = 1 - KNEE
        val u = (x - KNEE) / span
        val um = (top - KNEE) / span
        return min(1.0, KNEE + span * u * (1 + u / (um * um)) / (1 + u))
    }

    fun bt709Oetf(l: Double): Double {
        val x = l.coerceIn(0.0, 1.0)
        return if (x < 0.018) 4.5 * x else 1.099 * x.pow(0.45) - 0.099
    }

    fun srgbOetf(l: Double): Double {
        val x = l.coerceIn(0.0, 1.0)
        return if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
    }

    /** Linear BT.2020 to linear BT.709 (ITU-R BT.2087), row by row. */
    val BT2020_TO_709 = floatArrayOf(
        1.6605f, -0.5876f, -0.0728f,
        -0.1246f, 1.1329f, -0.0083f,
        -0.0182f, -0.1006f, 1.1187f
    )

    /** BT.2020's luminance weights. */
    val BT2020_LUMA = floatArrayOf(0.2627f, 0.6780f, 0.0593f)

    /** The packed table for a stream of [trc], or null when it is not HDR. */
    fun tables(trc: Int): FloatArray? {
        if (!isHdr(trc)) return null
        val t = FloatArray(LENGTH)
        val hlg = trc == TRC_HLG
        val last = (SIZE - 1).toDouble()
        for (i in 0 until SIZE) {
            val e = i / last
            t[IN + i] = (if (hlg) hlgToScene(e) else pqToNits(e) / SDR_WHITE_NITS).toFloat()
            // Indexed by the square root of the scene luminance, which is
            // where the darks need the steps.
            val ys = e * e
            // HLG's OOTF at 1000 nits: display light = scene light x Ys^0.2,
            // then in units of SDR white. PQ is display light already.
            t[GAIN + i] = (if (hlg) ys.pow(0.2) * PEAK_NITS / SDR_WHITE_NITS else 1.0).toFloat()
            val m = ys * top
            t[RATIO + i] = (if (m <= 0.0) 1.0 else curve(m) / m).toFloat()
            t[OUT_709 + i] = (bt709Oetf(ys) * 255).toFloat()
            t[OUT_SRGB + i] = (srgbOetf(ys) * 255).toFloat()
        }
        t[TOP] = top.toFloat()
        BT2020_TO_709.copyInto(t, MATRIX)
        BT2020_LUMA.copyInto(t, LUMA)
        return t
    }

    /**
     * How Media3's HDR-to-SDR output reads back to display light: a power of
     * [MEDIA3_GAMMA] with the peak, [PEAK_NITS], at 1. Fitted to what the moto
     * gave for two bars of a known HLG signal (EDGE_PLAN B4).
     */
    const val MEDIA3_GAMMA = 2.4

    /**
     * Media3's tone-mapped pixel (0..1, as its shader chain carries it) lifted
     * to the white this file's own pictures use. Media3 maps an HDR picture's
     * peak to white, so HLG's reference white - 203 nits, where a person's
     * skin and a white shirt sit - came out at half brightness: every HDR clip
     * looked dim in the preview and in the file, and the filmstrip beside it,
     * tone-mapped here, did not. Read back to display light, put in units of
     * SDR white, rolled into white by [curve] on the largest channel (so a
     * colour keeps its hue) and encoded as [tables] encodes. The shader
     * squish_hdr_lift_es2.glsl is this, line for line.
     */
    fun liftMedia3(r: Double, g: Double, b: Double): DoubleArray {
        val m = max(r, max(g, b)).coerceIn(0.0, 1.0)
        if (m <= 0.0) return doubleArrayOf(0.0, 0.0, 0.0)
        val lm = m.pow(MEDIA3_GAMMA) * top
        val ratio = curve(lm) / lm
        return doubleArrayOf(r, g, b).map { c -> bt709Oetf(c.coerceIn(0.0, 1.0).pow(MEDIA3_GAMMA) * top * ratio) }.toDoubleArray()
    }

    private val made = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()

    /** [tables], made once for each transfer: every decoder of an HDR stream is handed the same array. */
    fun cached(trc: Int): FloatArray? = if (!isHdr(trc)) null else made.getOrPut(trc) { tables(trc)!! }

    private fun index(v: Float): Int = (sqrt(v) * (SIZE - 1) + 0.5f).toInt()

    /**
     * One pixel, exactly as squishav.c's tone_pixel does it: R'G'B' as
     * 16-bit values in, 8-bit R'G'B' out into [out] at [at].
     */
    fun pixel(t: FloatArray, r16: Int, g16: Int, b16: Int, srgb: Boolean, out: IntArray, at: Int = 0) {
        var r = t[IN + (r16 shr 4)]
        var g = t[IN + (g16 shr 4)]
        var b = t[IN + (b16 shr 4)]
        val ys = t[LUMA] * r + t[LUMA + 1] * g + t[LUMA + 2] * b
        val k = t[GAIN + index(ys.coerceIn(0f, 1f))]
        r *= k; g *= k; b *= k
        var rr = max(0f, t[MATRIX] * r + t[MATRIX + 1] * g + t[MATRIX + 2] * b)
        var gg = max(0f, t[MATRIX + 3] * r + t[MATRIX + 4] * g + t[MATRIX + 5] * b)
        var bb = max(0f, t[MATRIX + 6] * r + t[MATRIX + 7] * g + t[MATRIX + 8] * b)
        val m = max(rr, max(gg, bb))
        if (m > 0f) {
            val s = t[RATIO + index(min(1f, m / t[TOP]))]
            rr *= s; gg *= s; bb *= s
        }
        val o = if (srgb) OUT_SRGB else OUT_709
        out[at] = (t[o + index(min(1f, rr))] + 0.5f).toInt()
        out[at + 1] = (t[o + index(min(1f, gg))] + 0.5f).toInt()
        out[at + 2] = (t[o + index(min(1f, bb))] + 0.5f).toInt()
    }
}
