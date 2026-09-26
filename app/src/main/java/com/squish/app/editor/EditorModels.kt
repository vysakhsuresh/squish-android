package com.squish.app.editor

import android.net.Uri
import com.squish.app.data.ProjectSnapshot
import com.squish.app.media.ExportPresets
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

/**
 * How big an export comes out, named by its short edge - 720p, 1080p - the way
 * every phone and upload page names a video size. [ORIGINAL] keeps the source's
 * own frame.
 *
 * This replaced Small / Medium / High, which were fixed bitrates wearing
 * adjectives. A phone records heavier than "High" was, so High came out smaller
 * than the original and nobody could say why; a size in pixels says what it is.
 */
object OutputSize {
    const val ORIGINAL = 0
    val PRESETS = listOf(360, 480, 720, 1080, 1440, 2160)

    /** The range a hand-typed size may take. Above 4K, phone encoders refuse. */
    const val MIN_P = 144
    const val MAX_P = 2160

    fun label(p: Int): String = when (p) {
        ORIGINAL -> "Original"
        2160 -> "4K"
        else -> "${p}p"
    }

    /**
     * Where Squeeze starts for a source whose short edge is [sourceP]: the
     * largest named size below it, and no more than 720p. A fixed 720p default
     * made a 576p clip *bigger*, which is the opposite of what the tool is for.
     */
    fun squeezeDefault(sourceP: Int): Int {
        if (sourceP <= 0) return 720
        return PRESETS.lastOrNull { it < sourceP && it <= 720 } ?: ORIGINAL
    }

    /** The old quality names, read back out of drafts saved before sizes existed. */
    fun fromLegacyQuality(name: String?): Int? = when (name) {
        "Small" -> 360
        "Medium" -> 720
        "High" -> 1080
        "Original" -> ORIGINAL
        else -> null
    }
}

enum class CropAspect(val label: String, val ratio: Float?) {
    Original("Original", null),
    Portrait("9:16", 9f / 16f),
    Square("1:1", 1f),
    Landscape("16:9", 16f / 9f),

    /**
     * Drawn by hand on the picture.
     *
     * Has no ratio of its own: its shape is whatever rectangle was dragged, which
     * lives in [EditorUiState.cropRect]. The fixed ratios answer "what am I
     * posting to" and this answers "what is the shot" - different questions, and
     * only the first had a control. Cropping to a square took the middle square
     * whether or not the subject was in the middle.
     */
    Custom("Custom", null)
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

    /** How it is set. New captions get an outline, which reads on any picture. */
    val font: TextFont = TextFont.Sans,
    val look: TextLook = TextLook.Outline,
    val motion: TextMotion = TextMotion.None,

    /**
     * A sticker: an emoji placed on the picture. Drawn exactly like a caption -
     * the same renderer, motions, timeline lane and export - but listed in its own
     * panel and left out of anything that treats captions as words, like .srt.
     */
    val sticker: Boolean = false,

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

/**
 * The pulse of whichever track was analysed.
 *
 * [beatsMs] is in **timeline** time, already mapped out of the analysed clip's
 * source clock - so moving or ramping that clip afterwards leaves the beats where
 * they were, which is wrong, and re-running the analysis is the fix. That is a
 * deliberate trade: recomputing the grid on every drag would mean decoding the
 * audio again on every drag.
 */
data class BeatProgress(
    val running: Boolean = false,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val bpm: Float = 0f,
    val confidence: Float = 0f,
    val beatsMs: List<Long> = emptyList(),
    val downbeatOffset: Int = 0,
    /** Which clip was listened to, so the card can say so. */
    val clipLabel: String = ""
) {
    val hasBeats: Boolean get() = beatsMs.size >= 2

    /** Every nth beat from the downbeat: the cut points for "on the bar". */
    fun every(n: Int): List<Long> {
        if (n <= 1) return beatsMs
        return beatsMs.filterIndexed { i, _ -> (i - downbeatOffset).mod(n) == 0 }
    }
}

data class CaptionProgress(
    val running: Boolean = false,
    val stage: String = "",
    val total: Int = 0,
    val transcribed: Int = 0,
    /** Lines worked through so far, words or not - what the progress bar measures. */
    val done: Int = 0,
    val finished: Boolean = false,
    /** Finished because it was stopped part-way, rather than by running out of lines. */
    val stopped: Boolean = false,
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

/**
 * The part of the editor an undo should put back.
 *
 * Deliberately not the whole [EditorUiState]. Restoring that would drag the
 * playhead, the zoom, the scroll position and any analysis in flight backwards
 * with it, which is not what anyone means by undo — press it after a cut and the
 * playhead should stay where you are looking.
 *
 * Every field is an immutable list or a primitive, so a snapshot costs a handful
 * of references rather than a copy of the edit.
 */
data class EditSnapshot(
    val videoClips: List<Clip>,
    val audioClips: List<Clip>,
    val textOverlays: List<TextOverlayItem>,
    val effects: List<TimedEffect>,
    val markers: List<Long>,
    val selectedClipId: String?,
    val muteOriginal: Boolean,
    val originalVolume: Float,
    val rotationDegrees: Int,
    val cropAspect: CropAspect,
    val cropRect: CropRect,
    val lookId: String?,
    val lookIntensity: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float
)

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

    /** The export's short edge, or [OutputSize.ORIGINAL]. See [OutputSize]. */
    val outputP: Int = OutputSize.ORIGINAL,
    /**
     * The source picture's bitrate, when the caller knows better than the file's
     * weight over its length - a merge, whose weight is several files'. 0 means
     * work it out from [originalSizeBytes] and [durationMs].
     */
    val sourceVideoBps: Long = 0,
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
    /** Timed effects from the library - shake, glitch, flash and the rest. */
    val effects: List<TimedEffect> = emptyList(),
    val captions: CaptionProgress = CaptionProgress(),
    val stabilize: StabilizeProgress = StabilizeProgress(),
    val stabilizeStrength: Float = 0.5f,
    val tracking: TrackProgress = TrackProgress(),
    val beats: BeatProgress = BeatProgress(),

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

    /** The hand-drawn crop, used when [cropAspect] is [CropAspect.Custom]. */
    val cropRect: CropRect = CropRect(),

    val selectedClipId: String? = null,
    val pixelsPerSecond: Float = 42f,
    /**
     * Bumped to ask the strip to fit the whole edit across its width.
     *
     * A request rather than a value, because only the strip knows how wide it is.
     * It answers by calling back with a zoom, which is the one piece of layout
     * the view model cannot work out for itself.
     */
    val fitNonce: Long = 0,

    /** What pressing undo would reverse, or null when there is nothing to. */
    val undoLabel: String? = null,
    val redoLabel: String? = null,

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
            if (cropAspect == CropAspect.Custom) return cropRect.aspect(sourceFrameAspect)
            cropAspect.ratio?.let { return it }
            return sourceFrameAspect
        }

