package com.squish.app.online

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Captions in another language, through MyMemory's free translation service -
 * no account or key. Only the caption's words are sent, one line at a time.
 */
object OnlineTranslate {

    /** [text] from [from] into [to] (two-letter codes); null when the service refused or failed. */
    suspend fun translate(context: Context, text: String, from: String, to: String): String? {
        if (text.isBlank() || from == to) return text
        // Line by line, so a two-line caption stays two lines. The service takes
        // 500 bytes a request: a long line, or one in a script of several bytes a
        // letter, goes in pieces at word ends, never cut short.
        val lines = text.split('\n')
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank()) { out += line; continue }
            val translated = StringBuilder()
            for ((piece, joined) in chunks(line)) {
                val t = translatePiece(context, piece, from, to) ?: return null
                if (translated.isNotEmpty() && !joined) translated.append(' ')
                translated.append(t)
            }
            out += translated.toString()
        }
        return out.joinToString("\n")
    }

    /**
     * [text] (one line) in pieces of at most [MAX_BYTES] UTF-8 bytes, split at
     * spaces where it can be; each with whether it continues the piece before
     * it inside one word (a word too long on its own, split between letters -
     * never inside an emoji or any other pair of UTF-16 units).
     */
    fun chunks(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val current = StringBuilder()
        var continues = false
        for (word in text.trim().split(Regex("\\s+"))) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { current.clear(); current.append(candidate); continue }
            if (current.isNotEmpty()) { out += current.toString() to continues; current.clear(); continues = false }
            var rest = word
            while (rest.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                var n = rest.length
                while (n > 1 && rest.substring(0, n).toByteArray(Charsets.UTF_8).size > MAX_BYTES) n--
                if (n in 1 until rest.length && Character.isLowSurrogate(rest[n])) n--
                out += rest.substring(0, n) to continues
                rest = rest.substring(n)
                continues = true
            }
            current.append(rest)
        }
        if (current.isNotEmpty()) out += current.toString() to continues
        return out
    }

    private suspend fun translatePiece(context: Context, text: String, from: String, to: String): String? {
        val url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, "UTF-8") +
            "&langpair=" + URLEncoder.encode("$from|$to", "UTF-8")
        val json = JSONObject(Online.get(context, url))
        if (json.optInt("responseStatus") != 200 || json.optBoolean("quotaFinished")) return null
        val top = json.optJSONObject("responseData")?.optString("translatedText")
        val matches = json.optJSONArray("matches")
        val candidates = (0 until (matches?.length() ?: 0)).mapNotNull { i ->
            val m = matches!!.optJSONObject(i) ?: return@mapNotNull null
            Candidate(
                translation = m.optString("translation"),
                quality = m.optString("quality").toIntOrNull() ?: 0,
                match = m.optDouble("match", 0.0)
            )
        }
        return pick(text, top, candidates)
    }

    /** One of the service's translation-memory entries for a piece. */
    data class Candidate(val translation: String, val quality: Int, val match: Double)

    /**
     * The translation to use for [source]: a reviewed entry (quality 50 and
     * up) that matches the source closely, the best of those, else the
     * service's own answer [top].
     *
     * The service answers with its closest memory entry whatever its quality,
     * and a crowd entry of quality 0 is often wrong: "BIG NEWS" came back as
     * "¡Uups!" on the phone, with "¡GRANDES NOTICIAS!" at quality 74 beside
     * it. Entries also carry the line breaks and spaces of the text they were
     * taken from, which are cut to the source's; a line all in capitals stays
     * in capitals.
     */
    fun pick(source: String, top: String?, candidates: List<Candidate>): String? {
        val reviewed = candidates
            .filter { it.quality >= MIN_QUALITY && it.match >= MIN_MATCH && tidy(it.translation).isNotBlank() }
            .maxWithOrNull(compareBy<Candidate> { it.match }.thenBy { it.quality })
        val chosen = tidy(reviewed?.translation ?: top ?: return null).takeIf { it.isNotBlank() } ?: return null
        val letters = source.filter { it.isLetter() }
        val shouting = letters.length >= 2 && letters.all { it.isUpperCase() }
        return if (shouting) chosen.uppercase() else chosen
    }

    /** Entities decoded, and the breaks and spaces an entry brought from its own text made one space or cut off: a piece is one line. */
    private fun tidy(s: String): String =
        unescape(s).replace("&#10;", " ").replace("&#13;", " ").replace(Regex("\\s+"), " ").trim()

    private const val MIN_QUALITY = 50
    private const val MIN_MATCH = 0.9

    /** The service returns some punctuation as entities. */
    private fun unescape(s: String): String = s
        .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    /** Under the service's own limit of 500 bytes a request. */
    const val MAX_BYTES = 450

    /** The languages offered, as code to name - the ones creators caption in most. */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English", "es" to "Spanish", "hi" to "Hindi", "pt" to "Portuguese", "fr" to "French",
        "de" to "German", "ar" to "Arabic", "ml" to "Malayalam", "ta" to "Tamil", "te" to "Telugu",
        "bn" to "Bengali", "id" to "Indonesian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
        "ru" to "Russian", "it" to "Italian", "tr" to "Turkish"
    )
}
