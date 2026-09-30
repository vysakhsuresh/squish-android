package com.squish.app.online

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Free fonts from Google Fonts, all under the Open Font License - fetched as
 * TrueType through the public stylesheet, which needs no key, and imported the
 * way a font picked from Files is (CustomFonts), so a draft names it by file.
 */
object OnlineFonts {

    /** A short list worth having for titles and captions, in the order offered. */
    val families: List<String> = listOf(
        "Bebas Neue", "Anton", "Lobster", "Pacifico", "Montserrat", "Poppins",
        "Oswald", "Playfair Display", "Dancing Script", "Permanent Marker",
        "Bangers", "Caveat", "Righteous", "Abril Fatface", "Shadows Into Light", "Satisfy"
    )

    /** The family as a TrueType file in the cache, ready for CustomFonts.import. */
    suspend fun fetch(context: Context, family: String): Uri {
        val css = Online.get(context, "https://fonts.googleapis.com/css2?family=" + URLEncoder.encode(family, "UTF-8"))
        val url = Regex("url\\((https://[^)]+)\\)\\s*format\\('truetype'\\)").find(css)?.groupValues?.get(1)
            ?: Regex("url\\((https://[^)]+\\.ttf)\\)").find(css)?.groupValues?.get(1)
            ?: throw IOException("no TrueType file for $family")
        val dir = File(context.cacheDir, "fonts").apply { mkdirs() }
        val file = File(dir, family.replace(Regex("[^A-Za-z0-9 ]"), "").trim() + ".ttf")
        return Uri.fromFile(Online.download(context, url, file))
    }
}
