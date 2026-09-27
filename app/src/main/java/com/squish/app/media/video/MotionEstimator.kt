package com.squish.app.media.video

import kotlin.math.abs
import kotlin.math.roundToInt

/** One frame reduced to luminance, small, for motion estimation. */
class LumaFrame(val width: Int, val height: Int, val pixels: FloatArray)

/** How the camera moved between two frames, in units of the analysis frame. */
data class FrameMotion(
    val dx: Float,
    val dy: Float,
    val rotationDegrees: Float,
    /** 0..1. Low means the estimate is a guess - a cut, a flash, a blur. */
    val confidence: Float
) {
    companion object {
        /** Genuinely no movement, and we are sure of it. */
        val Still = FrameMotion(0f, 0f, 0f, 1f)

        /**
         * We could not tell. Reported as zero motion so nothing is invented, and
         * zero confidence so the smoother knows not to believe it - a flat wall, a
         * white flash, a cut. Returning [Still] here would have been a lie with
         * full confidence attached.
         */
        val Unknown = FrameMotion(0f, 0f, 0f, 0f)
    }
}

/**
 * Estimates global camera motion between two frames by matching one against the
 * other at a range of offsets and taking the best fit.
 *
 * Pure arithmetic on a small grayscale grid, deliberately. Proper optical flow
 * wants OpenCV, which is a 30 MB native dependency; for the job at hand - undoing
 * handheld shake, which is a whole-frame movement - a global match is the right
 * model anyway. Tracking individual features would mostly be a more expensive way
 * to compute the same number.
 *
 * Rotation comes from matching the two halves of the frame separately: if the left
 * half drifts down while the right drifts up, the camera rolled. It is noisier than
 * the translation estimate, so it is damped before use.
 */
object MotionEstimator {

    /** Search radius in analysis pixels. At 96 wide this is 8% of the frame. */
    private const val SEARCH_RADIUS = 8

    /** How far round the coarse winner the fine pass looks. */
    private const val FINE_RADIUS = 2

    /** Rows and columns skipped at the border, where a shifted frame has no data. */
    private const val MARGIN = SEARCH_RADIUS + 1

    fun estimate(previous: LumaFrame, current: LumaFrame): FrameMotion {
        if (previous.width != current.width || previous.height != current.height) return FrameMotion.Unknown
        if (current.width < MARGIN * 3 || current.height < MARGIN * 3) return FrameMotion.Unknown

        val full = search(previous, current, 0, current.width)
        if (full.confidence <= 0f) return FrameMotion.Unknown

        // Two halves, for roll. Each is searched on its own, and the difference in
        // their vertical drift over the distance between them is the angle.
        val mid = current.width / 2
        val left = search(previous, current, 0, mid)
        val right = search(previous, current, mid, current.width)

        val rotation = if (left.confidence > 0.35f && right.confidence > 0.35f) {
            val lever = (mid / 2f).coerceAtLeast(1f) * 2f
            Math.toDegrees(((right.dy - left.dy) / lever).toDouble()).toFloat()
        } else 0f

        return FrameMotion(
            dx = full.dx,
            dy = full.dy,
            // Damped: a noisy roll estimate wobbling the horizon looks far worse
            // than simply not correcting roll at all.
            rotationDegrees = rotation.coerceIn(-2f, 2f) * 0.6f,
            confidence = full.confidence
        )
    }

    private class Match(val dx: Float, val dy: Float, val confidence: Float)

