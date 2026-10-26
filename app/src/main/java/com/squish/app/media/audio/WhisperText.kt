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

        /**
         * Each word's start from [stretchStartMs], the start of the stretch it
         * was heard in - the clock a caption's word starts are kept on, as the
         * segmenter's are. The words themselves are on the file's clock; handed
         * on as they were, every line after the first had its words offset twice.
         */
        fun startsWithin(stretchStartMs: Long): List<Long> = words.map { (it.startMs - stretchStartMs).coerceAtLeast(0L) }

        /** Every word in [language]'s script (see [inScriptOf]). */
        fun inScript(language: String?): Result = copy(words = words.map { it.copy(text = inScriptOf(it.text, language)) })
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

    /**
     * The same lines as bytes, as libsquishwhisper.so's transcribeBytes hands
     * them back. Its pieces can cut a multi-byte character in two - seen on
     * the moto with the first Hindi file, where NewStringUTF aborted the app
     * on the invalid sequence (and modified UTF-8 cannot carry an emoji at
     * all) - so a word whose bytes end inside a character is joined to the
     * next piece, and a byte nothing completes is decoded as U+FFFD.
     */
    fun parseBytes(raw: ByteArray?, offsetMs: Long = 0L): Result? {
        if (raw == null) return null
        val out = StringBuilder()
        var pendingStart: String? = null
        var pending = ByteArray(0)
        var start = 0
        fun flush(end: String) {
            out.append(pendingStart).append('\t').append(end).append('\t')
                .append(String(pending, Charsets.UTF_8)).append('\n')
            pendingStart = null
            pending = ByteArray(0)
        }
        var lastEnd = "0"
        for (i in 0..raw.size) {
            if (i < raw.size && raw[i] != NL) continue
            val line = raw.copyOfRange(start, i)
            start = i + 1
            val tab1 = line.indexOf(TAB)
            val tab2 = if (tab1 < 0) -1 else line.indexOfFrom(TAB, tab1 + 1)
            if (tab1 < 0) continue
            val first = String(line, 0, tab1, Charsets.US_ASCII)
            if (first == "lang") {
                out.append(String(line, Charsets.UTF_8)).append('\n')
                continue
            }
            if (tab2 < 0) continue
            val end = String(line, tab1 + 1, tab2 - tab1 - 1, Charsets.US_ASCII)
            if (pendingStart == null) pendingStart = first
            pending += line.copyOfRange(tab2 + 1, line.size)
            lastEnd = end
            if (!endsInsideCharacter(pending)) flush(end)
        }
        if (pendingStart != null) flush(lastEnd)
        return parse(out.toString(), offsetMs)
    }

    /**
     * [text] carried into [language]'s own script, where whisper.cpp wrote it
     * in another Brahmic one. Told "ml", the small model heard Malayalam right
     * and wrote every word in Devanagari (26 Oct, on the moto: the converted
     * Malayalam voice read back as "नमस्कारं, चंद्त्येगे स्वागतं"), and a prompt
     * in Malayalam did not move it. Unicode lays the Brahmic blocks out letter
     * for letter, 128 code points each, so a letter is carried by the
     * distance between the blocks; a nukta, which Malayalam has no letter
     * for, goes; a word ending on a consonant and virama takes the chillu
     * modern Malayalam writes. Tamil is left as heard: it has no letter for
     * most of Devanagari's, and half a word carried is worse than none.
     */
    fun inScriptOf(text: String, language: String?): String {
        val base = BRAHMIC_BLOCK[language?.lowercase()?.substringBefore('-')] ?: return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val block = cp and 0x7F.inv()
            if (cp !in 0x0900..0x0DFF || block == base || block == TAMIL) { out.appendCodePoint(cp); continue }
            val offset = cp - block
            if (offset == NUKTA) continue
            val target = base + offset
            when {
                Character.isDefined(target) -> out.appendCodePoint(target)
                // The dandas are Devanagari's alone and every Brahmic script borrows them.
                Character.getType(cp) == Character.OTHER_PUNCTUATION.toInt() -> out.appendCodePoint(cp)
            }
        }
        return if (base == MALAYALAM) chillu(out.toString()) else out.toString()
    }

    private fun chillu(s: String): String = CHILLU_AT_END.replace(s) { m ->
        (CHILLU[m.value[0]] ?: m.value[0]).toString()
    }

    private const val NUKTA = 0x3C
    private const val TAMIL = 0x0B80
    private const val MALAYALAM = 0x0D00
    private val BRAHMIC_BLOCK = mapOf(
        "hi" to 0x0900, "mr" to 0x0900, "ne" to 0x0900, "bn" to 0x0980, "as" to 0x0980, "pa" to 0x0A00,
        "gu" to 0x0A80, "or" to 0x0B00, "te" to 0x0C00, "kn" to 0x0C80, "ml" to MALAYALAM
    )
    private val CHILLU = mapOf('ണ' to 'ൺ', 'ന' to 'ൻ', 'ര' to 'ർ', 'ല' to 'ൽ', 'ള' to 'ൾ')
    private val CHILLU_AT_END = Regex("[ണനരലള]\u0D4D" + """(?![\p{L}\p{M}])""")

    /** Whether [b] ends partway through a UTF-8 sequence: its last lead byte wants more bytes than follow it. */
    private fun endsInsideCharacter(b: ByteArray): Boolean {
        var i = b.size - 1
        var back = 0
        while (i >= 0 && back < 4 && (b[i].toInt() and 0xC0) == 0x80) { i--; back++ }
        if (i < 0 || back >= 4) return false
        val lead = b[i].toInt() and 0xFF
        val wants = when {
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return b.size - i < wants
    }

    private fun ByteArray.indexOf(x: Byte): Int = indexOfFrom(x, 0)
    private fun ByteArray.indexOfFrom(x: Byte, from: Int): Int {
        for (k in from until size) if (this[k] == x) return k
        return -1
    }

    private const val NL = '\n'.code.toByte()
    private const val TAB = '\t'.code.toByte()

    private val NON_SPEECH = Regex("""^[\[(][^\])]*[\])]$""")
    private val PUNCTUATION = Regex("""^[,.!?;:]+$""")
}
