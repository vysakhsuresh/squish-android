package com.squish.app.editor

import android.net.Uri
import com.squish.app.data.ProjectSnapshot
import com.squish.app.media.SquishError
import com.squish.app.media.audio.Waveform
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.TimelineState

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

/**
 * Where the low-resolution stand-in for heavy footage has got to. Only 4K-and-up
 * sources ever leave [NotNeeded]; everything smaller plays fine as it is.
 */
enum class ProxyStatus { NotNeeded, Building, Ready, Failed }

/**
 * An edit recovered from disk after the app was killed, offered rather than
 * applied: silently overwriting what someone just opened would be its own kind of
 * data loss. [sourceReadable] is false when the original clip can no longer be
 * opened - the permission a gallery picker grants does not outlive the process -
 * in which case the edit is kept and re-attaches when that clip is opened again.
 */
data class RecoveryOffer(
    val snapshot: ProjectSnapshot,
    val sourceReadable: Boolean
) {
    val clipCount: Int get() = snapshot.clipCount
    val savedAtMillis: Long get() = snapshot.savedAtMillis
    val durationMs: Long get() = snapshot.totalDurationMs
}

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

    // The video track, in order. Seeded with the whole source clip on load; split,
    // trim, reorder and merge all operate on this list, and export renders it.
    val videoClips: List<Clip> = emptyList(),

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

    val selectedClipId: String? = null,
    val pixelsPerSecond: Float = 42f,

    // Proxy media. The preview plays [proxyUri] when it exists; export never does.
    val proxyUri: Uri? = null,
    val proxyStatus: ProxyStatus = ProxyStatus.NotNeeded,

    // A session that survived the process being killed, waiting to be accepted.
    val recovery: RecoveryOffer? = null,

    // The last failure, in sentences. Null whenever the editor is healthy.
    val failure: SquishError? = null,

    val isExporting: Boolean = false,
    val estimatedOutputBytes: Long = 0
) {
    val trimmedDurationMs: Long
        get() = if (videoClips.isEmpty()) (trimEndMs - trimStartMs).coerceAtLeast(0)
        else videoClips.sumOf { it.durationMs }

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

/**
 * The timeline the editor draws, assembled from the one authoritative copy of each
 * thing: the video track as stored, the separate audio track as its three numbers,
 * and captions as their time windows. Dragging a lane writes straight back to
 * whichever of those owns it, so nothing is ever mirrored into a second place.
 */
fun EditorUiState.toTimeline(): TimelineState {
    val audio = audioTrackUri?.let { uri ->
        listOf(
            Clip(
                id = AUDIO_CLIP_ID,
                kind = ClipKind.Audio,
                uri = uri,
                label = audioTrackName ?: "Audio",
                sourceInMs = audioTrimStartMs,
                sourceOutMs = audioTrimEndMs,
                timelineStartMs = audioPlacementMs,
                sourceDurationMs = audioTrackDurationMs,
                volume = audioVolume
            )
        )
    } ?: emptyList()

    val captions = textOverlays.map { overlay ->
        Clip(
            id = overlay.id,
            kind = ClipKind.Text,
            label = overlay.text,
            sourceInMs = 0,
            sourceOutMs = (overlay.endMs - overlay.startMs).coerceAtLeast(MIN_CLIP_MS),
            timelineStartMs = overlay.startMs,
            sourceDurationMs = durationMs,
            text = overlay.text
        )
    }

    return TimelineState(
        clips = videoClips + audio + captions,
        selectedClipId = selectedClipId,
        playheadMs = playheadMs,
        pixelsPerSecond = pixelsPerSecond
    )
}

const val AUDIO_CLIP_ID = "squish-audio-track"
