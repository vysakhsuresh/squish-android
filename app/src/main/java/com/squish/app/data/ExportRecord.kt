package com.squish.app.data

data class ExportRecord(
    val id: String,
    val title: String,
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
    val galleryUri: String? = null
)
