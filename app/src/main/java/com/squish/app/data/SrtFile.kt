package com.squish.app.data

/** One caption, independent of how the editor happens to store it. */
data class SrtCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * SubRip (.srt) reading and writing.
 *
 * This is the escape hatch, and it matters more than it looks. On-device speech
 * recognition is not available everywhere and is not equally good in every
 * language, so being able to bring a transcript in from whatever tool you trust -
 * and take one out to whatever tool you prefer - is the difference between captions
 * being a feature and captions being a dead end.
 *
 * Deliberately forgiving on the way in. Real .srt files in the wild have BOMs, CRLF
 * line endings, dots instead of commas before the milliseconds, missing indices and
 * blank lines in odd places. A parser that rejects those is technically correct and
 * practically useless.
 */
object SrtFile {

    /**
     * The byte-order mark an SRT saved by Notepad, or exported by half the
     * caption tools, starts with.
     *
     * Belt and braces, and measured as such: [parse] only recognises a line
     * with `-->` in it and a line of digits, and a mark stuck to the front of
     * the first index simply stops that line being an index - which it already
     * tolerates. Taking this strip out changes none of the suite's answers,
     * including the file with no indices at all. It stays because the intent is
     * worth stating, not because anything leans on it.
     *
     * Written as a number rather than as a character, because written out it is
     * three bytes of U+FEFF sitting in the middle of a Kotlin source file -
     * which Android lint reports, and which any tool that splits a file on a
     * BOM gets wrong.
     */
    private val BOM = 0xFEFF.toChar().toString()

    fun format(cues: List<SrtCue>): String = buildString {
        cues.sortedBy { it.startMs }
            .mapNotNull { cue -> carried(cue.text)?.let { cue to it } }
            .forEachIndexed { index, (cue, text) ->
                append(index + 1).append('\n')
                append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append('\n')
                append(text).append('\n')
                append('\n')
            }
    }

    /**
     * A caption's text as SubRip can carry it, or null when it carries nothing.
     *
     * A blank line *ends a cue* in this format, so there can be none inside
     * one. The writer used to trim only the ends, so a line somebody had typed
     * two returns into went out with a blank line in the middle of it - and
     * [parse], correctly, read that as the end of the cue and then dropped
     * every line after it. The two disagreed about what a blank line means and
     * the writer was the one in the wrong: a round trip through a file cannot
     * keep something the file cannot hold.
     *
     * A cue left with nothing to say is not written at all, because [parse]
     * would not produce one either - which is what makes the round trip exact
     * rather than nearly.
     */
    private fun carried(text: String): String? = text
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }

    fun parse(raw: String): List<SrtCue> {
        val text = raw.removePrefix(BOM).replace("\r\n", "\n").replace('\r', '\n')
        val cues = mutableListOf<SrtCue>()

        var start = -1L
        var end = -1L
        val body = StringBuilder()

        fun flush() {
            if (start >= 0 && body.isNotBlank()) {
                cues.add(SrtCue(start, end.coerceAtLeast(start), body.toString().trim()))
            }
            start = -1L
            end = -1L
            body.setLength(0)
        }

        val lines = text.split('\n')
        lines.forEachIndexed { i, line ->
            val trimmed = line.trim()
            val arrow = timingIn(trimmed)

            // A bare number is an index only when a timing line follows it
            // *immediately*. That one-line lookahead is what separates the index of
            // the next cue from a caption whose text happens to be "42" - and it is
            // also what handles files that leave no blank line between cues, where
            // the index would otherwise be swallowed into the previous caption.
            val isIndex = arrow == null &&
                trimmed.toIntOrNull() != null &&
                i + 1 < lines.size &&
                timingIn(lines[i + 1].trim()) != null

            when {
                arrow != null -> {
                    // A new timing line means the previous cue is finished, whether
                    // or not the file bothered to leave a blank line between them.
                    flush()
                    start = arrow.first
                    end = arrow.second
                }
                isIndex -> flush()
                trimmed.isEmpty() -> flush()
                start >= 0 -> {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(trimmed)
                }
            }
        }
        flush()
        return cues.filter { it.endMs > it.startMs }
    }

    /**
     * The two moments of a timing line, or null if this is not one.
     *
     * An arrow is not enough. A caption can say "he said --> go", and a line
     * taken for a timing line on the arrow alone ended the cue it was part of
     * and then set a start of -1, so the caption and everything after it in
     * that cue were dropped without a word. Both sides have to parse.
     */
    private fun timingIn(line: String): Pair<Long, Long>? {
        val m = ARROW.find(line) ?: return null
        val from = parseTimestamp(m.groupValues[1])
        val to = parseTimestamp(m.groupValues[2])
        return if (from >= 0 && to >= 0) from to to else null
    }

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        // Against Locale.ROOT, as PolishRules formats its numbers: `format`
        // without one takes Locale.getDefault(FORMAT), and %02d then emits the
        // locale's own digits. On a phone set to Arabic, Persian, Burmese,
        // Bengali or Nepali, "Export subtitles" wrote Eastern Arabic-Indic or
        // Devanagari numerals into the timing lines - which no subtitle tool
        // can read, this one included: STAMP matches \d, which in Java is
        // [0-9] without UNICODE_CHARACTER_CLASS, so importing the app's own
        // export found no cues and reported the file unreadable.
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun parseTimestamp(value: String): Long {
        val m = STAMP.find(value.trim()) ?: return -1L
        val hours = m.groupValues[1].toLongOrNull() ?: 0L
        val minutes = m.groupValues[2].toLongOrNull() ?: 0L
        val seconds = m.groupValues[3].toLongOrNull() ?: 0L
        // Files written by hand often give one or two digits here, so a bare "5"
        // means 500 ms rather than 5.
        val fraction = m.groupValues[4]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100
            2 -> (fraction.toLongOrNull() ?: 0L) * 10
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1000 + millis
    }

    /** Hours are optional in the wild; milliseconds may use a dot or a comma. */
    private val STAMP = Regex("""(?:(\d+):)?(\d+):(\d+)(?:[,.](\d+))?""")
    private val ARROW = Regex("""^(.+?)\s*-->\s*(.+?)$""")
}
