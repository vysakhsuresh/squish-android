package com.squish.app.editor

import android.net.Uri

enum class Quality(val label: String) {
    Small("Small"), Medium("Medium"), High("High"), Original("Original")
}

enum class CropAspect(val label: String, val ratio: Float?) {
    Original("Original", null),
    Portrait("9:16", 9f / 16f),
    Square("1:1", 1f),
    Landscape("16:9", 16f / 9f)
}

data class TextOverlayItem(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val colorArgb: Int,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.85f,
    val sizeSp: Int = 28
)

data class EditorUiState(
    val sourceUri: Uri? = null,
    val isLoadingSource: Boolean = true,
    val durationMs: Long = 0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val originalSizeBytes: Long = 0,

    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,

    val quality: Quality = Quality.Medium,
    val fitToSize: Boolean = false,
    val targetSizeMb: Int = 16,

    val muted: Boolean = false,
    val rotationDegrees: Int = 0,
    val cropAspect: CropAspect = CropAspect.Original,
    val speed: Float = 1f,

    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,

    val textOverlays: List<TextOverlayItem> = emptyList(),
    val musicUri: Uri? = null,
    val clipQueue: List<Uri> = emptyList(),

    val isExporting: Boolean = false,
    val estimatedOutputBytes: Long = 0
) {
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
}
