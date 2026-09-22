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

    fun format(cues: List<SrtCue>): String = buildString {
        cues.sortedBy { it.startMs }.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append('\n')
            append(cue.text.trim()).append('\n')
            append('\n')
        }
    }

    fun parse(raw: String): List<SrtCue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
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
            val arrow = ARROW.find(trimmed)

            // A bare number is an index only when a timing line follows it
            // *immediately*. That one-line lookahead is what separates the index of
            // the next cue from a caption whose text happens to be "42" - and it is
            // also what handles files that leave no blank line between cues, where
            // the index would otherwise be swallowed into the previous caption.
            val isIndex = arrow == null &&
                trimmed.toIntOrNull() != null &&
                i + 1 < lines.size &&
                ARROW.containsMatchIn(lines[i + 1].trim())

            when {
                arrow != null -> {
                    // A new timing line means the previous cue is finished, whether
                    // or not the file bothered to leave a blank line between them.
                    flush()
                    start = parseTimestamp(arrow.groupValues[1])
                    end = parseTimestamp(arrow.groupValues[2])
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

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
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
