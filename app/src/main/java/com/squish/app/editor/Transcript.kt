package com.squish.app.editor

/**
 * One word of the edit's spoken transcript, placed on the timeline.
 *
 * [startMs] and [endMs] are the edit's own clock, not the line's, so deleting a
 * run of words is a stretch of time the timeline can take out directly.
 */
data class TranscriptWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** The line it came from, so a word can be found again after an edit. */
    val lineId: String,
    /** Whether its moment is known, or only the line it sits in. */
    val timed: Boolean
)

/**
 * The edit, read as words.
 *
 * Auto-captions already land a time for every word ([TextOverlayItem.wordStartsMs],
 * mapped through the shot's clock when they land). Nothing until now showed them
 * to anyone: this turns them into a list that can be read, chosen from and cut.
 *
 * A line typed by hand, or one whose words were retyped, has no timings. Its
 * words are still listed - a transcript with holes in it is more use than no
 * transcript - but they are marked [TranscriptWord.timed] false and share the
 * line's own span, so choosing one chooses the whole line rather than pretending
 * to a precision that is not there.
 */
object Transcript {

    /** Words that are nearly always filler. Kept short on purpose: see [fillerRuns]. */
    val FILLERS = setOf("um", "umm", "uh", "uhh", "erm", "er", "ah", "hmm", "mmm", "mm")

    /**
     * Every word in the edit, in time order.
     *
     * [lines] is the editor's text list; only lines that came from speech are
     * worth reading as a transcript, which is what [spoken] decides.
     */
    fun words(lines: List<Line>): List<TranscriptWord> =
        lines.sortedBy { it.startMs }.flatMap { line -> wordsOf(line) }

    private fun wordsOf(line: Line): List<TranscriptWord> {
        val parts = line.text.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val timed = line.wordStartsMs.size == parts.size
        if (!timed) {
            return parts.map { TranscriptWord(it, line.startMs, line.endMs, line.id, timed = false) }
        }
        return parts.mapIndexed { i, word ->
            val start = line.startMs + line.wordStartsMs[i]
            // A word runs until the next one starts, and the last to the line's
            // end. Nothing else in the data says where a word stops.
            val end = if (i + 1 < parts.size) line.startMs + line.wordStartsMs[i + 1] else line.endMs
            TranscriptWord(word, start, maxOf(end, start), line.id, timed = true)
        }
    }

    /**
     * The stretch a run of chosen words covers, from the first to the last.
     *
     * Chosen words that are not timed drag their whole line in with them, which
     * is the honest reading: without timings the line is the smallest thing we
     * know the position of.
     */
    fun span(words: List<TranscriptWord>): LongRange? {
        if (words.isEmpty()) return null
        return words.minOf { it.startMs }..words.maxOf { it.endMs }
    }

    /**
     * The stretches a filler-word pass would take out: every run of neighbouring
     * filler words, merged, so "um, um" is one cut rather than two.
     *
     * Only words with a time of their own - taking out a line's whole span
     * because it contains an "um" would take the sentence with it.
     */
    fun fillerRuns(words: List<TranscriptWord>): List<LongRange> {
        val runs = mutableListOf<LongRange>()
        var open: LongRange? = null
        for (w in words) {
            val filler = w.timed && isFiller(w.text)
            open = if (filler) {
                if (open == null) w.startMs..w.endMs else open.first..w.endMs
            } else {
                open?.let { runs += it }
                null
            }
        }
        open?.let { runs += it }
        return runs
    }

    /** Punctuation and case are not part of the word: "Um," is still "um". */
    fun isFiller(word: String): Boolean =
        word.trim().trim { !it.isLetter() }.lowercase() in FILLERS

    /**
     * What a line of words becomes when a stretch of the edit is taken out.
     *
     * Null when the line went with it. Text lives in the editor's own list
     * rather than on the timeline, so nothing else moves it: without this, the
     * captions would stay where they were while the footage under them slid.
     *
     * A line with timings loses the words inside the stretch and keeps the
     * rest, which is what makes deleting a word from the transcript take the
     * word off the screen as well as the footage out of the edit. A line
     * without them cannot lose part of itself, so it is trimmed to what is
     * left of its span and keeps all its words.
     */
    fun afterRemoval(line: Line, fromMs: Long, toMs: Long): Line? {
        val from = minOf(fromMs, toMs)
        val to = maxOf(fromMs, toMs)
        val span = to - from
        if (span <= 0L) return line
        if (line.endMs <= from) return line
        if (line.startMs >= to) {
            return line.copy(startMs = line.startMs - span, endMs = line.endMs - span)
        }
        // Wholly inside: it goes.
        if (line.startMs >= from && line.endMs <= to) return null

        val parts = line.text.trim().split(WHITESPACE).filter { it.isNotBlank() }
        val timed = line.wordStartsMs.size == parts.size && parts.isNotEmpty()
        val start = minOf(line.startMs, from)
        val end = if (line.endMs > to) line.endMs - span else from

        if (!timed) {
            val trimmed = line.copy(startMs = start, endMs = maxOf(end, start), wordStartsMs = emptyList())
            return if (trimmed.endMs <= trimmed.startMs) null else trimmed
        }

        val keptWords = mutableListOf<String>()
        val keptStarts = mutableListOf<Long>()
        parts.forEachIndexed { i, word ->
            val at = line.startMs + line.wordStartsMs[i]
            if (at < from) {
                keptWords += word
                keptStarts += at - start
            } else if (at >= to) {
                keptWords += word
                keptStarts += at - span - start
            }
        }
        if (keptWords.isEmpty()) return null
        return line.copy(
            text = keptWords.joinToString(" "),
            startMs = start,
            endMs = maxOf(end, start + 1),
            wordStartsMs = keptStarts
        )
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * What the transcript needs from a line of text. A plain shape rather than
     * the editor's own model, so the reading of a transcript can be executed
     * without the editor around it.
     */
    data class Line(
        val id: String,
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val wordStartsMs: List<Long> = emptyList()
    )
}
