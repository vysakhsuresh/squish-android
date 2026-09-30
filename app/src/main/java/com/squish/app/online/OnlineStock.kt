package com.squish.app.online

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Free stock footage from the Internet Archive's stock_footage collection:
 * public-domain and Creative Commons clips whose licence lets them be cut into
 * someone's video (no "no derivatives", no "non-commercial"), small enough to
 * fetch on a phone. Searched without an account or a key; the H.264 copy the
 * Archive keeps of each is what is downloaded.
 */
object OnlineStock {

    data class Video(val id: String, val title: String, val licenseUrl: String?, val sizeBytes: Long) {
        val licenseLabel: String
            get() = licenseUrl?.let { url ->
                if ("publicdomain" in url) "Public domain"
                else Regex("licenses/([a-z-]+)/").find(url)?.groupValues?.get(1)?.let { "CC ${it.uppercase()}" }
            } ?: "Creative Commons"
        val thumbnailUrl: String get() = "https://archive.org/services/img/${enc(id)}"
    }

    suspend fun search(context: Context, query: String, rows: Int = 24): List<Video> {
        val q = buildString {
            append("collection:stock_footage AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*)")
            append(" AND NOT licenseurl:*-nd* AND NOT licenseurl:*-nc* AND item_size:[* TO $MAX_ITEM_BYTES]")
            Online.searchTerms(query)?.let { append(" AND (").append(it).append(")") }
        }
        val url = "https://archive.org/advancedsearch.php?q=" + enc(q) +
            "&fl[]=identifier&fl[]=title&fl[]=licenseurl&fl[]=item_size&sort[]=downloads+desc&rows=$rows&page=1&output=json"
        val docs = JSONObject(Online.get(context, url)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Video(
                id = id,
                title = d.opt("title")?.let { if (it is JSONArray) it.optString(0) else it.toString() }?.takeIf { it.isNotBlank() } ?: id,
                licenseUrl = d.optString("licenseurl").takeIf { it.isNotBlank() },
                sizeBytes = d.optLong("item_size")
            )
        }.filter { it.licenseUrl != null }
    }

    /** The item's picture, kept in the cache. */
    suspend fun thumbnail(context: Context, video: Video): Bitmap? = runCatching {
        val dir = File(context.cacheDir, "stock-thumbs").apply { mkdirs() }
        val file = File(dir, video.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")
        if (file.length() == 0L) Online.download(context, video.thumbnailUrl, file)
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    /** The clip on the phone (files/imports/stock), ready for the timeline. */
    suspend fun download(context: Context, video: Video): Uri? {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, video.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".mp4")
        if (file.length() > 0L) return Uri.fromFile(file)
        val files = JSONObject(Online.get(context, "https://archive.org/metadata/${enc(video.id)}")).optJSONArray("files") ?: return null
        val mp4 = (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".mp4", ignoreCase = true) }
            .minByOrNull { it.optString("size").toLongOrNull() ?: Long.MAX_VALUE }
            ?.optString("name") ?: return null
        val url = "https://archive.org/download/${enc(video.id)}/" + mp4.split('/').joinToString("/") { enc(it) }
        return Uri.fromFile(Online.download(context, url, file))
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Items over this are skipped: a stock clip, not a feature film, on a phone's data. */
    private const val MAX_ITEM_BYTES = 80_000_000L

    const val DIR = "imports/stock"
}
