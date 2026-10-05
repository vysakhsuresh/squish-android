package com.squish.app.timeline

/**
 * Where the things drawn along a clip on the strip belong.
 *
 * The strip draws a clip in *timeline* time - the box is as wide as the clip
 * plays - so anything laid evenly across that width must have its content
 * picked at the moment under it, which on a retimed clip is not the same as
 * dividing the clip's source span evenly.
 *
 * Both canvases in a sound clip got this wrong in opposite directions: the
 * waveform's bars were placed evenly across the drawn width while the peak each
 * one showed was picked evenly in *source* time, and the beat dots twelve lines
 * below went through [Clip.timelineAtSource], which is the true inverse of the
 * curve. So on a song with a speed curve the dots sat where the beats really
 * are and the wave under them did not: on a 20 s song at 1x, 0.5x, 1x the worst
 * bar drew the peak from 4,833 ms of the file where 5,699 ms is heard - 866 ms,
 * seventeen of the 50 ms buckets the waveform is built from.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/StripDrawChecks.kt).
 */
object StripDraw {

    /**
     * The source moment under the centre of bar [bar] of [bars], across the
     * stretch of timeline from [drawnStartMs] to [drawnEndMs] - the part of the
     * clip on screen, not the whole clip. [sourceAt] is the clip's own clock
     * (Clip.sourceAt, through the speed curve).
     */
    fun barSourceMs(
        bar: Int,
        bars: Int,
        drawnStartMs: Long,
        drawnEndMs: Long,
        sourceAt: (Long) -> Long
    ): Long {
        if (bars <= 0) return sourceAt(drawnStartMs)
        val span = (drawnEndMs - drawnStartMs).coerceAtLeast(0L)
        return sourceAt(drawnStartMs + (span * (2L * bar + 1) / (2L * bars)))
    }

    /**
     * Which of [peaks] buckets, over a file [durationMs] long, holds
     * [atSourceMs]. Clamped: a moment exactly at the end lands on the last
     * bucket rather than one past it.
     */
    fun peakIndex(atSourceMs: Long, durationMs: Long, peaks: Int): Int {
        if (peaks <= 0 || durationMs <= 0L) return 0
        val i = (atSourceMs.toDouble() / durationMs * peaks).toInt()
        return i.coerceIn(0, peaks - 1)
    }
}
