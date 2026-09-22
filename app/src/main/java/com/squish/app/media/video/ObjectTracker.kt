package com.squish.app.media.video

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where the tracked thing was at one moment, in fractions of the frame. */
data class TrackSample(
    val atMs: Long,
    val xFraction: Float,
    val yFraction: Float,
    /** Relative to the size it was first selected at. */
    val scale: Float = 1f,
    /** -1..1. Below [MotionTrack.LOST_BELOW] the tracker had lost it. */
    val confidence: Float = 1f
)

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
 * Zero-mean normalised cross-correlation rather than the absolute-difference match
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

    init {
        recomputeStats()
    }

    /**
     * Searches around [lastX], [lastY] for the template.
     *
     * Coarse then fine: every second offset first, then the immediate neighbours of
     * the winner. A full search costs four times as much and finds the same place.
     */
    fun step(frame: LumaFrame, lastX: Float, lastY: Float, searchRadius: Int): TrackStep {
        val cx = lastX.roundToInt()
        val cy = lastY.roundToInt()

        var best = -2f
        var bestX = cx
        var bestY = cy

        var offsetY = -searchRadius
        while (offsetY <= searchRadius) {
            var offsetX = -searchRadius
            while (offsetX <= searchRadius) {
                val score = correlate(frame, cx + offsetX, cy + offsetY, 1f)
                if (score > best) {
                    best = score
                    bestX = cx + offsetX
                    bestY = cy + offsetY
                }
                offsetX += 2
            }
            offsetY += 2
        }

        for (dy in -1..1) {
            for (dx in -1..1) {
                val score = correlate(frame, bestX + dx, bestY + dy, 1f)
                if (score > best) {
                    best = score
                    bestX += dx
                    bestY += dy
                }
            }
        }

        // Scale is checked only at the winning position: three more template
        // comparisons, rather than tripling the whole search.
        var bestScale = 1f
        for (candidate in SCALES) {
            val score = correlate(frame, bestX, bestY, candidate)
            if (score > best) {
                best = score
                bestScale = candidate
            }
        }

        if (best >= TEMPLATE_UPDATE_ABOVE) blendTemplate(frame, bestX, bestY)

        return TrackStep(bestX.toFloat(), bestY.toFloat(), bestScale, best)
    }

    /** ZNCC of the template against the frame patch centred at (x, y). */
    private fun correlate(frame: LumaFrame, x: Int, y: Int, scale: Float): Float {
        val halfW = templateWidth / 2
        val halfH = templateHeight / 2
        if (x - halfW < 0 || y - halfH < 0 ||
            x + halfW >= frame.width || y + halfH >= frame.height
        ) return -2f

        var patchSum = 0f
        var count = 0
        for (ty in 0 until templateHeight) {
            val sy = y - halfH + ((ty - halfH) * scale).roundToInt() + halfH
            if (sy < 0 || sy >= frame.height) return -2f
            for (tx in 0 until templateWidth) {
                val sx = x - halfW + ((tx - halfW) * scale).roundToInt() + halfW
                if (sx < 0 || sx >= frame.width) return -2f
                patchSum += frame.pixels[sy * frame.width + sx]
                count++
            }
        }
        if (count == 0) return -2f
        val patchMean = patchSum / count

        var dot = 0f
        var patchVar = 0f
        for (ty in 0 until templateHeight) {
            val sy = y - halfH + ((ty - halfH) * scale).roundToInt() + halfH
            for (tx in 0 until templateWidth) {
                val sx = x - halfW + ((tx - halfW) * scale).roundToInt() + halfW
                val p = frame.pixels[sy * frame.width + sx] - patchMean
                val t = current[ty * templateWidth + tx] - currentMean
                dot += t * p
                patchVar += p * p
            }
        }

        val denominator = currentNorm * sqrt(patchVar)
        // A patch with no variation correlates with nothing. Returning 0 rather than
        // dividing by it stops a blank wall scoring as a perfect match.
        if (denominator < 1e-6f) return 0f
        return (dot / denominator).coerceIn(-1f, 1f)
    }

    private fun blendTemplate(frame: LumaFrame, x: Int, y: Int) {
        val halfW = templateWidth / 2
        val halfH = templateHeight / 2
        if (x - halfW < 0 || y - halfH < 0 ||
            x + halfW >= frame.width || y + halfH >= frame.height
        ) return

        for (ty in 0 until templateHeight) {
            val sy = y - halfH + ty
            for (tx in 0 until templateWidth) {
                val sx = x - halfW + tx
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
        val SCALES = floatArrayOf(0.94f, 1.06f)

        /** How fast the template follows what it sees. */
        const val BLEND = 0.06f

        /** How strongly it is pulled back toward the original selection. */
        const val ANCHOR = 0.02f

        /** A poor match is not learned from; that is how a tracker eats background. */
        const val TEMPLATE_UPDATE_ABOVE = 0.6f
    }
}
