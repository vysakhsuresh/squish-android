package com.squish.app.editor

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Frame-accurate time display. Editors think in frames, not in "0:03" - a cut that
 * is "about 3 seconds in" is useless when you are matching a beat or a lip movement.
 */
object Timecode {

    fun frameDurationMs(fps: Float): Long =
        if (fps > 0f) (1000f / fps).roundToLong().coerceAtLeast(1L) else 33L

    fun frameAt(ms: Long, fps: Float): Long =
        if (fps > 0f) (ms / 1000.0 * fps).toLong() else 0L

    /** Snaps a millisecond position onto the nearest exact frame boundary. */
    fun quantize(ms: Long, fps: Float): Long {
        if (fps <= 0f) return ms
        val frame = (ms / 1000.0 * fps).roundToLong()
        return (frame * 1000.0 / fps).roundToLong()
    }

    /**
     * Where the transport's frame buttons put the playhead: [frames] frames on
     * from the one showing at [atMs].
     *
     * A player seeked exactly shows the first frame at or after the position
     * (see PreviewRules.END_BACKOFF_MS), so frame n shows for any position in
     * (start of n-1, start of n], and the step lands on the whole millisecond at
     * or just before frame n's start. It used to add a frame's length rounded
     * to whole milliseconds - 33 for 33.37 - which drifted a third of a
     * millisecond a press, and at 60fps every fiftieth press skipped a frame.
     *
     * Frames are the shot's own when the playhead is on one: the grid is laid in
     * the file's time from [sourceAt] and brought back through [timelineAt], so
     * a trimmed shot, a shot that starts mid-frame on the timeline and a retimed
     * one all step one of their own frames at a time. [shotStartMs] and
     * [shotEndMs] bound the shot on the timeline; past its last frame the step
     * is the next shot's first, and before its first it goes back, on a plain
     * grid and never short of the shot's start, into whatever is before it.
     * With no shot, the grid starts at zero.
     */
    fun frameStep(
        atMs: Long,
        frames: Int,
        fps: Float,
        shotStartMs: Long? = null,
        shotEndMs: Long? = null,
        sourceAt: (Long) -> Long = { it },
        timelineAt: (Long) -> Long = { it }
    ): Long {
        if (frames == 0) return atMs
        val period = 1000.0 / (if (fps > 0f) fps else DEFAULT_FPS)
        fun showing(ms: Long): Long = ceil(ms / period - EPSILON).toLong()
        fun startOf(index: Long): Long = floor(index * period + EPSILON).toLong()
        val plain = startOf(showing(atMs) + frames)
        if (shotStartMs == null || shotEndMs == null || shotEndMs <= shotStartMs) return plain

        val wanted = showing(sourceAt(atMs)) + frames
        val target = startOf(wanted)
        val previous = startOf(wanted - 1)
        if (target < sourceAt(shotStartMs)) return minOf(plain, shotStartMs - 1)
        if (target >= sourceAt(shotEndMs)) return if (frames > 0) shotEndMs else plain
        // The speed curve rounds to whole milliseconds either way; walk the last
        // few so the position asked for is inside the frame wanted.
        var t = timelineAt(target).coerceIn(shotStartMs, shotEndMs - 1)
        var guard = 0
        while (t > shotStartMs && sourceAt(t) > target && guard++ < WALK_LIMIT) t--
        while (t < shotEndMs - 1 && sourceAt(t) <= previous && guard++ < WALK_LIMIT) t++
        val onward = if (frames > 0) t > atMs else t < atMs
        return if (onward) t else plain
    }

    private const val EPSILON = 1e-6
    private const val DEFAULT_FPS = 30f
    /** More than a frame's worth of timeline milliseconds at any speed a clip can have. */
    private const val WALK_LIMIT = 64

    /**
     * "1:04.320" - millisecond precision, the minimum useful for sync work - and
     * "2:35:26.714" once there are hours. Without the hours a film read as
     * "155:26.714", which nobody reads as two and a half hours.
     */
    fun format(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        return if (hours > 0) "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
        else "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    /** "1:04.320 · f1929" */
    fun formatWithFrame(ms: Long, fps: Float): String = "${format(ms)} · f${frameAt(ms, fps)}"

    /**
     * "4:07" - minutes and seconds, the resolution anyone waiting cares about.
     *
     * For how long is left of a render, which is said in two places at once:
     * the progress card on screen and the notification behind it. They each had
     * their own copy of this, which is two chances to drift and a user watching
     * both of them at the same time.
     */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Signed offset for the sync readout: "+120 ms (4f)" / "-83 ms (-2f)". */
    fun formatOffset(ms: Long, fps: Float): String {
        val frames = if (fps > 0f) (ms / frameDurationMs(fps).toDouble()).roundToLong() else 0L
        val sign = if (ms > 0) "+" else ""
        return "$sign$ms ms ($sign${frames}f)"
    }
}
