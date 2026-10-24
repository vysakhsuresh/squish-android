package com.squish.app.media.audio

/**
 * What libsquishwhisper.so hands back, read (EDGE_PLAN E1). One line per word,
 * "t0ms\tt1ms\ttext", then "lang\t<code>". Pure, so tools/jvm/WhisperTextChecks.kt
 * runs it.
 */
object WhisperText {

    data class Word(val startMs: Long, val endMs: Long, val text: String)
    data class Result(val words: List<Word>, val language: String?) {
        val text: String get() = words.joinToString(" ") { it.text }.replace(Regex(" ([,.!?;:])"), "$1").trim()
    }

    fun parse(raw: String?, offsetMs: Long = 0L): Result? {
        if (raw == null) return null
        var lang: String? = null
        val words = ArrayList<Word>()
        raw.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0] == "lang") { lang = parts[1].takeIf { it.isNotBlank() }; return@forEach }
            if (parts.size < 3) return@forEach
            val t0 = parts[0].toLongOrNull() ?: return@forEach
            val t1 = parts[1].toLongOrNull() ?: return@forEach
            val text = parts.drop(2).joinToString("\t").trim()
            // Whisper's own markers - "[BLANK_AUDIO]", "(music)" - are not words anybody said.
            if (text.isEmpty() || NON_SPEECH.matches(text)) return@forEach
            words += Word(t0 + offsetMs, maxOf(t0, t1) + offsetMs, text)
        }
        // A punctuation mark whisper.cpp split off as its own word belongs to the word before it.
        val merged = ArrayList<Word>()
        for (w in words) {
            if (merged.isNotEmpty() && PUNCTUATION.matches(w.text)) {
                val last = merged.removeAt(merged.size - 1)
                merged += last.copy(endMs = maxOf(last.endMs, w.endMs), text = last.text + w.text)
            } else merged += w
        }
        return Result(merged, lang)
    }

    private val NON_SPEECH = Regex("""^[\[(][^\])]*[\])]$""")
    private val PUNCTUATION = Regex("""^[,.!?;:]+$""")
}
