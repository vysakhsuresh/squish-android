package com.squish.app.tools

/**
 * Where the in and out handles of a quick trim may go, kept free of Android so
 * it is executed on the JVM (tools/jvm/TrimRulesChecks.kt).
 *
 * Snip and Rip used to set their range on a Material slider over the whole
 * clip: on a twenty-minute file each pixel was three or four seconds, nothing
 * showed which frame a handle sat on, and the handles could be dragged
 * through each other. The strip they sit on now is frames from the footage
 * (ThumbnailExtractor.extractFrames), and a handle lands on a frame boundary,
 * as CapCut's trim does - so what the picture shows is exactly what the
 * export keeps.
 */
object TrimRules {

    /** The least a trim may keep: a handle cannot cross the other, or land on it. */
    const val MIN_KEEP_MS = 100L

    /** The moment under a point [x] pixels into a strip [widthPx] wide, clamped to the clip. */
    fun msAtX(x: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f || durationMs <= 0L) return 0L
        val fraction = (x / widthPx).coerceIn(0f, 1f)
        return (fraction * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /** Where a moment sits along the strip, in pixels. */
    fun xAtMs(ms: Long, widthPx: Float, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (ms.coerceIn(0L, durationMs).toFloat() / durationMs) * widthPx
    }

    /** The nearest frame boundary to [ms]; a frame length of nothing means no grid. */
    fun snapToFrame(ms: Long, frameMs: Long): Long {
        if (frameMs <= 0L) return ms
        return ((ms + frameMs / 2) / frameMs) * frameMs
    }

    /**
     * The in point after a drag to [proposedMs]: on a frame, never before the
     * start, never within [MIN_KEEP_MS] of the out point.
     */
    fun movedStart(proposedMs: Long, endMs: Long, durationMs: Long, frameMs: Long): Long {
        val ceiling = (endMs - MIN_KEEP_MS).coerceAtLeast(0L)
        val snapped = snapToFrame(proposedMs.coerceIn(0L, durationMs), frameMs)
        return snapped.coerceIn(0L, ceiling)
    }

    /**
     * The out point after a drag to [proposedMs]: on a frame, never past the
     * end, never within [MIN_KEEP_MS] of the in point.
     */
    fun movedEnd(proposedMs: Long, startMs: Long, durationMs: Long, frameMs: Long): Long {
        val floor = (startMs + MIN_KEEP_MS).coerceAtMost(durationMs)
        val clamped = proposedMs.coerceIn(0L, durationMs)
        // The clip's end is a boundary in its own right: snapped to the frame
        // grid it fell a frame short, and the last frame could never be kept.
        val snapped = if (clamped == durationMs) durationMs else snapToFrame(clamped, frameMs).coerceAtMost(durationMs)
        return snapped.coerceIn(floor, durationMs)
    }

    /**
     * Where a drag has taken a handle: the moment [travelledPx] along the strip
     * from where the handle stood when the finger landed ([anchorMs]), before
     * the frame grid. Measured from the anchor and the whole travel, never from
     * the handle's last snapped place and the last event's delta: each event of
     * a slow drag is under half a frame wide, so applied one at a time to a
     * handle already on the grid every one of them rounded back to the same
     * frame, and the handle never moved.
     */
    fun draggedTo(anchorMs: Long, travelledPx: Float, widthPx: Float, durationMs: Long): Long =
        msAtX(xAtMs(anchorMs, widthPx, durationMs) + travelledPx, widthPx, durationMs)

    /** A handle nudged by whole frames; the other handle's rule applies to where it lands. */
    fun steppedStart(startMs: Long, frames: Int, endMs: Long, durationMs: Long, frameMs: Long): Long =
        movedStart(startMs + frames * frameMs.coerceAtLeast(1L), endMs, durationMs, frameMs)

    fun steppedEnd(endMs: Long, frames: Int, startMs: Long, durationMs: Long, frameMs: Long): Long =
        movedEnd(endMs + frames * frameMs.coerceAtLeast(1L), startMs, durationMs, frameMs)

    /** How many frames a strip [widthDp] wide shows: one per [TILE_DP], at least [MIN_TILES], at most [MAX_TILES]. */
    fun tileCount(widthDp: Float): Int = (widthDp / TILE_DP).toInt().coerceIn(MIN_TILES, MAX_TILES)

    const val TILE_DP = 44f
    const val MIN_TILES = 4
    const val MAX_TILES = 16
}