    /** The rectangle actually kept, whichever way the crop was chosen. */
    val effectiveCrop: CropRect
        get() = when {
            cropAspect == CropAspect.Custom -> cropRect
            cropAspect.ratio != null -> CropRect.centred(cropAspect.ratio, sourceFrameAspect)
            else -> CropRect()
        }

    /**
     * The shape of the footage itself, whatever crop is set.
     *
     * The preview is fitted to this rather than to the crop, so the whole frame
     * is on screen and the crop is drawn over it. Fitting to the crop instead
     * meant the part being cropped away was never visible, which makes choosing
     * a crop a matter of guessing and checking the export.
     */
    val sourceFrameAspect: Float
        get() {
            if (sourceWidth <= 0 || sourceHeight <= 0) return PreviewBox.DEFAULT_ASPECT
            val quarterTurned = rotationDegrees % 180 != 0
            return if (quarterTurned) sourceHeight.toFloat() / sourceWidth
            else sourceWidth.toFloat() / sourceHeight
        }

    /** Everything an undo would restore, as it stands. */
    val editSnapshot: EditSnapshot
        get() = EditSnapshot(
            videoClips = videoClips,
            audioClips = audioClips,
            textOverlays = textOverlays,
            effects = effects,
            markers = markers,
            selectedClipId = selectedClipId,
            muteOriginal = muteOriginal,
            originalVolume = originalVolume,
            rotationDegrees = rotationDegrees,
            cropAspect = cropAspect,
            cropRect = cropRect,
            lookId = lookId,
            lookIntensity = lookIntensity,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation
        )

    /** The same fields put back, leaving the playhead and the zoom where they are. */
    fun restoring(snapshot: EditSnapshot): EditorUiState = copy(
        videoClips = snapshot.videoClips,
        audioClips = snapshot.audioClips,
        textOverlays = snapshot.textOverlays,
        effects = snapshot.effects,
        markers = snapshot.markers,
        selectedClipId = snapshot.selectedClipId,
        muteOriginal = snapshot.muteOriginal,
        originalVolume = snapshot.originalVolume,
        rotationDegrees = snapshot.rotationDegrees,
        cropAspect = snapshot.cropAspect,
        cropRect = snapshot.cropRect,
        lookId = snapshot.lookId,
        lookIntensity = snapshot.lookIntensity,
        brightness = snapshot.brightness,
        contrast = snapshot.contrast,
        saturation = snapshot.saturation
    )

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

    /** The frame the chosen size produces, before any crop. */
    val outputResolution: ExportPresets.Resolution
        get() = ExportPresets.resolutionFor(outputP, sourceWidth, sourceHeight)

    /**
     * The video bitrate this export is written at. One definition, read by the
     * estimate and by the encoder, so the size promised is the size delivered.
     */
    val exportVideoBitrate: Int
        get() = if (fitToSize) {
            ExportPresets.bitrateForTargetSize(targetSizeMb * 1_000_000L, trimmedDurationMs, hasAnyAudio)
        } else {
            val sourceBps = sourceVideoBps.takeIf { it > 0 }
                ?: ExportPresets.sourceVideoBitrate(originalSizeBytes, durationMs, sourceHasAudio)
            ExportPresets.bitrateFor(outputP, sourceWidth, sourceHeight, fps, sourceBps)
        }

    /** What the finished file should weigh. */
    val estimatedExportBytes: Long
        get() {
            val seconds = trimmedDurationMs / 1000.0
            if (audioOnly) return (ExportPresets.AUDIO_BITRATE_BPS * seconds / 8).toLong()
            val audioBits = if (hasAnyAudio) ExportPresets.AUDIO_BITRATE_BPS * seconds else 0.0
            return ((exportVideoBitrate * seconds + audioBits) / 8).toLong()
        }
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
