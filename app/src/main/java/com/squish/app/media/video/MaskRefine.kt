package com.squish.app.media.video

/**
 * Cut-out masks made cleaner (EDGE_PLAN F7): edges pulled onto the picture's
 * own edges, and the flicker taken out between samples.
 *
 * The selfie model answers a confidence a cell at a time, so its edge is soft
 * and drifts a pixel or two off the hair or the shoulder it means; and asked
 * ten times a second it disagrees with itself at the edge from one sample to
 * the next, which on screen is the outline boiling. [guided] is He, Sun and
 * Tang's guided filter, the frame's own brightness as the guide: within a
 * small window the mask is made a straight function of the brightness, so
 * where the picture has an edge the mask's edge goes to it. [temporal] is a
 * median of five samples per pixel - a change that lasts is kept where it
 * happened, a flip that lasts one or two samples is not - and then a light
 * blend so what is left moves smoothly.
 *
 * Free of Android; tools/jvm/MaskRefineChecks.kt executes both on pictures
 * whose truth is known.
 */
object MaskRefine {

    /** The guided filter's window, in mask pixels each side, and its smoothing: small enough to follow hair, large enough to hold a flat region. */
    const val RADIUS = 3
    const val EPS = 0.004f

    /**
     * [mask] (0..1) refined against [guide] (brightness, 0..1), both [w] x [h].
     */
    fun guided(guide: FloatArray, mask: FloatArray, w: Int, h: Int, r: Int = RADIUS, eps: Float = EPS): FloatArray {
        val n = w * h
        val meanI = box(guide, w, h, r)
        val meanP = box(mask, w, h, r)
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val ip = FloatArray(n) { guide[it] * mask[it] }
        val corrI = box(ii, w, h, r)
        val corrIp = box(ip, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (k in 0 until n) {
            val varI = corrI[k] - meanI[k] * meanI[k]
            val covIp = corrIp[k] - meanI[k] * meanP[k]
            a[k] = covIp / (varI + eps)
            b[k] = meanP[k] - a[k] * meanI[k]
        }
        val meanA = box(a, w, h, r)
        val meanB = box(b, w, h, r)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** The mean over a (2r+1)-square window, the window cut at the edges; from a summed-area table. */
    fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sum = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r); val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r); val x1 = minOf(w, x + r + 1)
                val s = sum[y1 * (w + 1) + x1] - sum[y0 * (w + 1) + x1] - sum[y1 * (w + 1) + x0] + sum[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return out
    }

    /**
     * [masks] (one per sample, each [size] bytes, 0..255) with the flicker
     * taken out: per pixel the median of five samples round it, then a
     * 1-2-1 blend. The first and last two samples use what they have.
     */
    fun temporal(masks: List<ByteArray>, size: Int): List<ByteArray> {
        val count = masks.size
        if (count < 3) return masks.map { it.copyOf() }
        val median = List(count) { ByteArray(size) }
        val window = IntArray(5)
        for (t in 0 until count) {
            val from = maxOf(0, t - 2); val to = minOf(count - 1, t + 2)
            val n = to - from + 1
            for (p in 0 until size) {
                for (k in 0 until n) window[k] = masks[from + k][p].toInt() and 0xFF
                median[t][p] = medianOf(window, n).toByte()
            }
        }
        return List(count) { t ->
            val prev = median[maxOf(0, t - 1)]; val here = median[t]; val next = median[minOf(count - 1, t + 1)]
            ByteArray(size) { p ->
                (((prev[p].toInt() and 0xFF) + 2 * (here[p].toInt() and 0xFF) + (next[p].toInt() and 0xFF) + 2) / 4).toByte()
            }
        }
    }

    private fun medianOf(v: IntArray, n: Int): Int {
        // Insertion sort of at most five.
        for (i in 1 until n) {
            val x = v[i]; var j = i - 1
            while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j-- }
            v[j + 1] = x
        }
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2
    }
}
