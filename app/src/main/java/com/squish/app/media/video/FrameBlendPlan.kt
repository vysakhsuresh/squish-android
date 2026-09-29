package com.squish.app.media.video

/**
 * Where the blended frames go between two frames of slowed footage - the
 * arithmetic of FrameBlendEffect, kept apart from the GL so it can be executed
 * (tools/jvm/FrameBlendChecks.kt).
 *
 * Slowing footage stretches its timestamps and invents no frames (see
 * SlowMotion), so at a quarter speed a 30 fps shot arrives as a frame every
 * 133 ms. Blending puts a frame every [intervalUs] between each pair, each one
 * a mix of the two by how far it sits between them: motion blur in place of
 * stepping, the trade every editor's "frame blending" makes.
 */
object FrameBlendPlan {

    /** One blended frame: its timestamp and how much of the later frame is in it. */
    data class Blend(val timeUs: Long, val mix: Float)

    /**
     * The frames to make between [previousUs] and [currentUs], at most [maxCount]
     * of them: one at each multiple of [intervalUs] after the earlier frame that
     * falls strictly between the two, never the frames themselves. A gap no
     * longer than the interval gets none - the footage already has a frame
     * there, and a blend would only soften it.
     *
     * When the gap wants more than [maxCount], the ones nearest the earlier
     * frame are kept and the rest given up: the effect owns a fixed handful of
     * textures per input, and a gap that long is a slideshow anyway.
     */
    fun between(previousUs: Long, currentUs: Long, intervalUs: Long, maxCount: Int): List<Blend> {
        if (intervalUs <= 0L || maxCount <= 0) return emptyList()
        val gap = currentUs - previousUs
        // Not a frame's worth past the interval: rounding in the timestamps
        // must not make a 33.4 ms gap at 30 fps sprout a frame.
        if (gap <= intervalUs + intervalUs / 4) return emptyList()
        val out = ArrayList<Blend>()
        var at = previousUs + intervalUs
        while (at < currentUs && out.size < maxCount) {
            // A blend a sliver before the real frame is that frame again.
            if (currentUs - at < intervalUs / 4) break
            out.add(Blend(at, ((at - previousUs).toDouble() / gap).toFloat().coerceIn(0f, 1f)))
            at += intervalUs
        }
        return out
    }

    /** The interval a file at [frameRate] wants its frames at. */
    fun intervalUs(frameRate: Int): Long = (1_000_000L / frameRate.coerceAtLeast(1)).coerceAtLeast(1L)
}
