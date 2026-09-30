package com.squish.app.online

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Free music from the Internet Archive's netlabels and open-source audio -
 * Creative Commons tracks, searched without an account or a key. A track's
 * licence and artist come with it, so the row can say what credit is owed.
 */
object OnlineMusic {

    data class Track(
        val id: String,
        val title: String,
        val artist: String?,
        /** The licence's own page; a CC BY track wants its artist credited. */
        val licenseUrl: String?
    ) {
        /**
         * Whether the licence lets it go under someone's video, including one
         * they are paid for: no "no derivatives" (music set to picture is an
         * adaptation) and no "non-commercial".
         */
        val usableInAVideo: Boolean
            get() = licenseUrl?.let { "-nd" !in it && "-nc" !in it && "/nd" !in it && "/nc" !in it } ?: false

        val licenseLabel: String
            get() = licenseUrl?.let { url ->
                Regex("licenses/([a-z-]+)/").find(url)?.groupValues?.get(1)?.let { "CC ${it.uppercase()}" }
                    ?: if ("publicdomain" in url) "Public domain" else null
            } ?: "Creative Commons"
    }

    /** Up to [rows] tracks for [query]; an empty query gives popular ones. */
    suspend fun search(context: Context, query: String, rows: Int = 20): List<Track> {
        val terms = query.trim()
        val q = buildString {
            append("(collection:netlabels OR collection:opensource_audio) AND mediatype:audio AND licenseurl:*creativecommons*")
            // Opened with nothing typed, music worth putting under a video rather
            // than whatever was downloaded most.
            append(" AND (").append(Online.searchTerms(terms) ?: DEFAULT_QUERY).append(")")
        }
        val url = "https://archive.org/advancedsearch.php?q=" + enc(q) +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl&sort[]=downloads+desc" +
            "&rows=${rows * 3}&page=1&output=json"
        val docs = JSONObject(Online.get(context, url)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(
                id = id,
                title = d.optString("title").takeIf { it.isNotBlank() } ?: id,
                artist = d.opt("creator")?.let { c -> if (c is org.json.JSONArray) c.optString(0) else c.toString() }?.takeIf { it.isNotBlank() },
                licenseUrl = d.optString("licenseurl").takeIf { it.isNotBlank() }
            )
        }.filter { it.usableInAVideo }.take(rows)
    }

    /** The URL of the item's first MP3 - enough to listen to it streamed. */
    suspend fun streamUrl(context: Context, track: Track): String? {
        val files = JSONObject(Online.get(context, "https://archive.org/metadata/${enc(track.id)}")).optJSONArray("files") ?: return null
        val name = (0 until files.length()).map { files.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".mp3", ignoreCase = true) }
            ?.optString("name") ?: return null
        return "https://archive.org/download/${enc(track.id)}/" + name.split('/').joinToString("/") { enc(it) }
    }

    /** Where the track is kept once downloaded - the key it is starred under. */
    fun localFile(context: Context, track: Track): File =
        File(File(context.filesDir, DIR), track.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".mp3")

    /** The track kept on the phone (files/music/online), for the timeline. */
    suspend fun download(context: Context, track: Track): Uri? {
        val file = localFile(context, track).apply { parentFile?.mkdirs() }
        if (file.length() > 0L) return Uri.fromFile(file)
        val url = streamUrl(context, track) ?: return null
        return Uri.fromFile(Online.download(context, url, file))
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private const val DEFAULT_QUERY = "subject:(instrumental OR ambient OR lofi OR piano OR acoustic OR electronic)"

    const val DIR = "music/online"
}
