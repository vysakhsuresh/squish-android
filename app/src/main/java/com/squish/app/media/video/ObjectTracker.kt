package com.squish.app.media.video

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where the tracked thing was at one moment, in fractions of the frame. */
data class TrackSample(
    val atMs: Long,
    val xFraction: Float,
    val yFraction: Float,
    /**
     * How big the thing is against the size it was first selected at, carried
     * along the track (ObjectTracker.scale, EDGE_PLAN F8): a face walking to
     * the camera grows from 1 towards 2. It used to be re-read each frame from
     * 0.94, 1 and 1.06 and never accumulated, so it could only ever be one of
     * those three, and pulsed by twelve per cent a frame. It still moves in 3%
     * steps, so the one place that uses it smooths it first ([withSmoothedScale]).
     */
    val scale: Float = 1f,
    /** -1..1. Below [MotionTrack.LOST_BELOW] the tracker had lost it. */
    val confidence: Float = 1f
)

/**
 * A path smoothed by a confidence-weighted Gaussian [seconds] wide each way.
 *
 * The width is in *seconds*, deliberately, and that is the whole point of this
 * being a function of its own. Auto-reframe samples four times a second but
 * caps the count, so past ninety seconds the samples spread out - and a window
 * sized in *samples*, as this was, grew with the clip: at five minutes it was
 * smoothing over two and a half seconds where its own comment promised three
 * quarters of one, so a long shot's reframe lagged the subject by seconds. The
 * spacing is read back off the samples rather than assumed.
 */
fun smoothedTrack(samples: List<TrackSample>, seconds: Float): List<TrackSample> {
    val sigma = trackSigma(samples, seconds) ?: return samples
    val radius = (sigma * 3).roundToInt().coerceAtLeast(1)
    return samples.indices.map { i ->
        var sx = 0f
        var sy = 0f
        var total = 0f
        for (j in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(samples.lastIndex)) {
            val d = (j - i).toFloat()
            val w = kotlin.math.exp(-(d * d) / (2 * sigma * sigma)) * samples[j].confidence
            sx += samples[j].xFraction * w
            sy += samples[j].yFraction * w
            total += w
        }
        val s = samples[i]
        if (total <= 0f) s else s.copy(xFraction = sx / total, yFraction = sy / total)
    }
}

/**
 * The same path with its *scale* smoothed and its positions untouched.
 *
 * For pinning a layer to a track. [TrackSample.scale] is a coarse reading of
 * one frame against the original patch - one of three numbers, re-picked every
 * frame and never accumulated - so multiplied straight into a layer's size it
 * made the layer pulse by twelve per cent at every frame of the track. The
 * positions are left alone on purpose: the tracker's own x and y are what the
 * pin is *for*, and smoothing them would make the overlay lag the thing it is
 * pinned to.
 */
fun withSmoothedScale(samples: List<TrackSample>, seconds: Float): List<TrackSample> {
    val sigma = trackSigma(samples, seconds) ?: return samples
    val radius = (sigma * 3).roundToInt().coerceAtLeast(1)
    return samples.indices.map { i ->
        var sum = 0f
        var total = 0f
        for (j in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(samples.lastIndex)) {
            val d = (j - i).toFloat()
            val w = kotlin.math.exp(-(d * d) / (2 * sigma * sigma)) * samples[j].confidence
            sum += samples[j].scale * w
            total += w
        }
        val s = samples[i]
        if (total <= 0f) s else s.copy(scale = sum / total)
    }
}

/**
 * The Gaussian's width in *samples* for a window of [seconds], or null when
 * there is nothing to smooth.
 *
 * Half a sample is the floor: below that the kernel is one sample wide and the
 * smoothing is a no-op, which is the honest answer for samples already further
 * apart than the window. The spacing is read off the samples rather than
 * assumed, which is the whole point - see [smoothedTrack].
 */