    /**
     * Exhaustive search for the offset that best aligns [current] onto [previous]
     * over a column range, refined to sub-pixel by fitting a parabola through the
     * best score and its two neighbours.
     */
    private fun search(previous: LumaFrame, current: LumaFrame, fromX: Int, toX: Int): Match {
        val w = current.width
        val h = current.height
        val x0 = maxOf(fromX + MARGIN, MARGIN)
        val x1 = minOf(toX - MARGIN, w - MARGIN)
        val y0 = MARGIN
        val y1 = h - MARGIN
        if (x1 <= x0 || y1 <= y0) return Match(0f, 0f, 0f)

        val cur = current.pixels
        val prev = previous.pixels

        /** Mean absolute difference at one offset, sampling every [step]th pixel. */
        fun sad(dx: Int, dy: Int, step: Int): Float {
            var sum = 0f
            var count = 0
            var y = y0
            while (y < y1) {
                val rowCur = y * w
                val rowPrev = (y + dy) * w + dx
                var x = x0
                while (x < x1) {
                    sum += abs(cur[rowCur + x] - prev[rowPrev + x])
                    count++
                    x += step
                }
                y += step
            }
            return if (count > 0) sum / count else Float.MAX_VALUE
        }

        // Coarse to fine. Every offset in the window was scored at full density,
        // which was 289 passes over the frame and nearly all the time Stabilize
        // took. Now the window is scanned sparsely at every other offset, then the
        // best of those is refined over its neighbours at full density. Shake is
        // a smooth, single-minimum surface at this scale, so the coarse pass lands
        // in the right basin and the answer is the same, about ten times sooner.
        var bestScore = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        var worstScore = 0f
        var dy = -SEARCH_RADIUS
        while (dy <= SEARCH_RADIUS) {
            var dx = -SEARCH_RADIUS
            while (dx <= SEARCH_RADIUS) {
                val score = sad(dx, dy, 4)
                if (score < bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
                if (score > worstScore) worstScore = score
                dx += 2
            }
            dy += 2
        }

        // Fine: the 5x5 around the coarse winner, densely - which also covers the
        // four neighbours the sub-pixel fit below needs.
        val span = 2 * FINE_RADIUS + 1
        val fine = FloatArray(span * span) { Float.NaN }
        val cx = bestDx
        val cy = bestDy
        bestScore = Float.MAX_VALUE
        for (oy in -FINE_RADIUS..FINE_RADIUS) {
            for (ox in -FINE_RADIUS..FINE_RADIUS) {
                val dxx = cx + ox
                val dyy = cy + oy
                if (abs(dxx) > SEARCH_RADIUS || abs(dyy) > SEARCH_RADIUS) continue
                val score = sad(dxx, dyy, 2)
                fine[(oy + FINE_RADIUS) * span + (ox + FINE_RADIUS)] = score
                if (score < bestScore) {
                    bestScore = score
                    bestDx = dxx
                    bestDy = dyy
                }
            }
        }
        fun fineAt(dx: Int, dy: Int): Float? {
            val ox = dx - cx + FINE_RADIUS
            val oy = dy - cy + FINE_RADIUS
            if (ox !in 0 until span || oy !in 0 until span) return null
            return fine[oy * span + ox].takeIf { !it.isNaN() }
        }
        // The coarse pass sampled more sparsely, so its worst is scaled to the fine
        // pass's units by rescoring that one offset would cost more than it tells.
        worstScore = maxOf(worstScore, bestScore)

        // A frame that matches every offer equally well carries no information -
        // a flat wall, a white flash, a cut. Correcting on that estimate invents
        // movement that was never there.
        val contrast = if (worstScore > 1e-6f) (worstScore - bestScore) / worstScore else 0f
        if (contrast < 0.04f) return Match(0f, 0f, 0f)

        val refinedX = refine(fineAt(bestDx - 1, bestDy), bestScore, fineAt(bestDx + 1, bestDy))
        val refinedY = refine(fineAt(bestDx, bestDy - 1), bestScore, fineAt(bestDx, bestDy + 1))

        // An estimate pinned at the edge of the search window means the real motion
        // was larger than the window, so the number is a floor rather than a value.
        val pinned = abs(bestDx) == SEARCH_RADIUS || abs(bestDy) == SEARCH_RADIUS
        val confidence = (contrast * 6f).coerceIn(0f, 1f) * (if (pinned) 0.4f else 1f)

        // Negated on the way out. The search asks "where in the previous frame did
        // this pixel come from", which is the opposite of "where did the picture go":
        // content that moved three pixels right is found three pixels left of where
        // it now is. Returning the raw search offset would stabilize every clip in
        // exactly the wrong direction - and look, convincingly, like it was working.
        return Match(-(bestDx + refinedX), -(bestDy + refinedY), confidence)
    }

    /** Parabola through three samples; its vertex is the sub-pixel minimum. */
    private fun refine(before: Float?, center: Float, after: Float?): Float {
        if (before == null || after == null) return 0f
        val denominator = before - 2f * center + after
        if (abs(denominator) < 1e-6f) return 0f
        return (0.5f * (before - after) / denominator).coerceIn(-0.5f, 0.5f)
    }


    /** Nearest-neighbor downsample of a packed ARGB frame into a luma grid. */
    fun toLuma(argb: IntArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): LumaFrame {
        val out = FloatArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val sy = (y.toLong() * srcHeight / dstHeight).toInt().coerceIn(0, srcHeight - 1)
            for (x in 0 until dstWidth) {
                val sx = (x.toLong() * srcWidth / dstWidth).toInt().coerceIn(0, srcWidth - 1)
                val c = argb[sy * srcWidth + sx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[y * dstWidth + x] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            }
        }
        return LumaFrame(dstWidth, dstHeight, out)
    }

    /** The analysis grid. Small enough to be quick, wide enough to see the shake. */
    fun analysisSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 96 to 54
        val width = 96
        val height = (width.toFloat() * sourceHeight / sourceWidth).roundToInt().coerceAtLeast(32)
        return width to height
    }
}
