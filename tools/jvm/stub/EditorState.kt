package com.squish.app.media

import android.net.Uri

/*
 * The three Android-bound names EditorUiState mentions, standing in so that
 * editor/EditorModels.kt can be compiled here.
 *
 * Read this before trusting anything a suite says about EditorUiState.
 *
 * The honest case for these: the draft codec reads twenty-nine fields off the
 * state, and every one of them is a plain constructor parameter - none is a
 * computed property, so none can route through any of the three. They are
 * mentioned by *other* fields: `failure`, `exportProgress`, and a pair of
 * codec-probe questions, all of which tools/jvm/DraftFieldChecks.kt lists as
 * transient and no draft carries. So what the round trip exercises is untouched
 * by what is written here.
 *
 * What that means a suite over this file may and may not claim:
 *
 *   - It may say a field is carried into a draft and read back, because that
 *     path is real code throughout.
 *   - It may **not** say anything about `sourceIsHdr`, the codec answer, the
 *     export progress or a failure. `cached` here always answers null, so
 *     `sourceIsHdr` is always false - not because the footage is SDR but
 *     because nothing probed it.
 *
 * If a future field of the draft reads one of these, this file stops being a
 * stand-in and becomes a fiction, and the suite will keep passing. The guard
 * against that is in the suite: it asserts that every field the encoder reads
 * is a constructor parameter.
 */

/** Stand-in: a field type only, never constructed or matched on here. */
sealed class SquishError(val message: String) {
    class Unknown(message: String) : SquishError(message)
}

/** Stand-in: a field type with a no-argument constructor, as the real one has. */
data class ExportProgress(
    val fraction: Float? = null,
    val elapsedMs: Long = 0,
    val remainingMs: Long? = null
)

/**
 * Stand-in. `cached` answers null for every file, which is what the real one
 * answers before anything has been probed - so the state's HDR and codec
 * questions read "not known", never "no".
 */
object MediaCompat {
    data class Report(
        val hdr: Boolean = false,
        val hasAudio: Boolean = false,
        val audioProblem: String? = null
    )

    fun cached(uri: Uri): Report? = null
}