private fun trackSigma(samples: List<TrackSample>, seconds: Float): Float? {
    if (samples.size < 2 || seconds <= 0f) return null
    val spanMs = (samples.last().atMs - samples.first().atMs).toFloat()
    val stepMs = (spanMs / (samples.size - 1)).coerceAtLeast(1f)
    return (seconds * 1000f / stepMs).coerceAtLeast(0.5f)
}

/**
 * A thing's path through a clip, keyed by source time.
 *
 * Source time rather than clip time for the same reason the stabilizer uses it: the
 * path belongs to the footage, so trimming the clip must not slide the track away
 * from the object it was following.
 */
data class MotionTrack(val samples: List<TrackSample>) {

    val isEmpty: Boolean get() = samples.isEmpty()

    /** How much of the track the tracker was actually confident about. */
    val heldFraction: Float
        get() = if (samples.isEmpty()) 0f
        else samples.count { it.confidence >= LOST_BELOW }.toFloat() / samples.size

    /**
     * The position at a moment, interpolated between samples. Outside the tracked
     * range it holds at the nearest end rather than extrapolating - a track that
     * keeps flying off past its last real observation is never what was meant.
     */
    fun sampleAt(sourceMs: Long): TrackSample? {
        if (samples.isEmpty()) return null
        val first = samples.first()
        if (sourceMs <= first.atMs) return first
        val last = samples.last()
        if (sourceMs >= last.atMs) return last

        var i = 0
        while (i < samples.size - 1 && samples[i + 1].atMs <= sourceMs) i++
        val a = samples[i]
        val b = samples[i + 1]
        val span = (b.atMs - a.atMs).coerceAtLeast(1L)
        val t = ((sourceMs - a.atMs).toFloat() / span).coerceIn(0f, 1f)
        return TrackSample(
            atMs = sourceMs,
            xFraction = a.xFraction + (b.xFraction - a.xFraction) * t,
            yFraction = a.yFraction + (b.yFraction - a.yFraction) * t,
            scale = a.scale + (b.scale - a.scale) * t,
            confidence = minOf(a.confidence, b.confidence)
        )
    }

    companion object {
        const val LOST_BELOW = 0.45f
    }
}

/** What one step of tracking concluded. */
data class TrackStep(val x: Float, val y: Float, val scale: Float, val confidence: Float)

/**
 * Follows a patch of picture from frame to frame.
 *
 * Zero-mean normalized cross-correlation rather than the absolute-difference match
 * the stabilizer uses. Stabilization compares whole consecutive frames, which are
 * lit identically; a tracked object walks through shadow and sunlight, and a plain
 * difference score would follow the lighting instead of the object. Subtracting each
 * patch's own mean and dividing by its own spread makes the score care about pattern
 * rather than brightness.
 *
 * The template is nudged toward what it currently sees rather than being replaced or
 * frozen. Freezing it loses the object the moment it turns; replacing it lets the
 * template wander onto the background a little each frame until it is tracking
 * nothing at all. A slow blend keeps up with real change and takes a long time to
 * drift.
 */
