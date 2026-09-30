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
        val url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text.take(MAX_CHARS), "UTF-8") +
            "&langpair=" + URLEncoder.encode("$from|$to", "UTF-8")
        val json = JSONObject(Online.get(context, url))
        if (json.optInt("responseStatus") != 200 || json.optBoolean("quotaFinished")) return null
        return json.optJSONObject("responseData")?.optString("translatedText")?.takeIf { it.isNotBlank() }
            ?.let(::unescape)
    }

    /** The service returns some punctuation as entities. */
    private fun unescape(s: String): String = s
        .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    /** The service's own limit per request. */
    private const val MAX_CHARS = 480

    /** The languages offered, as code to name - the ones creators caption in most. */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English", "es" to "Spanish", "hi" to "Hindi", "pt" to "Portuguese", "fr" to "French",
        "de" to "German", "ar" to "Arabic", "ml" to "Malayalam", "ta" to "Tamil", "te" to "Telugu",
        "bn" to "Bengali", "id" to "Indonesian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
        "ru" to "Russian", "it" to "Italian", "tr" to "Turkish"
    )
}
