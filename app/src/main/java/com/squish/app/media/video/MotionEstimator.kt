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

        var bestScore = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        var worstScore = 0f

        // Every other pixel: at this size the neighbours are nearly the same value,
        // so the full grid costs four times as much for no extra accuracy.
        val step = 2
        val scores = HashMap<Long, Float>()

        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                var sum = 0f
                var count = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    val rowCur = y * w
                    val rowPrev = (y + dy) * w
                    while (x < x1) {
                        sum += abs(current.pixels[rowCur + x] - previous.pixels[rowPrev + x + dx])
                        count++
                        x += step
                    }
                    y += step
                }
                val score = if (count > 0) sum / count else Float.MAX_VALUE
                scores[key(dx, dy)] = score
                if (score < bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
                if (score > worstScore) worstScore = score
            }
        }

        // A frame that matches every offer equally well carries no information -
        // a flat wall, a white flash, a cut. Correcting on that estimate invents
        // movement that was never there.
        val contrast = if (worstScore > 1e-6f) (worstScore - bestScore) / worstScore else 0f
        if (contrast < 0.04f) return Match(0f, 0f, 0f)

        val refinedX = refine(
            scores[key(bestDx - 1, bestDy)],
            bestScore,
            scores[key(bestDx + 1, bestDy)]
        )
        val refinedY = refine(
            scores[key(bestDx, bestDy - 1)],
            bestScore,
            scores[key(bestDx, bestDy + 1)]
        )

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

    private fun key(dx: Int, dy: Int): Long = (dx.toLong() shl 32) or (dy.toLong() and 0xFFFFFFFFL)

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
