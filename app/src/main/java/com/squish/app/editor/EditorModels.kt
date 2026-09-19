package com.squish.app.editor

import android.net.Uri
import com.squish.app.media.audio.Waveform

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

enum class SyncStatus { Idle, Analyzing, Matched, NoMatch }

data class EditorUiState(
    val sourceUri: Uri? = null,
    val isLoadingSource: Boolean = true,
    val durationMs: Long = 0,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val originalSizeBytes: Long = 0,
    val fps: Float = 30f,

    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val playheadMs: Long = 0,
    val markers: List<Long> = emptyList(),
    val snapToMarkers: Boolean = true,

    val quality: Quality = Quality.Medium,
    val fitToSize: Boolean = false,
    val targetSizeMb: Int = 16,

    val muteOriginal: Boolean = false,
    val originalVolume: Float = 1f,
    val rotationDegrees: Int = 0,
    val cropAspect: CropAspect = CropAspect.Original,
    val speed: Float = 1f,

    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,

    val textOverlays: List<TextOverlayItem> = emptyList(),
    val clipQueue: List<Uri> = emptyList(),

    // Separate audio track: dual-system sound, a music bed, a voiceover.
    val audioTrackUri: Uri? = null,
    val audioTrackDurationMs: Long = 0,
    val audioTrackName: String? = null,
    val audioOffsetMs: Long = 0,
    val audioVolume: Float = 1f,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val syncConfidence: Float = 0f,

    val videoWaveform: Waveform? = null,
    val audioWaveform: Waveform? = null,

    val isExporting: Boolean = false,
    val estimatedOutputBytes: Long = 0
) {
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)

    val frameMs: Long get() = Timecode.frameDurationMs(fps)

    val hasSeparateAudio: Boolean get() = audioTrackUri != null

    /**
     * How far the export has to push the video's in-point so the offset external
     * audio still has material to play from its own time zero. Surfaced in the UI
     * so a head trim never happens silently.
     */
    val syncHeadTrimMs: Long
        get() = if (!hasSeparateAudio) 0L else (-(trimStartMs + audioOffsetMs)).coerceAtLeast(0L)
}