class ObjectTracker(
    template: FloatArray,
    val templateWidth: Int,
    val templateHeight: Int
) {
    private val current = template.copyOf()

    /** The patch as first selected, kept so runaway drift can be detected. */
    private val original = template.copyOf()

    private var currentMean = 0f
    private var currentNorm = 0f

    /** The original's own statistics: the size is judged against it (see [step]).
     */
    private var originalMean = 0f
    private var originalNorm = 1f

    init {
        recomputeStats()
        originalMean = original.average().toFloat()
        originalNorm = sqrt(original.fold(0f) { s, v -> s + (v - originalMean) * (v - originalMean) }).coerceAtLeast(1e-6f)
    }

    /**
     * How big the thing is now against the patch first selected (EDGE_PLAN F8),
     * carried from step to step: each step tries a little bigger and a little
     * smaller than the last, and the template is learned at the size it was
     * found at. It used to be re-read each frame from 0.94, 1 and 1.06 against
     * the original size and never kept, so a face walking towards the camera
     * read as "1.06" for ever and a layer pinned to it stayed its first size.
     */
    var scale: Float = 1f
        private set

    /** Steps since the thing was last found with confidence; the search widens with each. */
    private var lostSteps = 0

    /**
     * How strongly the thing matched while it was surely held, averaged: a lost
     * thing is taken back only on a match nearly that strong (see [step]).
     */
    private var heldLevel = -1f

    /**
     * Searches around [lastX], [lastY] for the template.
     *
     * Coarse then fine: every second offset first, then the immediate neighbours of
     * the winner. A full search costs four times as much and finds the same place.
     *
     * Once the thing is lost (a step under [MotionTrack.LOST_BELOW]) the next
     * steps look further each time - twice the radius, three times, up to the
     * whole frame - at a coarser stride, then refine round the best place; it
     * is taken back only on a match as strong as [REFIND_ABOVE], so a person
     * walking behind a tree is picked up where they come out, and a wall that
     * merely looks a little like them is not.
     */
    fun step(frame: LumaFrame, lastX: Float, lastY: Float, searchRadius: Int): TrackStep {
        var cx = lastX.roundToInt()
        var cy = lastY.roundToInt()

        if (lostSteps > 0) {
            val wide = searchRadius * (1 + lostSteps * 2)
            val reach = minOf(wide, maxOf(frame.width, frame.height))
            if (reach > searchRadius) {
                val stride = (reach / 24).coerceAtLeast(2)
                val wideBest = sweep(frame, cx, cy, reach, stride, scale)
                if (wideBest.first > -2f) { cx = wideBest.second; cy = wideBest.third }
            }
        }

        val local = sweep(frame, cx, cy, searchRadius, 2, scale)
        var best = local.first
        var bestX = local.second
        var bestY = local.third

        // Measured from the coarse winner, which does not move while the nine
        // are scored. It used to read `bestX + dx` while assigning `bestX +=
        // dx` in the same loop, so after the first improvement the 3x3 was
        // taken about a *moved* centre: offsets inside the intended square
        // were never scored, offsets outside it were, and which ones depended
        // on the iteration order - with (-1,-1) first the walk leant up and
        // left. Nine iterations could each take a step, putting the answer
        // nine analysis pixels from the coarse winner; and since every frame
        // searches from the frame before, that compounded.
        val coarseX = bestX
        val coarseY = bestY
        for (dy in -1..1) {
            for (dx in -1..1) {
                val score = correlate(frame, coarseX + dx, coarseY + dy, scale)
                if (score > best) {
                    best = score
                    bestX = coarseX + dx
                    bestY = coarseY + dy
                }
            }
        }

        // The size, at the winning position only: a step either way from the
        // size it was last found at, taken only when it is plainly better, so
        // texture noise does not walk the size about. Judged against the patch
        // as first selected, not the template as learned: the learned one takes
        // in each frame at the size it was found at, so a thing growing slowly
        // was learned bigger a little every frame and a 5% step never looked
        // better - a card that grew 1.6 times over nine seconds read 1.15 on the
        // moto (28 Oct). The original does not grow; against it, the size does.
        var bestScale = scale
        // Each size scored at its own best place within a couple of pixels: as a
        // thing grows about its middle, the point the learned template holds
        // slides on it against the original, and every size scored at that one
        // place read a little off - the size then held for a second at a time
        // on a growing card's real frames (28 Oct).
        fun fitAt(s: Float): Float {
            var top = -2f
            for (dy in -FIT_SLACK..FIT_SLACK) for (dx in -FIT_SLACK..FIT_SLACK) {
                val v = correlate(frame, bestX + dx, bestY + dy, s, original, originalMean, originalNorm)
                if (v > top) top = v
            }
            return top
        }
        var bestFit = fitAt(scale)
        for (factor in SCALE_STEPS) {
            val candidate = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            val fit = fitAt(candidate)
            if (fit > bestFit + SCALE_MARGIN) {
                bestFit = fit
                bestScale = candidate
            }
        }
        // Taken back only on a match nearly as strong as it was held at, and
        // never under REFIND_ABOVE: a card held at 0.97 and hidden for a second
        // was "found" in the fractal behind it at 0.74, a corner of the frame
        // away, and the line pinned to it went there (28 Oct, the moto).
        val refind = maxOf(REFIND_ABOVE, heldLevel * REFIND_SHARE)
        val found = if (lostSteps > 0) best >= refind else best >= MotionTrack.LOST_BELOW
        if (found) {
            lostSteps = 0
            // Every step it is found at, weak ones too, and from the first one
            // rather than from 1: started at 1 and moved only by sure matches, a
            // thing held at 0.5-0.7 needed 0.85 to be taken back after a gap and
            // never was (review, 28 Oct).
            heldLevel = if (heldLevel < 0f) best else heldLevel * (1f - HELD_FOLLOW) + best * HELD_FOLLOW
            // The size only from a sure lock, as the template is learned only from
            // one: a thing half seen behind something busy matched at about 0.5,
            // just over LOST_BELOW, and each such frame stepped the size towards
            // whatever was there (28 Oct, the moto: 1.09 to 0.76 over a second's gap).
            if (best >= TEMPLATE_UPDATE_ABOVE) {
                scale = bestScale
                blendTemplate(frame, bestX, bestY, scale)
            }
        } else {
            lostSteps++
            // Below the bar a re-find must clear, it is still lost: said so, whatever the score.
            if (best >= MotionTrack.LOST_BELOW) best = MotionTrack.LOST_BELOW - 0.01f
        }

        return TrackStep(bestX.toFloat(), bestY.toFloat(), scale, best)
    }

    /** The best match within [radius] of ([cx], [cy]) every [stride] pixels: (score, x, y). */
    private fun sweep(frame: LumaFrame, cx: Int, cy: Int, radius: Int, stride: Int, atScale: Float): Triple<Float, Int, Int> {
        var best = -2f
        var bestX = cx
        var bestY = cy
        var offsetY = -radius
        while (offsetY <= radius) {
            var offsetX = -radius
            while (offsetX <= radius) {
                val score = correlate(frame, cx + offsetX, cy + offsetY, atScale)
                if (score > best) {
                    best = score
                    bestX = cx + offsetX
                    bestY = cy + offsetY
                }
                offsetX += stride
            }
            offsetY += stride
        }
        return Triple(best, bestX, bestY)
    }

    /** ZNCC of the template against the frame patch centered at (x, y). */
    private fun correlate(
        frame: LumaFrame, x: Int, y: Int, scale: Float,
        against: FloatArray = current, againstMean: Float = currentMean, againstNorm: Float = currentNorm
    ): Float {
        val halfW = templateWidth / 2
        val halfH = templateHeight / 2
        if (x - halfW < 0 || y - halfH < 0 ||
            x + halfW >= frame.width || y + halfH >= frame.height
        ) return -2f

        // The frame under the template, sampled at [scale] round (x, y). Between
        // pixels, not rounded to them: rounded, sizes a step apart sampled the very
        // same pixels and scored alike - 1.00 and 1.05 against a test card grown
        // to 1.2 differed by 0.001 - so the size never took a step (28 Oct). At
        // the size it was first selected at, the pixels themselves, as before.
        val patch = patchBuffer
        var patchSum = 0f
        for (ty in 0 until templateHeight) {
            val fy = y + (ty - halfH) * scale
            if (fy < 0f || fy > frame.height - 1f) return -2f
            for (tx in 0 until templateWidth) {
                val fx = x + (tx - halfW) * scale
                if (fx < 0f || fx > frame.width - 1f) return -2f
                val v = if (scale == 1f) frame.pixels[(y + ty - halfH) * frame.width + x + tx - halfW] else {
                    val x0 = fx.toInt(); val y0 = fy.toInt()
                    val x1 = minOf(x0 + 1, frame.width - 1); val y1 = minOf(y0 + 1, frame.height - 1)
                    val ax = fx - x0; val ay = fy - y0
                    val top = frame.pixels[y0 * frame.width + x0] * (1f - ax) + frame.pixels[y0 * frame.width + x1] * ax
                    val bottom = frame.pixels[y1 * frame.width + x0] * (1f - ax) + frame.pixels[y1 * frame.width + x1] * ax
                    top * (1f - ay) + bottom * ay
                }
                patch[ty * templateWidth + tx] = v
                patchSum += v
            }
        }
        val patchMean = patchSum / patch.size

        var dot = 0f
        var patchVar = 0f
        for (i in patch.indices) {
            val p = patch[i] - patchMean
            val t = against[i] - againstMean
            dot += t * p
            patchVar += p * p
        }

        val denominator = againstNorm * sqrt(patchVar)
        // A patch with no variation correlates with nothing. Returning 0 rather than
        // dividing by it stops a blank wall scoring as a perfect match.
        if (denominator < 1e-6f) return 0f
        return (dot / denominator).coerceIn(-1f, 1f)
    }

    /** The frame under the template, one correlation at a time (the tracker runs on one thread). */
    private val patchBuffer = FloatArray(templateWidth * templateHeight)

    // Learned at the size it was found at, so the template stays the shape of
    // the thing in its own coordinates as the thing grows or shrinks.
    private fun blendTemplate(frame: LumaFrame, x: Int, y: Int, atScale: Float) {
        val halfW = templateWidth / 2
        val halfH = templateHeight / 2
        for (ty in 0 until templateHeight) {
            val sy = y + ((ty - halfH) * atScale).roundToInt()
            if (sy < 0 || sy >= frame.height) return
        }
        for (tx in 0 until templateWidth) {
            val sx = x + ((tx - halfW) * atScale).roundToInt()
            if (sx < 0 || sx >= frame.width) return
        }

        for (ty in 0 until templateHeight) {
            val sy = y + ((ty - halfH) * atScale).roundToInt()
            for (tx in 0 until templateWidth) {
                val sx = x + ((tx - halfW) * atScale).roundToInt()
                val i = ty * templateWidth + tx
                val seen = frame.pixels[sy * frame.width + sx]
                val blended = current[i] * (1f - BLEND) + seen * BLEND
                // Anchored to the original: the template may follow the object as it
                // changes, but it is never allowed to wander far from the thing that
                // was actually selected.
                current[i] = blended * (1f - ANCHOR) + original[i] * ANCHOR
            }
        }
        recomputeStats()
    }

    private fun recomputeStats() {
        var sum = 0f
        for (v in current) sum += v
        currentMean = sum / current.size
        var variance = 0f
        for (v in current) {
            val d = v - currentMean
            variance += d * d
        }
        currentNorm = sqrt(variance)
        if (currentNorm < 1e-6f) currentNorm = 1e-6f
    }

    private companion object {
        /** A step either way from the size it was last found at: 3%, which a test card
         *  growing a fifth of a per cent a frame is followed by within a step (5% lagged two). */
        val SCALE_STEPS = floatArrayOf(0.97f, 1.03f)

        /** How much better a new size must match before it is taken. */
        const val SCALE_MARGIN = 0.004f

        /** How far round the winning place each size is scored (see [step]). */
        const val FIT_SLACK = 2
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 4f

        /** How strong a match must be to take a lost thing back. */
        const val REFIND_ABOVE = 0.6f


        /** The share of the level it was held at that a re-find must reach. */
        const val REFIND_SHARE = 0.85f

        /** How fast that level follows the sure matches. */
        const val HELD_FOLLOW = 0.1f

        /** How fast the template follows what it sees. */
        const val BLEND = 0.06f

        /** How strongly it is pulled back toward the original selection. */
        const val ANCHOR = 0.02f

        /** A poor match is not learned from; that is how a tracker eats background. */
        const val TEMPLATE_UPDATE_ABOVE = 0.6f
    }
}
