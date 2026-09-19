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
    val sourceHasAudio: Boolean = true,

    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val playheadMs: Long = 0,
    val markers: List<Long> = emptyList(),
    val snapToMarkers: Boolean = true,

    val quality: Quality = Quality.Medium,
    val fitToSize: Boolean = false,
    val targetSizeMb: Int = 16,
    val audioOnly: Boolean = false,

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

    // A second audio track: separately recorded sound, a music bed, a voiceover.
    // Three numbers describe it completely -
    //   audioTrimStartMs/EndMs : which slice of the audio file to use
    //   audioPlacementMs       : where on the video timeline that slice begins
    // Sync offset is simply the difference between the two, so aligning a clap and
    // dropping a music cue at a chorus are the same operation underneath.
    val audioTrackUri: Uri? = null,
    val audioTrackDurationMs: Long = 0,
    val audioTrackName: String? = null,
    val audioTrimStartMs: Long = 0,
    val audioTrimEndMs: Long = 0,
    val audioPlacementMs: Long = 0,
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

    /** How long the chosen slice of the audio track runs. */
    val audioSliceDurationMs: Long get() = (audioTrimEndMs - audioTrimStartMs).coerceAtLeast(0)

    /**
     * At video time t the aligned audio sample sits at (t + audioOffsetMs).
     * Positive means the track's content runs ahead of the picture.
     */
    val audioOffsetMs: Long get() = audioTrimStartMs - audioPlacementMs

    /** Whether any audio at all reaches the exported file. */
    val hasAnyAudio: Boolean get() = (!muteOriginal && sourceHasAudio) || hasSeparateAudio
}
