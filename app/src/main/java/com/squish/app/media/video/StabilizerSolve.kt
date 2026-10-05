package com.squish.app.media.video

import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.Transform
import kotlin.math.roundToInt

/**
 * What the stabilizer measured off a clip: the camera's motion between each
 * pair of frames it read, in the analysis frame's own pixels, and when. The
 * costly half of stabilizing - a decode of the whole shot - kept on the clip,
 * so that the cheap half (the solve below) can run again at a new strength in
 * a moment, and after a restart.
 */
data class StabilizerMeasurement(
    val analysisWidth: Int,
    val analysisHeight: Int,
    /** Source time of each measured motion's later frame; one per motion. */
    val timesMs: List<Long>,
    val motions: List<FrameMotion>
) {
    val isEmpty: Boolean get() = motions.size < 4 || analysisWidth <= 0 || analysisHeight <= 0

    /**
     * The same measurement on a reversed render's clock.
     *
     * Each motion is the move from one frame to the next, so played backwards
     * it is the same move the other way: the list is reversed and every motion
     * negated, and the times are mirrored about [pivotMs] as the solved keys
     * are. Without this a Reverse mirrored the keys and left the measurement
     * alone, so one nudge of the Strength slider re-solved from the old clock
     * and wrote un-mirrored keys over the mirrored ones - a stabilized shot
     * shaking harder reversed than raw, which is the thing the mirroring is
     * there to prevent.
     */
    fun mirroredAt(pivotMs: Long): StabilizerMeasurement = StabilizerMeasurement(
        analysisWidth = analysisWidth,
        analysisHeight = analysisHeight,
        timesMs = timesMs.map { pivotMs - it }.reversed(),
        motions = motions.reversed().map {
            it.copy(dx = -it.dx, dy = -it.dy, rotationDegrees = -it.rotationDegrees)
        }
    )
}

/** The correction solved from a measurement at one strength, and what it costs in frame. */
data class StabilizerSolution(val keyframes: List<Keyframe>, val crop: Float)

/**
 * The stabilizer's arithmetic, apart from the decoding: from a measurement and
 * a strength to the correction track. Pure, so it runs on the JVM
 * (tools/jvm/StabilizerChecks.kt) and so Strength can re-solve instantly.
 */
object StabilizerSolve {

    /**
     * How the strength is read. Stronger smooths over a longer window - holding
     * the frame steadier - and spends more of the frame on the crop that hides
     * what the correction exposes.
     */
    fun windowFrames(strength: Float): Int = (10 + (35 * strength.coerceIn(0f, 1f))).roundToInt().coerceAtLeast(4)

    fun cropBudget(strength: Float): Float = 0.03f + 0.12f * strength.coerceIn(0f, 1f)

    fun solve(measurement: StabilizerMeasurement, strength: Float): StabilizerSolution? {
        if (measurement.isEmpty) return null
        val width = measurement.analysisWidth
        val height = measurement.analysisHeight
        val budget = cropBudget(strength)
        // A correction of d analysis pixels is 2d/size in the half-frame units the
        // transform speaks, so each axis's pixel budget is the crop budget halved
        // against that axis's own length.
        val corrections = TrajectorySmoother.smooth(
            motions = measurement.motions,
            windowFrames = windowFrames(strength),
            maxShiftX = budget / 2f * width,
            maxShiftY = budget / 2f * height,
            maxRotationDegrees = 1.5f
        )
        val crop = TrajectorySmoother.requiredCrop(corrections, width.toFloat(), height.toFloat())
        val scale = 1f + crop
        val keys = corrections.mapIndexed { i, correction ->
            Keyframe(
                atMs = measurement.timesMs.getOrElse(i) { 0L },
                transform = Transform(
                    scale = scale,
                    offsetXFraction = 2f * correction.dx / width,
                    offsetYFraction = 2f * correction.dy / height,
                    rotationDegrees = correction.rotationDegrees
                ),
                // Linear between densely sampled keys. Smoothing already shaped
                // the path; easing each tiny segment again would fight it.
                easing = KeyframeEasing.Linear
            )
        }.sortedBy { it.atMs }
        return StabilizerSolution(keys, crop)
    }
}
