package com.squish.app.timeline

/**
 * Remove silences: which parts of a shot to keep, given where its sound has
 * someone talking. A pause shorter than [MIN_SILENCE_MS] is breath and stays;
 * each kept stretch keeps [PAD_MS] either side of its words so no syllable is
 * clipped. Executed on the JVM (tools/jvm/SilenceChecks.kt).
 */
object SilenceRules {

    const val MIN_SILENCE_MS = 700L
    const val PAD_MS = 150L

    /**
     * The windows of the file between [inMs] and [outMs] worth keeping, given the
     * [speech] found in it (file milliseconds). Empty when there is no speech at
     * all in the window - then there is nothing to go by, and the shot is left.
     */
    fun keptWindows(
        speech: List<LongRange>,
        inMs: Long,
        outMs: Long,
        minSilenceMs: Long = MIN_SILENCE_MS,
        padMs: Long = PAD_MS
    ): List<LongRange> {
        val padded = speech
            .map { (it.first - padMs).coerceAtLeast(inMs)..(it.last + padMs).coerceAtMost(outMs) }
            .filter { it.last > it.first }
            .sortedBy { it.first }
        if (padded.isEmpty()) return emptyList()
        val out = ArrayList<LongRange>()
        for (r in padded) {
            val last = out.lastOrNull()
            if (last != null && r.first - last.last < minSilenceMs) out[out.size - 1] = last.first..maxOf(last.last, r.last)
            else out += r
        }
        // Silence at the head or the tail shorter than a real pause is kept too.
        if (out.first().first - inMs < minSilenceMs) out[0] = inMs..out[0].last
        if (outMs - out.last().last < minSilenceMs) out[out.size - 1] = out.last().first..outMs
        return out
    }

    /** How much a cut down to [kept] takes out of a window [inMs]..[outMs], in file milliseconds. */
    fun removedMs(kept: List<LongRange>, inMs: Long, outMs: Long): Long =
        (outMs - inMs) - kept.sumOf { it.last - it.first }

    /**
     * The windows a cut will actually keep: [kept] held inside [inMs]..[outMs]
     * and anything shorter than [minKeptMs] dropped, because the timeline
     * cannot hold a piece shorter than a clip's minimum (MIN_CLIP_MS) and
     * withSilencesRemoved drops it.
     *
     * The count the card reports and the edit underneath both have to come from
     * this one list. They used to differ: the card counted the unfiltered
     * windows, so it was always at least as small as the truth, and where every
     * window fell under the minimum - a shot trimmed to start just after the
     * talking stops leaves one 160 ms window, since the pad is clamped up to
     * the in-point - it said it had cut fifteen seconds of pauses while the
     * edit changed nothing at all and filed an undo step for it.
     */
    fun survivingWindows(
        kept: List<LongRange>,
        inMs: Long,
        outMs: Long,
        minKeptMs: Long
    ): List<LongRange> = kept
        .map { maxOf(it.first, inMs)..minOf(it.last, outMs) }
        .filter { it.last - it.first >= minKeptMs }
}
