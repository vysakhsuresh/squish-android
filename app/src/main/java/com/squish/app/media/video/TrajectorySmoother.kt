package com.squish.app.media.video

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** How far a frame must be pushed back to sit on the smoothed path. */
data class Correction(val dx: Float, val dy: Float, val rotationDegrees: Float)

/**
 * Turns per-frame motion into per-frame corrections.
 *
 * The whole idea of stabilization is one observation: a shaky shot is an intended
 * camera path with noise added. Integrate the frame-to-frame motion and you have
 * the path the camera actually took; smooth that path and you have the path it
 * meant to take; the difference is what to undo. A pan survives because it is in
 * both, and only the jitter cancels.
 *
 * The alternative - simply canceling all motion - locks the frame rigid and turns
 * a deliberate pan into a stuttering mess as the correction saturates against the
 * crop. That is the classic way to make stabilization look worse than no
 * stabilization.
 */
object TrajectorySmoother {

    /**
     * Measured rather than guessed: against synthetic frames, a genuine match scores
     * 1.00 and two unrelated frames score 0.31, so the line goes between them. The
     * first draft put it at 0.25, which would have believed pure noise.
     */
    private const val TRUST_FLOOR = 0.5f

    /**
     * @param maxShiftX largest horizontal correction, in analysis pixels
     * @param maxShiftY largest vertical correction, in analysis pixels. Separate from
     *   [maxShiftX] because the crop is a fraction of each axis independently: one
     *   budget measured in widths but applied to heights overshoots by the frame's
     *   aspect ratio - 78% too far on 16:9 - and the crop the UI reports becomes a
     *   number the render does not honour.
     */
    fun smooth(
        motions: List<FrameMotion>,
        windowFrames: Int,
        maxShiftX: Float,
        maxShiftY: Float,
        maxRotationDegrees: Float
    ): List<Correction> {
        if (motions.isEmpty()) return emptyList()

        // Integrate to get the path actually traveled. A low-confidence estimate
        // contributes nothing rather than its guess: a wrong delta does not just
        // spoil its own frame, it shifts the whole path from there on.
        val n = motions.size
        val pathX = FloatArray(n)
        val pathY = FloatArray(n)
        val pathR = FloatArray(n)
        var cx = 0f
        var cy = 0f
        var cr = 0f
        for (i in 0 until n) {
            val m = motions[i]
            val trust = if (m.confidence < TRUST_FLOOR) 0f else 1f
            cx += m.dx * trust
            cy += m.dy * trust
            cr += m.rotationDegrees * trust
            pathX[i] = cx
            pathY[i] = cy
            pathR[i] = cr
        }

        val kernel = gaussian(windowFrames)
        val smoothX = convolve(pathX, kernel)
        val smoothY = convolve(pathY, kernel)
        val smoothR = convolve(pathR, kernel)

        val raw = ArrayList<Correction>(n)
        for (i in 0 until n) {
            raw.add(
                Correction(
                    dx = smoothX[i] - pathX[i],
                    dy = smoothY[i] - pathY[i],
                    rotationDegrees = smoothR[i] - pathR[i]
                )
            )
        }

        // A correction bigger than the crop would pull empty space into frame. Rather
        // than clipping the few frames that overshoot - which puts a kink in an
        // otherwise smooth path, and a kink is exactly the jolt being removed - the
        // whole correction is scaled down until it fits. Less stabilization,
        // uniformly, instead of good stabilization with dents in it.
        var peakX = 0f
        var peakY = 0f
        raw.forEach {
            peakX = max(peakX, abs(it.dx))
            peakY = max(peakY, abs(it.dy))
        }
        // Both axes are scaled by the same factor even though their budgets differ.
        // Squeezing one axis harder than the other would turn the correction vector,
        // so the frame would slide off at an angle to the shake it is canceling.
        val scale = minOf(
            if (peakX > maxShiftX && peakX > 0f) maxShiftX / peakX else 1f,
            if (peakY > maxShiftY && peakY > 0f) maxShiftY / peakY else 1f
        )

        var peakRotation = 0f
        raw.forEach { peakRotation = max(peakRotation, abs(it.rotationDegrees)) }
        val rotationScale =
            if (peakRotation > maxRotationDegrees && peakRotation > 0f) maxRotationDegrees / peakRotation else 1f

        return raw.map {
            Correction(
                dx = it.dx * scale,
                dy = it.dy * scale,
                rotationDegrees = it.rotationDegrees * rotationScale
            )
        }
    }

    /**
     * How much of the frame must be given up to hide the edges, for a given shake.
     * Reported so the crop can be honest rather than a fixed guess: a steady shot
     * should not lose 10% of its frame for nothing.
     */
    fun requiredCrop(corrections: List<Correction>, frameWidth: Float, frameHeight: Float): Float {
        if (corrections.isEmpty() || frameWidth <= 0f || frameHeight <= 0f) return 0f
        var worst = 0f
        corrections.forEach {
            worst = max(worst, max(abs(it.dx) / frameWidth, abs(it.dy) / frameHeight))
        }
        return (worst * 2f).coerceIn(0f, 0.4f)
    }

    private fun gaussian(windowFrames: Int): FloatArray {
        val radius = windowFrames.coerceAtLeast(1)
        val sigma = radius / 2.5f
        val kernel = FloatArray(radius * 2 + 1)
        var total = 0f
        for (i in kernel.indices) {
            val d = (i - radius).toFloat()
            val v = exp(-(d * d) / (2f * sigma * sigma))
            kernel[i] = v
            total += v
        }
        for (i in kernel.indices) kernel[i] /= total
        return kernel
    }

    /** Edges clamp to the end sample, so the path does not sag toward zero there. */
    private fun convolve(values: FloatArray, kernel: FloatArray): FloatArray {
        val radius = kernel.size / 2
        val out = FloatArray(values.size)
        for (i in values.indices) {
            var sum = 0f
            for (k in kernel.indices) {
                val j = (i + k - radius).coerceIn(0, values.size - 1)
                sum += values[j] * kernel[k]
            }
            out[i] = sum
        }
        return out
    }
}
