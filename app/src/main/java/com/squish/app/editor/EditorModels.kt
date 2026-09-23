package com.squish.app.editor

import android.net.Uri
import com.squish.app.data.ProjectSnapshot
import com.squish.app.media.ExportProgress
import com.squish.app.media.SquishError
import com.squish.app.media.effects.Grade
import com.squish.app.media.effects.Looks
import com.squish.app.media.audio.Waveform
import com.squish.app.media.video.MotionTrack
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
    val sizeSp: Int = 28,

    /**
     * Pins this caption to something moving. Stored in timeline time, matching
     * [startMs] and [endMs], because that is the clock the overlay renderer is
     * already handed.
     */
    val track: MotionTrack? = null
) {
    /** Where the caption sits at a moment, following its track if it has one. */
    fun anchorAt(timelineMs: Long): Pair<Float, Float> {
        val sample = track?.sampleAt(timelineMs) ?: return xFraction to yFraction
        return sample.xFraction to sample.yFraction
    }

    /**
     * The same caption expressed in a clip's own source clock.
     *
     * The preview plays a source file, so its effects are handed source time, while
     * a caption is written in timeline time. Shifting once here is what makes a
     * caption appear at the right moment over a clip that has been trimmed or moved
     * - otherwise it shows up early by however far the clip was dragged.
     */
    fun shiftedInto(clip: Clip): TextOverlayItem {
        val delta = clip.sourceInMs - clip.timelineStartMs
        return copy(
            startMs = startMs + delta,
            endMs = endMs + delta,
            track = track?.let { t ->
                MotionTrack(t.samples.map { it.copy(atMs = it.atMs + delta) })
            }
        )
    }
}

enum class SyncStatus { Idle, Analyzing, Matched, NoMatch }

/**
 * How captioning is going. [transcribed] is separate from [total] because speech
 * recognition is best-effort: the timings always work, the words may not. Reporting
 * the two apart is what turns an empty caption card from a mystery into an answer.
 */
/** How a tracking run is going, and what it found. */
data class TrackProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val failed: Boolean = false,
    /** In the source clock of [clipId]. */
    val track: MotionTrack? = null,
    val clipId: String? = null,
    val pointX: Float = 0.5f,
    val pointY: Float = 0.5f,
    val boxFraction: Float = 0.14f
)

/** How stabilization analysis is going, and what it cost. */
data class StabilizeProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val crop: Float = 0f,
    val framesAnalysed: Int = 0,
    val failed: Boolean = false
)

data class CaptionProgress(
    val running: Boolean = false,
    val stage: String = "",
    val total: Int = 0,
    val transcribed: Int = 0,
    val finished: Boolean = false,
    val recognitionAvailable: Boolean = true
)

/**
 * Where the low-resolution stand-in for heavy footage has got to. Only 4K-and-up
 * sources ever leave [NotNeeded]; everything smaller plays fine as it is.
 */
enum class ProxyStatus { NotNeeded, Building, Ready, Failed }

/**
 * An edit recovered from disk after the app was killed, offered rather than
 * applied: silently overwriting what someone just opened would be its own kind of
 * data loss. A snapshot whose clip can no longer be opened is not offered at all -
 * the permission a gallery picker grants does not outlive the process - and is left
 * on disk to re-attach when that clip is opened again.
 */
data class RecoveryOffer(val snapshot: ProjectSnapshot) {
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

    // Transport. [isPlaying] is what the user asked for; the preview engine obeys
    // it. [scrubNonce] ticks on every deliberate jump so the engine can tell a
    // playhead that moved *because* playback advanced from one the user dragged.
    val isPlaying: Boolean = false,
    val scrubNonce: Long = 0,

    val quality: Quality = Quality.Original,
    val fitToSize: Boolean = false,
    val targetSizeMb: Int = 16,
    val audioOnly: Boolean = false,

    val muteOriginal: Boolean = false,
    val originalVolume: Float = 1f,
    val rotationDegrees: Int = 0,
    val cropAspect: CropAspect = CropAspect.Original,

    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,

    // The graded look, and how far it is dialled in. The sliders above refine on
    // top of it rather than replacing it.
    val lookId: String? = null,
    val lookIntensity: Float = 1f,

