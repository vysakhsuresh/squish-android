package com.squish.app.data

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * A text file somebody picked, read as text.
 *
 * Both readers of one used to assume UTF-8 and *replace* what they could not
 * read - `bufferedReader()` for an .srt, `decodeToString()` for a .cube. A
 * subtitle file in a legacy single-byte encoding (windows-1252 for Western
 * Europe, 1251 for Cyrillic, 1256 for Arabic - which is what Notepad's "ANSI"
 * writes and what most subtitle archives hold) has ASCII timing lines, so the
 * cues parsed, the import reported success, and every accented or non-Latin
 * letter in the words had quietly become U+FFFD: "Un café très chaud" landed as
 * "Un caf?e tr?s chaud". The app's own round trip was safe, because it writes
 * UTF-8; only files from elsewhere were corrupted, and nothing warned. A UTF-16
 * file decoded to no timing line at all and was at least refused with a
 * message, so the failure was honest there and silent here.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/SrtChecks.kt).
 */
object PickedText {

    /**
     * [bytes] as text.
     *
     * A byte-order mark decides it outright. Without one, UTF-8 is tried
     * *strictly* - a decoder that reports rather than replaces - and a file
     * that is not valid UTF-8 is read in the legacy encoding its reader's own
     * language suggests, which is the best hint available about where a file
     * like this came from. ISO-8859-1 is the last resort and cannot fail:
     * every byte maps to a character.
     */
    fun decode(bytes: ByteArray, language: String = Locale.getDefault().language): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strictly(bytes, Charsets.UTF_8)?.let { return it }
        legacyCharset(language)?.let { charset -> strictly(bytes, charset)?.let { return it } }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** [bytes] in [charset], or null when they are not that encoding at all. */
    private fun strictly(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * The encoding a file in [language] is most likely to be in when it is not
     * UTF-8. Null where there is no guess better than the last resort.
     */
    private fun legacyCharset(language: String): Charset? {
        val name = when (language.lowercase(Locale.ROOT)) {
            "ru", "uk", "be", "bg", "sr", "mk" -> "windows-1251"
            "ar", "fa", "ur" -> "windows-1256"
            "el" -> "windows-1253"
            "tr" -> "windows-1254"
            "he", "iw" -> "windows-1255"
            "th" -> "windows-874"
            "vi" -> "windows-1258"
            "pl", "cs", "sk", "hu", "ro", "hr", "sl", "sq" -> "windows-1250"
            "zh" -> "GBK"
            "ja" -> "Shift_JIS"
            "ko" -> "EUC-KR"
            else -> "windows-1252"
        }
        return runCatching { Charset.forName(name) }.getOrNull()
    }
}
