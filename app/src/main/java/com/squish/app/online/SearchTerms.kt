package com.squish.app.online

/**
 * What a person typed, safe inside a Lucene query: letters, digits and spaces
 * only, the words joined by AND - so a search can narrow a query but never
 * escape its licence filter. Lower case, too: a typed "rock OR jazz" sent the
 * operator as a bare word between two ANDs, a syntax error the Archive answers
 * with no list, read as "couldn't reach".
 *
 * Null when nothing is left, so a caller can leave the clause out rather than
 * send an empty one.
 *
 * In its own file because [Online] cannot be compiled off a phone - it holds a
 * Context, a connection and a Compose helper - and this is the one piece of it
 * that is arithmetic over a string and therefore checkable
 * (`tools/jvm/SearchTermChecks.kt`). It is the guard between a text field and a
 * query, which is worth executing rather than reading.
 */
fun searchTerms(typed: String): String? =
    typed.lowercase()
        // Letters, digits and *combining marks* of any script.
        //
        // \p{L} is letters alone, and in an Indic script the vowel signs and
        // the virama are marks rather than letters - so a word in Malayalam
        // came back as three fragments joined by AND, which matches nothing,
        // and a search typed in it returned an empty list the app reads as
        // "couldn't reach". The same goes for Arabic, Hebrew, Thai and
        // Devanagari, and for a decomposed accent in any script at all.
        .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(MAX_TERMS)
        .joinToString(" AND ")
        .ifEmpty { null }

/** How many words of a typed search are used. Past this it is not a search, it is a sentence. */
const val MAX_TERMS = 8

/**
 * Whether a licence URL lets the clip be cut into someone else's video.
 *
 * Here, beside the search guard, and for the same reason: it is the other
 * thing between a list the Archive sends and what the app offers as free
 * b-roll, and it is arithmetic over a string, so it can be executed
 * (`tools/jvm/SearchTermChecks.kt`).
 *
 * It had been left to the query alone - `AND NOT licenseurl:*-nd* AND NOT
 * licenseurl:*-nc*` - which only matches the hyphenated BY-era forms. Creative
 * Commons 1.0 wrote its codes without the `by`: `licenses/nd/1.0/`,
 * `licenses/nc/1.0/` and `licenses/nc-sa/1.0/` have no hyphen *before* the
 * term, so all three slipped the filter and a no-derivatives or
 * non-commercial clip was offered as free to cut. A query is a filter and not
 * a guarantee; this is the guarantee.
 *
 * An allow-list rather than a deny-list, so a licence code nobody here has
 * heard of is refused rather than waved through.
 */
fun licenceAllowsCutting(licenseUrl: String?): Boolean {
    val url = licenseUrl?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    // No rights reserved at all: CC0, the public domain mark, and the Archive's
    // own /publicdomain/ pages.
    if ("publicdomain" in url || "/zero/" in url) return true
    val code = Regex("/licenses/([a-z0-9.-]+)/").find(url)?.groupValues?.get(1) ?: return false
    val terms = code.split('-').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return false
    return terms.all { it in CUTTABLE_TERMS }
}

/**
 * The licence terms that leave a clip cuttable: attribution and share-alike.
 *
 * Not `nc` (non-commercial - someone's monetised video is commercial) and not
 * `nd` (no derivatives - a cut *is* a derivative), and not anything else,
 * because an unknown term is not an argument for using the footage.
 */
private val CUTTABLE_TERMS = setOf("by", "sa")
