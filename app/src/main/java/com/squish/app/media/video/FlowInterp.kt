package com.squish.app.media.video

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Smooth slow motion (EDGE_PLAN F1, INSHOT_PARITY P1): an in-between frame
 * drawn from both of its neighbours moved along the motion, rather than the
 * two cross-faded - which is motion blur, not motion, and what Blend frames
 * did alone.
 *
 * The motion is a coarse field, one vector a block, each found by the same
 * [MotionEstimator] the stabilizer and the tracker use, run on that block and
 * a margin round it; then a median over the neighbours, so one wrong block
 * does not tear the picture. An in-between frame at `t` takes the earlier
 * frame from `p - t·v` and the later one from `p + (1-t)·v`. Where the two
 * disagree - something uncovered, something the field got wrong - the pixel
 * goes back towards the plain cross-fade rather than smearing; and where the
 * field holds too little of the picture (a cut, a flash, a blur) the whole
 * frame is the cross-fade, so no motion is invented.
 *
 * This is the CPU twin of squish_flow_es2.glsl, which draws the same thing on
 * the export's frames: tools/jvm/FlowInterpChecks.kt executes it on frames
 * whose truth is known.
 */
object FlowInterp {

    /** Analysis pixels a block covers. */
    const val BLOCK = 16

    /** The widest a frame is analysed at: wider frames are box-averaged down first. */
    const val ANALYSIS_WIDTH = 160

    /** A block is believed above this confidence (MotionEstimator's own 0..1). */
    const val BELIEVED = 0.3f

    /** The share of blocks that must be believed for a pair to be moved along the field at all. */
    const val MIN_HELD = 0.5f

    /** The largest motion the packed field carries, in the field's pixels, either way. */
    const val RANGE = 64f

    /** Where the two moved samples disagree by this much, the pixel starts back towards the cross-fade... */
    const val DISAGREE_FROM = 0.12f

    /** ...and by this much it is the cross-fade. */
    const val DISAGREE_TO = 0.3f

    /**
     * One vector a block, in the pixels of the frames it was measured on: how
     * far the content moved from the earlier frame to the later one. [cell] is
     * a block's size in those pixels.
     */
    class Field(
        val cols: Int,
        val rows: Int,
        val cell: Float,
        val dx: FloatArray,
        val dy: FloatArray,
        val confidence: FloatArray
    )

    /** The mean difference a believed block may leave between the earlier frame moved and the later one. */
    const val MAX_RESIDUAL = 0.06f

    /** How far [next] is, over one block, from [prev] moved by (dx, dy): what the move leaves unexplained. */
    private fun residual(prev: LumaFrame, next: LumaFrame, x0: Int, y0: Int, dx: Float, dy: Float): Float {
        var sum = 0f
        var n = 0
        for (j in y0 until minOf(y0 + BLOCK, next.height)) for (i in x0 until minOf(x0 + BLOCK, next.width)) {
            sum += abs(next.pixels[j * next.width + i] - sample(prev, i + 0.5f - dx, j + 0.5f - dy))
            n++
        }
        return if (n == 0) Float.MAX_VALUE else sum / n
    }

