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

    /**
     * The width a frame is read back at for the analysis (FrameBlendEffect.readLuma):
     * box-averaged to [ANALYSIS_WIDTH] for the blocks, which is what keeps a
     * small thing from aliasing away there: a 56 px ball at 1080p is under five
     * pixels at 160, and read at 160 directly its move came out a pixel or more
     * off and the slow motion tore it (27 Oct). A multiple of [ANALYSIS_WIDTH].
     */
    const val READ_WIDTH = 480

    /** A block is believed above this confidence (MotionEstimator's own 0..1). */
    const val BELIEVED = 0.3f

    /** How far apart, in analysis pixels, a block's two measurements may be and still be trusted. */
    const val CONSISTENT_PX = 1.0

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
        val confidence: FloatArray,
        /**
         * How far each block's move is to be trusted for drawing, 0 to 1: 0
         * where measuring it the other way round gives a different answer - a
         * thin line, a turning edge, anything a block cannot pin down - so
         * the frame is the cross-fade there rather than a tear.
         */
        val trust: FloatArray = FloatArray(cols * rows) { 1f },
        /**
         * A second move for each block, in the same pixels: what the few
         * pixels that changed did, where only a few did - a ball, a distant
         * hand, anything a fraction of a block that the block's own move
         * (the still ground round it) does not carry. The block's own move
         * where there is none, so a shader reading it always has a vector.
         */
        val dx2: FloatArray = dx,
        val dy2: FloatArray = dy
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

    /** A pixel has changed between the two frames by more than this (luma, 0 to 1): noise and grain stay under it. */
    const val CHANGED = 0.1f

    /** A block has a second move only where fewer than this share of its pixels changed: more is the block's own motion. */
    const val SMALL_SHARE = 0.25f

    /** How far, in analysis pixels, a small thing is looked for: a fast one crosses a block or more a frame. */
    const val SMALL_REACH = 20

    /** The mean difference, both ways round, the second move may leave over the changed pixels. */
    const val SMALL_RESIDUAL = 0.12f

    /** The share of a small thing's changed pixels that must have two changed neighbours: a blob, not grain. */
    const val SMALL_JOINED = 0.5f

    /**
     * Where the few pixels of the block at ([c], [r]) that changed went: the
     * offset that matches them both ways round - each changed pixel of [prev]
     * against [next] moved on by it, and each of [next] against [prev] moved
     * back - which pins a small thing down at both ends of its move, where it
     * left and where it arrived. Null where too few or too many changed, or
     * nothing explains them. In analysis pixels.
     */
    fun smallMove(prev: LumaFrame, next: LumaFrame, c: Int, r: Int): Pair<Int, Int>? {
        val w = prev.width
        val h = prev.height
        val x0 = c * BLOCK; val y0 = r * BLOCK
        val x1 = minOf(x0 + BLOCK, w); val y1 = minOf(y0 + BLOCK, h)
        val area = (x1 - x0) * (y1 - y0)
        if (area <= 0) return null
        val changed = ArrayList<Int>()
        for (j in y0 until y1) for (i in x0 until x1) {
            if (abs(prev.pixels[j * w + i] - next.pixels[j * w + i]) > CHANGED) changed += j * w + i
        }
        if (changed.size < 2 || changed.size > area * SMALL_SHARE) return null
        // A thing is a blob: most of its changed pixels have changed ones beside
        // them. Grain changes pixels one at a time, scattered, and some offset
        // always matches scattered pixels by chance.
        val inSet = changed.toHashSet()
        val joined = changed.count { k ->
            val i = k % w
            var n = 0
            if (i > 0 && (k - 1) in inSet) n++
            if (i < w - 1 && (k + 1) in inSet) n++
            if ((k - w) in inSet) n++
            if ((k + w) in inSet) n++
            n >= 2
        }
        if (joined < changed.size * SMALL_JOINED) return null
        fun cost(vx: Int, vy: Int): Float {
            var sum = 0f
            var n = 0
            for (k in changed) {
                val i = k % w; val j = k / w
                val fi = i + vx; val fj = j + vy
                val bi = i - vx; val bj = j - vy
                if (fi in 0 until w && fj in 0 until h) { sum += abs(prev.pixels[k] - next.pixels[fj * w + fi]); n++ }
                if (bi in 0 until w && bj in 0 until h) { sum += abs(next.pixels[k] - prev.pixels[bj * w + bi]); n++ }
            }
            return if (n < changed.size) Float.MAX_VALUE else sum / n
        }
        // Every other offset, then the eight round the best.
        var best = 0 to 0
        var bestCost = cost(0, 0)
        val still = bestCost
        for (vy in -SMALL_REACH..SMALL_REACH step 2) for (vx in -SMALL_REACH..SMALL_REACH step 2) {
            val e = cost(vx, vy)
            if (e < bestCost) { bestCost = e; best = vx to vy }
        }
        val (cx, cy) = best
        for (vy in cy - 1..cy + 1) for (vx in cx - 1..cx + 1) {
            if (abs(vx) > SMALL_REACH || abs(vy) > SMALL_REACH) continue
            val e = cost(vx, vy)
            if (e < bestCost) { bestCost = e; best = vx to vy }
        }
        if (best == (0 to 0) || bestCost > SMALL_RESIDUAL || bestCost > still * 0.5f) return null
        return best
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
        val trust = FloatArray(cols * rows) { 1f }
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
            val back = MotionEstimator.estimate(crop(n, x0, y0, w, h), crop(p, x0, y0, w, h))
            val k = r * cols + c
            trust[k] = if (m.confidence > BELIEVED && back.confidence > BELIEVED &&
                kotlin.math.hypot((m.dx + back.dx).toDouble(), (m.dy + back.dy).toDouble()) > CONSISTENT_PX) 0f else 1f
            dx[k] = m.dx * scale
            dy[k] = m.dy * scale
            // Believed only if the move explains the block: the estimator's
            // confidence says how much better the best offset is than the rest,
            // which across a cut between two busy pictures can still be a lot.
            conf[k] = if (residual(p, n, c * BLOCK, r * BLOCK, m.dx, m.dy) <= MAX_RESIDUAL) m.confidence else 0f
        }
        val smooth = smoothed(Field(cols, rows, (BLOCK * scale).toFloat(), dx, dy, conf, trust))
        // The second moves after the smoothing, which must not average a ball
        // into the ground round it; a block without one carries its own.
        val dx2 = smooth.dx.copyOf()
        val dy2 = smooth.dy.copyOf()
        for (r in 0 until rows) for (c in 0 until cols) {
            smallMove(p, n, c, r)?.let { (vx, vy) -> dx2[r * cols + c] = vx.toFloat() * scale; dy2[r * cols + c] = vy.toFloat() * scale }
        }
        return Field(cols, rows, smooth.cell, smooth.dx, smooth.dy, smooth.confidence, smooth.trust, dx2, dy2)
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
        return Field(f.cols, f.rows, f.cell, dx, dy, f.confidence, f.trust, if (f.dx2 === f.dx) dx else f.dx2, if (f.dy2 === f.dy) dy else f.dy2)
    }

    /** The share of blocks the estimator believed. */
    fun heldShare(f: Field): Float = if (f.confidence.isEmpty()) 0f else f.confidence.count { it > BELIEVED }.toFloat() / f.confidence.size

    /** Whether this pair is moved along the field at all, or cross-faded as Blend frames always did. */
    fun usesFlow(f: Field): Boolean = heldShare(f) >= MIN_HELD

    /** The trust at a point, between the four nearest block centres, as [vectorAt] blends vectors. */
    fun trustAt(f: Field, x: Float, y: Float): Float {
        val gx = (x / f.cell - 0.5f).coerceIn(0f, (f.cols - 1).toFloat())
        val gy = (y / f.cell - 0.5f).coerceIn(0f, (f.rows - 1).toFloat())
        val c0 = floor(gx).toInt(); val r0 = floor(gy).toInt()
        val c1 = minOf(c0 + 1, f.cols - 1); val r1 = minOf(r0 + 1, f.rows - 1)
        val fx = gx - c0; val fy = gy - r0
        val t = f.trust
        val top = t[r0 * f.cols + c0] * (1 - fx) + t[r0 * f.cols + c1] * fx
        val bottom = t[r1 * f.cols + c0] * (1 - fx) + t[r1 * f.cols + c1] * fx
        return top * (1 - fy) + bottom * fy
    }

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

    /** Where in [candidates] the second moves begin: after the blend and the four blocks' own. */
    const val FIRST_SMALL = 5

    /** The mean difference a small thing's move may leave, per sample, and still be taken over standing still. */
    const val SMALL_AGREES = 0.04f

    /**
     * After standing still (interpolate tries it first), the blended vector (it wins a tie among these), then the four nearest blocks'
     * own, then their second moves - the small fast thing, which is only ever
     * chosen where the two frames moved along it agree.
     */
    fun candidates(f: Field, x: Float, y: Float): List<Pair<Float, Float>> {
        val gx = (x / f.cell - 0.5f).coerceIn(0f, (f.cols - 1).toFloat())
        val gy = (y / f.cell - 0.5f).coerceIn(0f, (f.rows - 1).toFloat())
        val c0 = floor(gx).toInt(); val r0 = floor(gy).toInt()
        val c1 = minOf(c0 + 1, f.cols - 1); val r1 = minOf(r0 + 1, f.rows - 1)
        fun at(c: Int, r: Int) = f.dx[r * f.cols + c] to f.dy[r * f.cols + c]
        fun second(c: Int, r: Int) = f.dx2[r * f.cols + c] to f.dy2[r * f.cols + c]
        return listOf(
            vectorAt(f, x, y), at(c0, r0), at(c1, r0), at(c0, r1), at(c1, r1),
            second(c0, r0), second(c1, r0), second(c0, r1), second(c1, r1)
        )
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
            // Standing still first, so it wins a tie: over smooth ground a
            // block's move can be any shift along it, and where a ball covers
            // both frames that shift, blended in from the blocks round it, drew
            // its middle from the ground (27 Oct).
            var bestCost = stillCost(prev, next, x, y)
            var vx = 0f
            var vy = 0f
            var small = false
            val cands = candidates(f, x, y)
            var smallCost = Float.MAX_VALUE
            var sx = 0f
            var sy = 0f
            for ((index, cand) in cands.withIndex()) {
                var cost = 0f
                for (o in PATCH) {
                    cost += abs(
                        sample(prev, x + o.first - t * cand.first, y + o.second - t * cand.second) -
                            sample(next, x + o.first + (1 - t) * cand.first, y + o.second + (1 - t) * cand.second)
                    )
                }
                // A block with no second move offers its own again: not a small thing.
                if (index >= FIRST_SMALL && cand != cands[index - (FIRST_SMALL - 1)]) {
                    if (cost < smallCost - 1e-6f) { smallCost = cost; sx = cand.first; sy = cand.second }
                } else if (cost < bestCost - 1e-6f) { bestCost = cost; vx = cand.first; vy = cand.second }
            }
            // A small thing's move wins wherever the two frames agree along it,
            // even where standing still agrees as well: half way along its path
            // the ground shows in both frames, so "nothing moved" is just as
            // consistent there - and is the ghost. Where it is wrong, on flat
            // ground, moving flat ground changes nothing.
            // Only where what it brings in is the thing in motion: where it was
            // in the earlier frame is not that in the later one, and the other
            // way round. Still ground beside a moving edge can match itself
            // shifted, and was being moved.
            val passing = smallCost < Float.MAX_VALUE &&
                abs(sample(prev, x - t * sx, y - t * sy) - sample(next, x - t * sx, y - t * sy)) > CHANGED &&
                abs(sample(next, x + (1 - t) * sx, y + (1 - t) * sy) - sample(prev, x + (1 - t) * sx, y + (1 - t) * sy)) > CHANGED
            val changedHere = abs(prev.pixels[k] - next.pixels[k]) > CHANGED
            if ((changedHere && smallCost < bestCost * 0.5f) || (passing && smallCost <= SMALL_AGREES * PATCH.size)) { vx = sx; vy = sy; small = true }
            val a = sample(prev, x - t * vx, y - t * vy)
            val b = sample(next, x + (1 - t) * vx, y + (1 - t) * vy)
            val moved = a * (1 - t) + b * t
            // A block's trust is about its own move. A small thing in it is
            // what makes that move hard to pin down both ways round, so where
            // the small thing's move is the one chosen, the two frames agreeing
            // along it is the test, not the block's trust.
            val distrust = if (small) 0f else 1f - trustAt(f, x, y)
            val back = maxOf(smoothstep(DISAGREE_FROM, DISAGREE_TO, abs(a - b)), distrust)
            out[k] = moved + (fade - moved) * back
            // The small thing itself, from whichever frame has it here. Its
            // move is measured on the analysis frame, where a 56 px ball at
            // 1080p is under five pixels and aliased, so drawn at full size the
            // move is out by a few pixels: the two copies miss each other, and
            // where only one covers the pixel the frames disagree and the pixel
            // fell back to the ground - the ball cut down to where its two
            // copies overlapped, torn at its rim (27 Oct, the moto). A copy is
            // the thing where its own pixel changed and its partner is within
            // the slack of the other end, itself on something that changed.
            if (smallCost < Float.MAX_VALUE) {
                val r = f.cell / BLOCK * MATCH_SLACK
                val pax = x - t * sx; val pay = y - t * sy
                val pbx = x + (1 - t) * sx; val pby = y + (1 - t) * sy
                val sa = sample(prev, pax, pay); val sb = sample(next, pbx, pby)
                val aThing = abs(sa - sample(next, pax, pay)) > CHANGED && changedNear(prev, next, pbx, pby, r) && nearest(next, pbx, pby, r, sa) < CHANGED
                val bThing = abs(sb - sample(prev, pbx, pby)) > CHANGED && changedNear(prev, next, pax, pay, r) && nearest(prev, pax, pay, r, sb) < CHANGED
                // Where its two copies overlap, the ends of a pixel on its rim
                // may both lie where it covers both frames, and nothing changed
                // there; but this pixel changed, and the two ends agreeing is
                // the thing passing (or the ground, which is right too).
                val through = !aThing && !bThing && changedHere &&
                    nearest(next, pbx, pby, r, sa) < CHANGED && nearest(prev, pax, pay, r, sb) < CHANGED
                if (aThing || bThing || through) {
                    out[k] = if (aThing == bThing) sa * (1 - t) + sb * t else if (aThing) sa else sb
                    continue
                }
            }
        }
        return LumaFrame(w, h, out)
    }

    /**
     * How far, in analysis pixels, a small thing's move may be out when its
     * two ends are matched: the move is measured on the analysis frame, and
     * the picture is drawn at eight or twelve times that.
     */
    const val MATCH_SLACK = 1.0f

    /** What standing still leaves round ([x], [y]), over [PATCH]: the first candidate. */
    private fun stillCost(prev: LumaFrame, next: LumaFrame, x: Float, y: Float): Float {
        var c = 0f
        for (o in PATCH) c += abs(sample(prev, x + o.first, y + o.second) - sample(next, x + o.first, y + o.second))
        return c
    }

    /** Whether [a] and [b] differ by more than CHANGED at ([x], [y]) or [r] either way along each axis. */
    private fun changedNear(a: LumaFrame, b: LumaFrame, x: Float, y: Float, r: Float): Boolean =
        abs(sample(a, x, y) - sample(b, x, y)) > CHANGED ||
            abs(sample(a, x + r, y) - sample(b, x + r, y)) > CHANGED || abs(sample(a, x - r, y) - sample(b, x - r, y)) > CHANGED ||
            abs(sample(a, x, y + r) - sample(b, x, y + r)) > CHANGED || abs(sample(a, x, y - r) - sample(b, x, y - r)) > CHANGED

    /** The least difference between [value] and [f] at ([x], [y]) or [r] either way along each axis. */
    private fun nearest(f: LumaFrame, x: Float, y: Float, r: Float, value: Float): Float =
        minOf(
            abs(sample(f, x, y) - value),
            minOf(abs(sample(f, x + r, y) - value), abs(sample(f, x - r, y) - value)),
            minOf(abs(sample(f, x, y + r) - value), abs(sample(f, x, y - r) - value))
        )

    /**
     * The field as the shader's texture: a pixel a block, RGBA8, each vector
     * component as sixteen bits over ±[RANGE] - red and green for x, blue and
     * alpha for y. Eight bits alone were a step of half a pixel at 1080p.
     */
    fun pack(f: Field): ByteArray = pack(f.dx, f.dy, f.cols * f.rows)

    /** The second moves ([Field.dx2]) as the shader's third texture, packed as [pack] packs the first. */
    fun packSecond(f: Field): ByteArray = pack(f.dx2, f.dy2, f.cols * f.rows)

    private fun pack(xs: FloatArray, ys: FloatArray, n: Int): ByteArray {
        val out = ByteArray(n * 4)
        for (k in 0 until n) {
            val ux = encode(xs[k]); val uy = encode(ys[k])
            out[k * 4] = (ux shr 8).toByte(); out[k * 4 + 1] = (ux and 0xFF).toByte()
            out[k * 4 + 2] = (uy shr 8).toByte(); out[k * 4 + 3] = (uy and 0xFF).toByte()
        }
        return out
    }

    /** Each block's trust as a byte, for the shader's second texture (RGBA8, the trust in every channel). */
    fun packTrust(f: Field): ByteArray = ByteArray(f.cols * f.rows * 4) { i -> (f.trust[i / 4].coerceIn(0f, 1f) * 255f).toInt().toByte() }

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