    val textOverlays: List<TextOverlayItem> = emptyList(),
    val captions: CaptionProgress = CaptionProgress(),
    val stabilize: StabilizeProgress = StabilizeProgress(),
    val stabilizeStrength: Float = 0.5f,
    val tracking: TrackProgress = TrackProgress(),

    // The video track, in order. Seeded with the whole source clip on load; split,
    // trim, reorder and merge all operate on this list, and export renders it.
    val videoClips: List<Clip> = emptyList(),

    /**
     * Every added sound: music, a voiceover, a second mic, as many as you like and
     * overlapping freely. Each is an ordinary [Clip], which is the whole point -
     * moving, trimming, splitting and deleting a sound is then the same code that
     * does it to a picture, and a music bed can be cut to the beat on the strip
     * exactly like a shot. This replaced seven loose fields that between them could
     * only ever describe one track.
     */
    val audioClips: List<Clip> = emptyList(),

    /** Waveforms cached per source file, so splitting a track costs no re-decode. */
    val audioWaveforms: Map<String, Waveform> = emptyMap(),

    val syncStatus: SyncStatus = SyncStatus.Idle,
    val syncConfidence: Float = 0f,

    val videoWaveform: Waveform? = null,

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
    /** What the encoder says it has done, while it is doing it. */
    val exportProgress: ExportProgress = ExportProgress(),
    val estimatedOutputBytes: Long = 0
) {
    /**
     * How long the finished video runs.
     *
     * This is where the last frame *lands*, not the sum of the clip lengths. Once
     * clips can be dragged apart - which is what makes manual sync possible - a
     * gap is part of the edit, and summing durations would report a video shorter
     * than the one that actually gets written.
     */
    val trimmedDurationMs: Long
        get() = if (videoClips.isEmpty()) (trimEndMs - trimStartMs).coerceAtLeast(0)
        else videoClips.maxOf { it.timelineEndMs }

    /** The full span the preview has to cover, sound included. */
    val timelineDurationMs: Long
        get() = maxOf(
            trimmedDurationMs,
            audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L,
            textOverlays.maxOfOrNull { it.endMs } ?: 0L
        )

    val frameMs: Long get() = Timecode.frameDurationMs(fps)

    /**
     * The shape of the picture the preview composes on: the crop if one is chosen,
     * otherwise the source, turned on its side when the edit is rotated a quarter
     * turn. Overlay offsets are fractions of this canvas, so getting it wrong would
     * move every layer.
     */
    val previewAspect: Float
        get() {
            cropAspect.ratio?.let { return it }
            if (sourceWidth <= 0 || sourceHeight <= 0) return 16f / 9f
            val quarterTurned = rotationDegrees % 180 != 0
            return if (quarterTurned) sourceHeight.toFloat() / sourceWidth
            else sourceWidth.toFloat() / sourceHeight
        }

    /** The look and the manual sliders folded together - what the GPU is asked for. */
    val grade: Grade
        get() = Looks.grade(lookId, lookIntensity, brightness, contrast, saturation)

    val hasSeparateAudio: Boolean get() = audioClips.isNotEmpty()

    /**
     * Which video clip the Motion panel acts on: whatever is selected, falling back
     * to the opening shot so the panel is never inert.
     */
    val targetVideoClip: Clip?
        get() = videoClips.firstOrNull { it.id == selectedClipId } ?: videoClips.firstOrNull()

    /**
     * Which audio clip the Audio panel acts on: whatever is selected, falling back
     * to the first, so the panel is never inert.
     */
    val targetAudioClip: Clip?
        get() = audioClips.firstOrNull { it.id == selectedClipId } ?: audioClips.firstOrNull()

    fun waveformFor(clip: Clip): Waveform? = clip.uri?.let { audioWaveforms[it.toString()] }

    /** Whether any audio at all reaches the exported file. */
    val hasAnyAudio: Boolean get() = (!muteOriginal && sourceHasAudio) || hasSeparateAudio
}

/**
 * The timeline the editor draws, assembled from the one authoritative copy of each
 * thing: the video track and the audio tracks as stored, and captions as their time
 * windows. Dragging a lane writes straight back to whichever of those owns it, so
 * nothing is ever mirrored into a second place.
 */
fun EditorUiState.toTimeline(): TimelineState {
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
        clips = videoClips + audioClips + captions,
        selectedClipId = selectedClipId,
        playheadMs = playheadMs,
        pixelsPerSecond = pixelsPerSecond
    )
}
