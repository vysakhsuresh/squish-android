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
