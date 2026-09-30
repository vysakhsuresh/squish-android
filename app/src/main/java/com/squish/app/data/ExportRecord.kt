package com.squish.app.data

import android.net.Uri
import java.io.File

data class ExportRecord(
    val id: String,
    val title: String,
    /**
     * Where the render was written. The record's identity - the done screen is
     * routed by it - and the file itself only until the gallery copy is
     * verified, when the private copy is deleted (GallerySaver.retire); see
     * [mediaUri] for what to open.
     */
    val outputPath: String,
    val originalSizeBytes: Long,
    val outputSizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val createdAtMillis: Long,
    /**
     * Whether the copy into the gallery was made: true, false, or null for a
     * record written before anyone checked. Only a true or an unknown earns
     * "Saved to your gallery"; a false says the video is only inside Squish.
     */
    val savedToGallery: Boolean? = null,
    /** The gallery copy's content URI, when there is one. */
    val galleryUri: String? = null,
    /** Whether [title] is a name the person gave the project: shown as it is, never replaced by the moment. */
    val named: Boolean = false
) {
    val isAudio: Boolean get() = outputPath.endsWith(".m4a", ignoreCase = true)

    val mimeType: String get() = if (isAudio) "audio/mp4" else "video/mp4"

    /**
     * What the library calls it: its title, or - where that is a name a camera
     * or the app made up ("1001319240.jpg", "photo_1790...", a copy's UUID) -
     * the moment it was made, "29 Sep, 11:47 PM" (ProjectRules.displayTitle).
     */
    val shownTitle: String
        get() = if (named) title else ProjectRules.displayTitle(title, createdAtMillis, prefix = null)

    /**
     * The one copy to open, play and share: the gallery's when it was made,
     * the private file otherwise. Exports are stored once now - the private
     * copy goes after the gallery copy is verified - so a record from before
     * that, or one whose publish failed, is the only kind with a file here.
     */
    val mediaUri: Uri get() = galleryUri?.let(Uri::parse) ?: Uri.fromFile(File(outputPath))

    /** Whether the private copy is the one to open. */
    val onPrivateCopy: Boolean get() = galleryUri == null
}
