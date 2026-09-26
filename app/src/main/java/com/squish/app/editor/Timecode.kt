package com.squish.app.editor

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

    /** Signed offset for the sync readout: "+120 ms (4f)" / "-83 ms (-2f)". */
    fun formatOffset(ms: Long, fps: Float): String {
        val frames = if (fps > 0f) (ms / frameDurationMs(fps).toDouble()).roundToLong() else 0L
        val sign = if (ms > 0) "+" else ""
        return "$sign$ms ms ($sign${frames}f)"
    }
}