    /** The field between [prev] and [next], smoothed. */
    fun field(prev: LumaFrame, next: LumaFrame): Field {
        val scale = maxOf(1, (prev.width + ANALYSIS_WIDTH - 1) / ANALYSIS_WIDTH)
        val p = if (scale == 1) prev else shrink(prev, scale)
        val n = if (scale == 1) next else shrink(next, scale)
        val cols = maxOf(1, (p.width + BLOCK - 1) / BLOCK)
        val rows = maxOf(1, (p.height + BLOCK - 1) / BLOCK)
        val dx = FloatArray(cols * rows)
        val dy = FloatArray(cols * rows)
        val conf = FloatArray(cols * rows)
        // The block and a margin the search can look into: MotionEstimator
        // skips its search radius and one more at every border.
        val margin = 9
        val size = BLOCK + 2 * margin
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = (c * BLOCK - margin).coerceIn(0, maxOf(0, p.width - size))
            val y0 = (r * BLOCK - margin).coerceIn(0, maxOf(0, p.height - size))
            val w = minOf(size, p.width)
            val h = minOf(size, p.height)
            val m = MotionEstimator.estimate(crop(p, x0, y0, w, h), crop(n, x0, y0, w, h))
            val k = r * cols + c
            dx[k] = m.dx * scale
            dy[k] = m.dy * scale
            // Believed only if the move explains the block: the estimator's
            // confidence says how much better the best offset is than the rest,
            // which across a cut between two busy pictures can still be a lot.
            conf[k] = if (residual(p, n, c * BLOCK, r * BLOCK, m.dx, m.dy) <= MAX_RESIDUAL) m.confidence else 0f
        }
        return smoothed(Field(cols, rows, (BLOCK * scale).toFloat(), dx, dy, conf))
    }

    /**
     * Each vector the median of the believed vectors round it, itself
     * included: one block caught on the wrong edge does not tear, and a block
     * that could not be measured takes its neighbours' motion. A block with
     * no believed neighbour at all stays still.
     */
    fun smoothed(f: Field): Field {
        val dx = FloatArray(f.dx.size)
        val dy = FloatArray(f.dy.size)
        val xs = ArrayList<Float>(9)
        val ys = ArrayList<Float>(9)
        for (r in 0 until f.rows) for (c in 0 until f.cols) {
            xs.clear(); ys.clear()
            for (rr in r - 1..r + 1) for (cc in c - 1..c + 1) {
                if (rr !in 0 until f.rows || cc !in 0 until f.cols) continue
                val k = rr * f.cols + cc
                if (f.confidence[k] > BELIEVED) { xs += f.dx[k]; ys += f.dy[k] }
            }
            val k = r * f.cols + c
            if (xs.isNotEmpty()) { dx[k] = median(xs); dy[k] = median(ys) }
        }
        return Field(f.cols, f.rows, f.cell, dx, dy, f.confidence)
    }

    /** The share of blocks the estimator believed. */
    fun heldShare(f: Field): Float = if (f.confidence.isEmpty()) 0f else f.confidence.count { it > BELIEVED }.toFloat() / f.confidence.size

    /** Whether this pair is moved along the field at all, or cross-faded as Blend frames always did. */
    fun usesFlow(f: Field): Boolean = heldShare(f) >= MIN_HELD

    /** The field's vector at a point of the frame, between the four nearest block centres. */
    fun vectorAt(f: Field, x: Float, y: Float): Pair<Float, Float> {
        val gx = (x / f.cell - 0.5f).coerceIn(0f, (f.cols - 1).toFloat())
        val gy = (y / f.cell - 0.5f).coerceIn(0f, (f.rows - 1).toFloat())
        val c0 = floor(gx).toInt(); val r0 = floor(gy).toInt()
        val c1 = minOf(c0 + 1, f.cols - 1); val r1 = minOf(r0 + 1, f.rows - 1)
        val fx = gx - c0; val fy = gy - r0
        fun lerp2(a: FloatArray): Float {
            val top = a[r0 * f.cols + c0] * (1 - fx) + a[r0 * f.cols + c1] * fx
            val bottom = a[r1 * f.cols + c0] * (1 - fx) + a[r1 * f.cols + c1] * fx
            return top * (1 - fy) + bottom * fy
        }
        return lerp2(f.dx) to lerp2(f.dy)
    }

    /** Where round a pixel the two moved frames are compared: a plus, 1.5 px out. */
    val PATCH = listOf(0f to 0f, 1.5f to 0f, -1.5f to 0f, 0f to 1.5f, 0f to -1.5f)

    /** The blended vector first (it wins a tie), then the four nearest blocks' own. */
    fun candidates(f: Field, x: Float, y: Float): List<Pair<Float, Float>> {
        val gx = (x / f.cell - 0.5f).coerceIn(0f, (f.cols - 1).toFloat())
        val gy = (y / f.cell - 0.5f).coerceIn(0f, (f.rows - 1).toFloat())
        val c0 = floor(gx).toInt(); val r0 = floor(gy).toInt()
        val c1 = minOf(c0 + 1, f.cols - 1); val r1 = minOf(r0 + 1, f.rows - 1)
        fun at(c: Int, r: Int) = f.dx[r * f.cols + c] to f.dy[r * f.cols + c]
        return listOf(vectorAt(f, x, y), at(c0, r0), at(c1, r0), at(c0, r1), at(c1, r1))
    }

    /** The frame [t] of the way from [prev] to [next]: what the shader draws, pixel for pixel. */
    fun interpolate(prev: LumaFrame, next: LumaFrame, f: Field, t: Float): LumaFrame {
        val w = prev.width
        val h = prev.height
        val out = FloatArray(w * h)
        val flow = usesFlow(f)
        for (j in 0 until h) for (i in 0 until w) {
            val k = j * w + i
            val fade = prev.pixels[k] * (1 - t) + next.pixels[k] * t
            if (!flow) { out[k] = fade; continue }
            val x = i + 0.5f
            val y = j + 0.5f
            // The vector of whichever of the four nearest blocks - or their
            // blend - makes the two moved frames agree best round this pixel.
            // A blend alone smeared every edge where two motions meet: half
            // way between a still wall and a moving hand it moved both by half.
            var bestCost = Float.MAX_VALUE
            var vx = 0f
            var vy = 0f
            for (cand in candidates(f, x, y)) {
                var cost = 0f
                for (o in PATCH) {
                    cost += abs(
                        sample(prev, x + o.first - t * cand.first, y + o.second - t * cand.second) -
                            sample(next, x + o.first + (1 - t) * cand.first, y + o.second + (1 - t) * cand.second)
                    )
                }
                if (cost < bestCost - 1e-6f) { bestCost = cost; vx = cand.first; vy = cand.second }
            }
            val a = sample(prev, x - t * vx, y - t * vy)
            val b = sample(next, x + (1 - t) * vx, y + (1 - t) * vy)
            val moved = a * (1 - t) + b * t
            val back = smoothstep(DISAGREE_FROM, DISAGREE_TO, abs(a - b))
            out[k] = moved + (fade - moved) * back
        }
        return LumaFrame(w, h, out)
    }

    /**
     * The field as the shader's texture: a pixel a block, RGBA8, each vector
     * component as sixteen bits over ±[RANGE] - red and green for x, blue and
     * alpha for y. Eight bits alone were a step of half a pixel at 1080p.
     */
    fun pack(f: Field): ByteArray {
        val out = ByteArray(f.cols * f.rows * 4)
        for (k in f.dx.indices) {
            val ux = encode(f.dx[k]); val uy = encode(f.dy[k])
            out[k * 4] = (ux shr 8).toByte(); out[k * 4 + 1] = (ux and 0xFF).toByte()
            out[k * 4 + 2] = (uy shr 8).toByte(); out[k * 4 + 3] = (uy and 0xFF).toByte()
        }
        return out
    }

    /** [pack] read back, as the shader reads it. */
    fun unpack(bytes: ByteArray, cols: Int, rows: Int): Field {
        val n = cols * rows
        val dx = FloatArray(n) { k -> decode(((bytes[k * 4].toInt() and 0xFF) shl 8) or (bytes[k * 4 + 1].toInt() and 0xFF)) }
        val dy = FloatArray(n) { k -> decode(((bytes[k * 4 + 2].toInt() and 0xFF) shl 8) or (bytes[k * 4 + 3].toInt() and 0xFF)) }
        return Field(cols, rows, 1f, dx, dy, FloatArray(n) { 1f })
    }

    private fun encode(v: Float): Int = (((v / RANGE).coerceIn(-1f, 1f) * 0.5f + 0.5f) * 65535f).roundToInt().coerceIn(0, 65535)
    private fun decode(u: Int): Float = ((u / 65535f) - 0.5f) * 2f * RANGE

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Bilinear, clamped to the edge - as a texture with CLAMP_TO_EDGE and LINEAR samples. */
    private fun sample(f: LumaFrame, x: Float, y: Float): Float {
        val gx = (x - 0.5f).coerceIn(0f, (f.width - 1).toFloat())
        val gy = (y - 0.5f).coerceIn(0f, (f.height - 1).toFloat())
        val x0 = floor(gx).toInt(); val y0 = floor(gy).toInt()
        val x1 = minOf(x0 + 1, f.width - 1); val y1 = minOf(y0 + 1, f.height - 1)
        val fx = gx - x0; val fy = gy - y0
        val p = f.pixels
        val top = p[y0 * f.width + x0] * (1 - fx) + p[y0 * f.width + x1] * fx
        val bottom = p[y1 * f.width + x0] * (1 - fx) + p[y1 * f.width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun crop(f: LumaFrame, x0: Int, y0: Int, w: Int, h: Int): LumaFrame {
        val out = FloatArray(w * h)
        for (j in 0 until h) System.arraycopy(f.pixels, (y0 + j) * f.width + x0, out, j * w, w)
        return LumaFrame(w, h, out)
    }

    /** Box-averaged down by a whole [factor]. */
    fun shrink(f: LumaFrame, factor: Int): LumaFrame {
        val w = maxOf(1, f.width / factor)
        val h = maxOf(1, f.height / factor)
        val out = FloatArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            var sum = 0f
            for (yy in 0 until factor) for (xx in 0 until factor) sum += f.pixels[(j * factor + yy) * f.width + i * factor + xx]
            out[j * w + i] = sum / (factor * factor)
        }
        return LumaFrame(w, h, out)
    }

    private fun median(v: MutableList<Float>): Float {
        v.sort()
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2f
    }
}
