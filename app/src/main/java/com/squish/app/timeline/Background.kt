package com.squish.app.timeline

/** What goes where the background was. */
enum class BackgroundFill(val label: String) {
    /** The original background, heavily blurred - a portrait-mode look. */
    Blur("Blur"),

    /** A flat colour. */
    Colour("Colour"),

    /** Nothing: transparent, so a layer above the base shows only the person. */
    Remove("Cut out")
}

/**
 * Background removal on one clip: where its person masks are stored, and what
 * replaces the background. The masks are measured once, on the phone, and read
 * by both the preview and the export.
 */
data class BackgroundRemoval(
    /** Absolute path of the mask file written by the segmenter. */
    val maskFile: String,
    val fill: BackgroundFill = BackgroundFill.Blur,
    val colorArgb: Int = 0xFF101828.toInt()
)
