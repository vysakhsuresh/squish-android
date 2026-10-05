package com.squish.app.media

/**
 * How far each run of a reversed render is fed, apart from the decoding.
 *
 * A reverse is rendered run by run: seek to a keyframe, decode forward to the
 * next one, hand the frames to the encoder last first. Every run but the last
 * is bounded by the keyframe that starts the run after it. The last one has no
 * keyframe after it *inside the window*, and used to be given the end of the
 * track - so reversing a short stretch near the start of a long recording fed
 * and decoded the whole remainder of the file and threw every frame of it away,
 * taking minutes for a few seconds of footage with the card sitting at the
 * sound's share the whole time. It is the run processed first, too, so none of
 * that waste showed as progress.
 *
 * Pure, so it is executed on the JVM (tools/jvm/ReverseRunChecks.kt).
 */
object ReverseRuns {

    /**
     * How many keyframes past the window the last run may be fed to.
     *
     * Not one: the samples come out of the container in decode order, and with
     * an open GOP a frame displaying just before the window's end can be stored
     * after the keyframe that follows it. Stopping at that keyframe would drop
     * the last frame or two of the reversed clip. Two keyframes is one whole
     * run of margin - about a second of ordinary footage - which costs a GOP of
     * decoding against a file that could be an hour long.
     */
    const val TAIL_SYNCS = 2

    /**
     * Where feeding stops for the last run, given the keyframes found at or
     * after the window's end, in order. [Long.MAX_VALUE] - the end of the
     * track - when the file holds fewer than [TAIL_SYNCS] of them, since the
     * file then ends within a run or two of the window anyway and the
     * extractor's own end of stream is the cheaper bound.
     */
    fun tailBoundUs(syncsPastWindow: List<Long>): Long =
        if (syncsPastWindow.size >= TAIL_SYNCS) syncsPastWindow[TAIL_SYNCS - 1] else Long.MAX_VALUE

    /** Where feeding stops for the run that starts at `syncsInWindow[gop]`. */
    fun runEndUs(syncsInWindow: List<Long>, gop: Int, tailBoundUs: Long): Long =
        if (gop + 1 < syncsInWindow.size) syncsInWindow[gop + 1] else tailBoundUs
}
