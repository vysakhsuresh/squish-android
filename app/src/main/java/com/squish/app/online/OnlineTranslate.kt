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
        return json.optJSONObject("responseData")?.optString("translatedText")?.takeIf { it.isNotBlank() }
            ?.let(::unescape)
    }

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
