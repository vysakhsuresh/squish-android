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
            // Public domain first: its URL is creativecommons.org/licenses/publicdomain/,
            // which the licence pattern read as "CC PUBLICDOMAIN".
            get() = licenseUrl?.let { url ->
                if ("publicdomain" in url || "/zero/" in url) "Public domain"
                else Regex("licenses/([a-z-]+)/").find(url)?.groupValues?.get(1)?.let { "CC ${it.uppercase()}" }
            } ?: "Creative Commons"
    }

    /**
     * A style to browse by: the subjects the Archive files that kind of music
     * under. Ours, not typed, so they go into the query as they are.
     */
    enum class Genre(val label: String, val subjects: String) {
        Popular("Popular", DEFAULT_SUBJECTS),
        LoFi("Lo-fi", "lofi OR \"lo-fi\" OR chillhop"),
        Chill("Chill", "chill OR chillout OR downtempo"),
        Happy("Happy", "happy OR upbeat OR cheerful OR fun"),
        Cinematic("Cinematic", "cinematic OR soundtrack OR orchestral OR epic"),
        Piano("Piano", "piano"),
        Acoustic("Acoustic", "acoustic OR guitar OR folk"),
        Electronic("Electronic", "electronic OR electronica OR synth OR edm"),
        HipHop("Hip-hop", "\"hip hop\" OR hiphop OR beats"),
        Rock("Rock", "rock OR indie"),
        Jazz("Jazz", "jazz OR swing OR bossa"),
        Ambient("Ambient", "ambient OR atmospheric OR drone"),
        Classical("Classical", "classical OR baroque OR strings"),
        Funk("Funk", "funk OR soul OR disco"),
        World("World", "world OR latin OR reggae OR african OR indian")
    }

    /**
     * Up to [rows] tracks for [query] in [genre], page [page] (from 1); an
     * empty query gives the genre's most played. Each page is its own slice
     * of the Archive's list, so "Load more" never repeats a track.
     */
    /** One page of a search: the tracks fit for a video, and whether the Archive had a full page, so there may be more. */
    data class Page(val tracks: List<Track>, val full: Boolean)

    suspend fun search(context: Context, query: String, rows: Int = 24, genre: Genre = Genre.Popular, page: Int = 1): Page {
        val terms = query.trim()
        val q = buildString {
            append("(collection:netlabels OR collection:opensource_audio) AND mediatype:audio AND licenseurl:*creativecommons*")
            append(" AND NOT licenseurl:*-nd* AND NOT licenseurl:*-nc*")
            append(" AND subject:(").append(genre.subjects).append(")")
            // Music to cut to: no talks, sample packs or wartime marches, which the
            // same subjects also find (a "world" search led with a lecture series).
            append(" AND NOT subject:($NOT_MUSIC) AND NOT title:($NOT_MUSIC)")
            Online.searchTerms(terms)?.let { append(" AND (").append(it).append(")") }
        }
        val url = "https://archive.org/advancedsearch.php?q=" + enc(q) +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl&sort[]=downloads+desc" +
            "&rows=$rows&page=${page.coerceAtLeast(1)}&output=json"
        val docs = JSONObject(Online.get(context, url)).getJSONObject("response").getJSONArray("docs")
        val tracks = (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(
                id = id,
                // The same guard the artist beside it already has, and the one
                // OnlineStock gives its title. An Archive item may carry
                // several titles, and `optString` on Android answers
                // `String.valueOf(value)` for anything that is not a String -
                // so a multi-valued title became the literal JSON text
                // `["A","B"]`, which is then starred, kept in the recents, and
                // written onto the clip on the timeline.
                title = d.opt("title")?.let { t -> if (t is org.json.JSONArray) t.optString(0) else t.toString() }
                    ?.takeIf { it.isNotBlank() } ?: id,
                artist = d.opt("creator")?.let { c -> if (c is org.json.JSONArray) c.optString(0) else c.toString() }?.takeIf { it.isNotBlank() },
                licenseUrl = d.optString("licenseurl").takeIf { it.isNotBlank() }
            )
        }.filter { it.usableInAVideo }
        // Whether more pages may follow, from what the Archive sent before the
        // licence filter: judged after it, a page that lost half its rows hid
        // Load more with more to come.
        return Page(tracks, full = docs.length() >= rows)
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

    private const val DEFAULT_SUBJECTS = "instrumental OR ambient OR lofi OR piano OR acoustic OR electronic"
    private const val NOT_MUSIC = "lecture OR lectures OR sermon OR speech OR podcast OR audiobook OR interview OR " +
        // Single words or quoted phrases only: two bare words here (hindi audios)
        // quietly turned the whole exclusion off on the Archive's side.
        "samples OR \"sample pack\" OR notes OR nazi OR propaganda OR military OR war OR discourse OR osho OR rajneesh"

    const val DIR = "music/online"
}
