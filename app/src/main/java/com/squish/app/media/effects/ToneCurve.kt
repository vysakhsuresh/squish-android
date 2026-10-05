package com.squish.app.media.effects

import kotlin.math.abs
import kotlin.math.sqrt

/** A point on a tone curve: a level going in, and what it comes out as. Both 0..1. */
data class CurvePoint(val x: Float, val y: Float)

/**
 * One channel's tone curve.
 *
 * Interpolated monotonically (Fritsch-Carlson), not by a plain spline: a
 * Catmull-Rom through four points a user actually places overshoots, and an
 * overshoot in a tone curve is not a wobble - it is a band of pixels that get
 * *darker* as the footage gets brighter, which reads as a hard edge in a sky.
 * A monotone fit cannot do that: between two points it never leaves the range
 * they set.
 *
 * The ends are always there. A curve editor moves (0,0) and (1,1) but cannot
 * delete them, so there is no question of what happens outside the points.
 */
data class Curve(val points: List<CurvePoint> = STRAIGHT) {

    /** Sorted, with any two points sharing an x reduced to the last of them. */
    private val sorted: List<CurvePoint>
        get() = points.sortedBy { it.x }.let { list ->
            list.filterIndexed { i, p -> i == list.lastIndex || abs(list[i + 1].x - p.x) > X_EPS }
        }

    /** Nothing done: every point sits on the diagonal, however many there are. */
    val isIdentity: Boolean get() = points.all { abs(it.y - it.x) < EPS }

    /**
     * The curve at [x]. Outside the points it holds the end value rather than
     * running on: a curve is a mapping of levels, and levels stop at 0 and 1.
     */
    fun valueAt(x: Float): Float {
        val p = sorted
        if (p.isEmpty()) return x.coerceIn(0f, 1f)
        if (p.size == 1) return p[0].y.coerceIn(0f, 1f)
        if (x <= p.first().x) return p.first().y.coerceIn(0f, 1f)
        if (x >= p.last().x) return p.last().y.coerceIn(0f, 1f)

        val m = tangents(p)
        var i = 0
        while (i < p.size - 2 && x > p[i + 1].x) i++
        val h = p[i + 1].x - p[i].x
        val t = (x - p[i].x) / h
        val t2 = t * t
        val t3 = t2 * t
        val value = (2f * t3 - 3f * t2 + 1f) * p[i].y +
            (t3 - 2f * t2 + t) * h * m[i] +
            (-2f * t3 + 3f * t2) * p[i + 1].y +
            (t3 - t2) * h * m[i + 1]
        return value.coerceIn(0f, 1f)
    }

    /**
     * Fritsch-Carlson: the slope at each point, pulled in where it would make
     * the segment turn back on itself. This is the whole of the monotone fit.
     */
    private fun tangents(p: List<CurvePoint>): FloatArray {
        val n = p.size
        val d = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / (p[it + 1].x - p[it].x) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = (d[i - 1] + d[i]) / 2f
        // Where the curve turns - the secants either side of a point running
        // opposite ways - the averaged slope points against one of them, and a
        // segment given a tangent against its own secant leaves the range its
        // two points set. Fritsch-Carlson flattens the tangent there, and this
        // step was missing: the circle constraint below scales a tangent but
        // keeps its sign, so the overshoot survived it. On the Curves tool a
        // point placed below the one before it made the picture *brighter* than
        // the higher of the two just before it came down.
        for (i in 1 until n - 1) {
            if (d[i - 1] * d[i] <= 0f) m[i] = 0f
        }
        for (i in 0 until n - 1) {
            if (abs(d[i]) < 1e-7f) {
                // A flat run stays flat; a tangent through it is what overshoots.
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
        return m
    }

    companion object {
        val STRAIGHT = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        private const val EPS = 1e-4f

        /** Two points this close together are one point; below it the slope blows up. */
        const val X_EPS = 1f / 255f
    }
}

/**
 * The four curves a Curves tool offers, and the one table they fold into.
 *
 * [master] is applied to all three channels, not to luminance. Every tool in
 * this bracket means the same thing by its main curve, and a true luminance
 * curve needs a round trip through another colour space to avoid shifting hue -
 * which is what the HSL bands are already for.
 *
 * Per-channel first, then the master, which is the order Photoshop's Curves
 * has used for thirty years and the order anyone who has used one expects.
 */
data class ToneCurve(
    val master: Curve = Curve(),
    val red: Curve = Curve(),
    val green: Curve = Curve(),
    val blue: Curve = Curve()
) {
    val isIdentity: Boolean
        get() = master.isIdentity && red.isIdentity && green.isIdentity && blue.isIdentity

    /**
     * Both curves for one channel, folded: per-channel, then master.
     *
     * The shader never evaluates a curve - it looks this table up - so the
     * interpolation above exists once, here, rather than once here and once in
     * GLSL where it would drift. [lut] is what the texture carries and what
     * [sample] reads, so the preview, the file and a filter swatch are reading
     * the same numbers.
     */
    fun lut(size: Int = LUT_SIZE): FloatArray {
        val out = FloatArray(size * 3)
        for (i in 0 until size) {
            val x = i.toFloat() / (size - 1)
            out[i * 3] = byte(master.valueAt(red.valueAt(x)))
            out[i * 3 + 1] = byte(master.valueAt(green.valueAt(x)))
            out[i * 3 + 2] = byte(master.valueAt(blue.valueAt(x)))
        }
        return out
    }

    /**
     * Held to what a byte can say, because the texture the shader reads is one:
     * if the table kept full precision the CPU copy would land a fraction away
     * from the GPU on every pixel, and a swatch would not quite be the frame.
     * The output is eight bits either way, so nothing is lost by rounding here.
     */
    private fun byte(v: Float): Float = Math.round(v.coerceIn(0f, 1f) * 255f) / 255f

    companion object {
        /** 256 entries, the size of the texture the shader samples. */
        const val LUT_SIZE = 256

        val NONE = ToneCurve()

        /**
         * One channel of [lut] at [x], read the way a GL_LINEAR texture reads
         * it - between the two nearest entries. The CPU copy samples the table
         * rather than calling the curve directly so that it lands on exactly
         * what the GPU lands on, to the last digit a byte can hold.
         */
        fun sample(lut: FloatArray, channel: Int, x: Float, size: Int = LUT_SIZE): Float {
            val t = (x.coerceIn(0f, 1f)) * (size - 1)
            val i = t.toInt().coerceIn(0, size - 1)
            val j = (i + 1).coerceAtMost(size - 1)
            val f = t - i
            val a = lut[i * 3 + channel]
            val b = lut[j * 3 + channel]
            return a + (b - a) * f
        }
    }
}
