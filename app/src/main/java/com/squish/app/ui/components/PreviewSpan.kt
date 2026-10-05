package com.squish.app.ui.components

/**
 * How long the thing in a [ClipPreview] is, kept pure so it can be executed on
 * the JVM (tools/jvm/PreviewSpanChecks.kt).
 *
 * The caller hands over a length it probed, and everything the preview draws is
 * measured against it: the scrub bar's width, where the playhead sits, the
 * timecode, the kept stretch, the waveform. When the probe came back with
 * nothing - a MediaStore URI the retriever would not answer for, a file still
 * being written - the sum was zero and the position was clamped to zero on
 * every tick, so the picture played while the bar stayed at the start, the
 * timecode read 0:00 throughout and there was no bar to drag at all.
 *
 * The player knows. Once it has prepared the file it reports a duration, and
 * for a single source that is exactly the number the probe failed to get.
 */
object PreviewSpan {

    /**
     * The length to measure the preview against.
     *
     * [probedTotalMs] is the sum of what the caller measured, [sources] how
     * many files are in the playlist and [playerDurationMs] what the player
     * reports - which is `C.TIME_UNSET` (a large negative) before it knows, so
     * the test is for a positive number rather than for any number.
     *
     * The player's answer is only taken for a single source, because for a
     * playlist it is the length of the item playing now and there is no way to
     * sum what was never measured. A merge preview whose sources all probed
     * zero is a caller that has not probed, and this cannot rescue it.
     */
    fun scrubTotalMs(probedTotalMs: Long, sources: Int, playerDurationMs: Long): Long = when {
        probedTotalMs > 0L -> probedTotalMs
        sources == 1 && playerDurationMs > 0L -> playerDurationMs
        else -> 0L
    }
}
