package com.squish.app.media.audio

/**
 * Auto-captions' lines heard by Whisper a window at a time rather than a line
 * at a time (EDGE_PLAN E1), kept free of the framework so
 * tools/jvm/WhisperWindowsChecks.kt runs it.
 *
 * Whisper reads its audio in 30-second windows and pads a shorter call out to
 * one, so a two-second line cost as much as thirty seconds of speech: on the
 * moto (27 Oct) 109 s of English in 57 lines was at line 14 after seven
 * minutes, where the whole file in one call took 109 s. Neighbouring lines of
 * one clip are now heard together, up to [MAX_WINDOW_MS], and each word is
 * handed back to the line it was said in.
 */
object WhisperWindows {

    /** The longest stretch heard in one call: under Whisper's 30 s window, with room for its edges. */
    const val MAX_WINDOW_MS = 28_000L

    /** How far outside every line a word may fall and still go to the nearest one; further, it was not in a line at all. */
    const val NEAREST_MS = 1_000L

    /**
     * [spans] (start, end) in order, grouped into windows of consecutive
     * spans whose first start and last end are at most [maxMs] apart. A span
     * longer than [maxMs] is a window on its own. Indices into [spans].
     */
    fun group(spans: List<LongRange>, maxMs: Long = MAX_WINDOW_MS): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var start = 0L
        spans.forEachIndexed { i, s ->
            if (current.isEmpty()) { current += i; start = s.first; return@forEachIndexed }
            if (s.last - start <= maxMs) current += i
            else { out += current; current = arrayListOf(i); start = s.first }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * Whether a line handed no words still lands, as a card to type into.
     * Only when it was not heard by Whisper - the system recognizer, which
     * hears a line alone and misses speech, or a Whisper call that failed and
     * heard nothing at all - and the run found words somewhere - then an empty line is
     * more likely speech it missed than not speech at all. Whisper hears the
     * line inside its window, so finding nothing there means nothing was
     * said: on the moto (27 Oct) that left a blank 0.4 s card at the end of
     * the 109 s clip, "Empty text" on the strip and nothing on the picture.
     */
    fun keepsWordless(heardByWhisper: Boolean, anyWords: Boolean): Boolean = anyWords && !heardByWhisper

    /**
     * Each of [words] to the span it was said in - the one its middle falls
     * inside, else the nearest within [NEAREST_MS] - in order; a word further
     * from every span than that is dropped. One list per span.
     */
    fun assign(words: List<WhisperText.Word>, spans: List<LongRange>): List<List<WhisperText.Word>> {
        val out = List(spans.size) { ArrayList<WhisperText.Word>() }
        for (w in words) {
            val mid = (w.startMs + w.endMs) / 2
            var best = -1
            var bestGap = Long.MAX_VALUE
            spans.forEachIndexed { i, s ->
                val gap = when {
                    mid < s.first -> s.first - mid
                    mid > s.last -> mid - s.last
                    else -> 0L
                }
                if (gap < bestGap) { bestGap = gap; best = i }
            }
            if (best >= 0 && bestGap <= NEAREST_MS) out[best] += w
        }
        return out
    }
}
