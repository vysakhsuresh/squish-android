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
import com.squish.app.timeline.EffectSpan
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transform
import com.squish.app.timeline.transformAt

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
    val track: MotionTrack? = null,

    /**
     * Which of the text rows on the timeline strip this line would rather be on,
     * where nothing else is in the way there (see TimelineLanes.rows). Only where
     * it is drawn: it changes nothing on the picture.
     */
    val stripRow: Int = 0
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
     *
     * Mapped through the clip's speed curve rather than by one constant offset.
     * The player's clock is source time and a retimed clip runs it faster or
     * slower than the timeline, so over a clip at double speed a caption written
     * for 4-6 s of the timeline used to show for 2-3 s instead.
     */
    fun shiftedInto(clip: Clip): TextOverlayItem = copy(
        startMs = clip.sourceAtExtended(startMs),
        endMs = clip.sourceAtExtended(endMs),
        track = track?.let { t ->
            MotionTrack(t.samples.map { it.copy(atMs = clip.sourceAtExtended(it.atMs)) })
        }
    )
}

/**
 * Marks a caption made by auto-captioning, in its id. A second run replaces the
 * first run's lines rather than stacking a copy of every one on top of them, and
 * the id is the one thing about a caption every draft already keeps.
 */
const val AUTO_CAPTION_PREFIX = "auto-"

val TextOverlayItem.isAutoCaption: Boolean get() = id.startsWith(AUTO_CAPTION_PREFIX)

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

/**
 * How stabilization analysis is going, and what it cost.
 *
 * [clipId] says which clip all of that is about. There is one analysis at a time
 * but many clips, and without it the card reported clip A's "zoomed in 8%" - or
 * its failure - on whichever clip was selected next.
 */
data class StabilizeProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val crop: Float = 0f,
    val framesAnalysed: Int = 0,
    val failed: Boolean = false,
    val clipId: String? = null
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
    val clipLabel: String = "",
    /**
     * What is being listened to while [running]. Separate from [clipLabel] so that
     * a new analysis leaves the grid already found in place until it has an
     * answer: undo can then put back the grid as it was, rather than a grid that
     * says it is still listening to something.
     */
    val listeningTo: String = ""
) {
    val hasBeats: Boolean get() = beatsMs.size >= 2

    /** The grid alone, as undo keeps it: no analysis in flight, no last failure. */
    val settled: BeatProgress
        get() = if (!running && !failed && listeningTo.isEmpty()) this
        else copy(running = false, failed = false, listeningTo = "", finished = hasBeats)

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
    val recognitionAvailable: Boolean = true,
    /** The video has no sound to listen to, which is not the same as no speech in it. */
    val noAudio: Boolean = false,
    /**
     * Lines brought in from a subtitle file. Nothing was listened to or
     * transcribed, and saying "N lines timed, N transcribed" claimed it had.
     */
    val imported: Int = 0
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
data class RecoveryOffer(
    val snapshot: ProjectSnapshot,
    /**
     * Whether the offer blocks the editor until it is answered. It does after
     * the app was killed under this edit: the person was in the middle of it,
     * and the editor cannot save anything until they say which edit this is.
     */
    val modal: Boolean = false
) {
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
    val reframe: MotionTrack?,
    val markers: List<Long>,
    val selectedClipId: String?,
    val muteOriginal: Boolean,
    val voiceEffect: VoiceEffect,
    val originalVolume: Float,
    val rotationDegrees: Int,
    val cropAspect: CropAspect,
    val cropRect: CropRect,
    val lookId: String?,
    val lookIntensity: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    /**
     * The beat grid, as [BeatProgress.settled]. Scaling it, shifting the bar and
     * clearing it are edits like any other, and were the only ones undo could
     * not reach.
     */
    val beats: BeatProgress,
    val stabilizeStrength: Float
)

data class EditorUiState(
    val sourceUri: Uri? = null,
    /**
     * What the person called this project, or null for none yet - the header
     * and the drafts list then show the first clip's name, as they always did.
     * Not an undo step: naming the project is not an edit to the video. It is
     * saved with the draft, so a rename alone is enough to keep one.
     */
    val projectName: String? = null,
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
    /** A voice effect on the clip's own sound; see [VoiceEffect]. */
    val voiceEffect: VoiceEffect = VoiceEffect.None,
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
    /**
     * Auto-reframe: where the frame-shape crop is centred through the clip, in
     * the main source's time. Null keeps the crop centred. Only used with a
     * fixed shape (9:16, 1:1, 16:9).
     */
    val reframe: MotionTrack? = null,
    val reframeProgress: ReframeProgress = ReframeProgress(),
    /** Finding the person in a clip, for background removal. */
    val backgroundProgress: ReframeProgress = ReframeProgress(),
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

    /**
     * Proxy media: each heavy file's light stand-in, by the file's address. The
     * preview plays these; the export never does. Per file, not per project -
     * only the file first opened used to get one, so a 4K reaction clip over a
     * 4K shot was two full-size decodes in the preview (P8).
     */
    val proxyUris: Map<Uri, Uri> = emptyMap(),
    /** Where each file that needs a stand-in has got to. A file that needs none is not here. */
    val proxyStatuses: Map<Uri, ProxyStatus> = emptyMap(),
    /** How many photos or blanks are being made into clips right now. */
    val preparingStills: Int = 0,

    // A session that survived the process being killed, waiting to be accepted.
    val recovery: RecoveryOffer? = null,
    /**
     * Set when editing the bare clip answered the offer above for the user: the
     * saved edit went to the bin, and the editor says so rather than letting it
     * vanish without a word. Undoing back to the bare clip brings the offer back.
     */
    val setAsideNotice: Boolean = false,

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

    /**
     * The shape the preview should frame to, or null for the whole picture.
     *
     * The preview crops by aspect, which a hand-drawn rectangle can be reduced to
     * for framing purposes even though the export cuts the rectangle itself.
     */
    val previewCropRatio: Float?
        get() = when {
            cropAspect == CropAspect.Custom ->
                if (cropRect.isFull) null else cropRect.aspect(sourceFrameAspect)
            else -> cropAspect.ratio
        }

    /** The rectangle actually kept, whichever way the crop was chosen. */
    val effectiveCrop: CropRect
        get() = when {
            cropAspect == CropAspect.Custom -> cropRect
            cropAspect.ratio != null -> CropRect.centred(cropAspect.ratio, sourceFrameAspect)
            else -> CropRect()
        }

    /** True when the user's rotation transposes the frame. */
    val quarterTurned: Boolean get() = rotationDegrees % 180 != 0

    /**
     * The frame's width *after* the rotation has been applied, and its height.
     *
     * Everything downstream of the rotation in the render chain has to measure
     * against these rather than against the source's own numbers. Rotating a
     * 1080x1920 clip by ninety degrees hands the next effect a 1920x1080 frame,
     * and an output size worked out from 1080x1920 then asks it to fit a
     * landscape picture into a portrait box - which is the distorted, letterboxed
     * export that came out of pressing Rotate.
     *
     * The exporter had this right in one place and wrong in two others, which is
     * the argument for it living here instead of being recomputed per call site.
     */
    val framedWidth: Int get() = if (quarterTurned) sourceHeight else sourceWidth
    val framedHeight: Int get() = if (quarterTurned) sourceWidth else sourceHeight

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
            if (framedWidth <= 0 || framedHeight <= 0) return PreviewBox.DEFAULT_ASPECT
            return framedWidth.toFloat() / framedHeight
        }

    /** Everything an undo would restore, as it stands. */
    val editSnapshot: EditSnapshot
        get() = EditSnapshot(
            videoClips = videoClips,
            audioClips = audioClips,
            textOverlays = textOverlays,
            effects = effects,
            reframe = reframe,
            markers = markers,
            selectedClipId = selectedClipId,
            muteOriginal = muteOriginal,
            voiceEffect = voiceEffect,
            originalVolume = originalVolume,
            rotationDegrees = rotationDegrees,
            cropAspect = cropAspect,
            cropRect = cropRect,
            lookId = lookId,
            lookIntensity = lookIntensity,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            beats = beats.settled,
            stabilizeStrength = stabilizeStrength
        )

    /** The same fields put back, leaving the playhead and the zoom where they are. */
    fun restoring(snapshot: EditSnapshot): EditorUiState = copy(
        videoClips = snapshot.videoClips,
        audioClips = snapshot.audioClips,
        textOverlays = snapshot.textOverlays,
        effects = snapshot.effects,
        reframe = snapshot.reframe,
        markers = snapshot.markers,
        selectedClipId = snapshot.selectedClipId,
        muteOriginal = snapshot.muteOriginal,
        voiceEffect = snapshot.voiceEffect,
        originalVolume = snapshot.originalVolume,
        rotationDegrees = snapshot.rotationDegrees,
        cropAspect = snapshot.cropAspect,
        cropRect = snapshot.cropRect,
        lookId = snapshot.lookId,
        lookIntensity = snapshot.lookIntensity,
        brightness = snapshot.brightness,
        contrast = snapshot.contrast,
        saturation = snapshot.saturation,
        // An analysis still listening keeps listening; the grid under it is
        // what goes back.
        beats = if (beats.running) snapshot.beats.copy(running = true, listeningTo = beats.listeningTo)
        else snapshot.beats,
        stabilizeStrength = snapshot.stabilizeStrength
    )

    /** The look and the manual sliders folded together - what the GPU is asked for. */
    val grade: Grade
        get() = Looks.grade(lookId, lookIntensity, brightness, contrast, saturation)

    val hasSeparateAudio: Boolean get() = audioClips.isNotEmpty()

    /**
     * Which audio clip the Audio panel acts on: whatever is selected, falling back
     * to the first, so the panel is never inert.
     */
    val targetAudioClip: Clip?
        get() = audioClips.firstOrNull { it.id == selectedClipId } ?: audioClips.firstOrNull()

    fun waveformFor(clip: Clip): Waveform? = clip.uri?.let { audioWaveforms[it.toString()] }

    /** The first shot of the main track: the picture everything else is lined up against. */
    val headVideoClip: Clip?
        get() = videoClips.filter { it.layer == 0 }.minByOrNull { it.timelineStartMs }

    /**
     * How far the head shot's file runs ahead of the timeline: file time minus
     * timeline time at its first frame. Zero for an untrimmed clip at the start,
     * and exactly the error every sync calculation made once it was not.
     *
     * Taken at the first frame because that is the one moment whose file time
     * is known whatever the shot's speed curve: the curve starts there. On a
     * retimed shot file time and timeline time drift apart from there on, and a
     * sound at its own speed cannot follow - one delta can match it at one
     * moment only. The Sound panel says so when the head shot is retimed.
     */
    val headPictureDeltaMs: Long
        get() = headVideoClip?.let { it.sourceInMs - it.timelineStartMs } ?: 0L

    /**
     * The main-track clip showing at [timelineMs]: the one it falls inside, or on
     * the very last frame of the edit, the last clip.
     */
    fun baseClipAt(timelineMs: Long): Clip? {
        val base = videoClips.filter { it.layer == 0 }
        return base.firstOrNull { timelineMs >= it.timelineStartMs && timelineMs < it.timelineEndMs }
            ?: base.maxByOrNull { it.timelineEndMs }?.takeIf { it.timelineEndMs == timelineMs }
    }

    /**
     * Whether any audio at all reaches the exported file: the shots' own sound,
     * an overlay's, or an added one.
     *
     * An overlay counts only when its file has sound this phone decodes, as far
     * as the background check has said: a silent screen recording used to count,
     * and a size-targeted export set aside audio bits for a track that was never
     * written. Not yet checked counts as sound, the safe side of the estimate.
     */
    val hasAnyAudio: Boolean
        get() = (!muteOriginal && sourceHasAudio) || hasSeparateAudio ||
            videoClips.any { clip ->
                clip.isOverlay && clip.volume > 0f && !clip.isStillPicture &&
                    clip.uri?.let { com.squish.app.media.MediaCompat.cached(it) }
                        .let { it == null || (it.hasAudio && it.audioProblem == null) }
            }

    /** The stand-in for the file first opened, for the analyses that read it. */
    val proxyUri: Uri? get() = sourceUri?.let { proxyUris[it] }

    /** The stand-ins of the files still in the edit, by where each has got to. */
    val proxiesInEdit: Map<Uri, ProxyStatus>
        get() {
            val inEdit = videoClips.mapNotNull { it.uri }.toSet().ifEmpty { setOfNotNull(sourceUri) }
            return proxyStatuses.filterKeys { it in inEdit }
        }

    /**
     * The stand-ins taken together, for the one line that reports them: building
     * while any is, failed if any did, ready if any is.
     */
    val proxyStatus: ProxyStatus
        get() {
            val statuses = proxiesInEdit.values
            return when {
                ProxyStatus.Building in statuses -> ProxyStatus.Building
                ProxyStatus.Failed in statuses -> ProxyStatus.Failed
                ProxyStatus.Ready in statuses -> ProxyStatus.Ready
                else -> ProxyStatus.NotNeeded
            }
        }

    /** The part of the rotated frame the crop keeps, in pixels, before any size is chosen. */
    val croppedFrame: ExportPresets.Resolution
        get() = effectiveCrop.let { ExportPresets.croppedFrame(framedWidth, framedHeight, it.width, it.height) }

    /**
     * The frame the file is written at: what the crop keeps, at the chosen size.
     * Fit-to-size keeps the kept part's own size and spends its budget on bitrate.
     */
    val outputResolution: ExportPresets.Resolution
        // The rotated shape, not the shot one. This sits after the rotation in
        // the render chain, so measured against the source's own numbers it asks
        // a turned frame to fit a box the wrong way round - which is the
        // letterboxed, wrong-shaped file that pressing Rotate produced. And the
        // cropped one: see ExportPresets.croppedFrame.
        get() = effectiveCrop.let {
            ExportPresets.canvasFor(if (fitToSize) OutputSize.ORIGINAL else outputP, framedWidth, framedHeight, it.width, it.height)
        }

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
            ExportPresets.bitrateForFrame(outputResolution, sourceWidth, sourceHeight, fps, sourceBps)
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
 * Where the editor asked this clip to sit at a moment of the timeline - keys or
 * placement - without the stabilizer's correction.
 *
 * [Clip.transformAt] is what is drawn, and it includes the stabilizer. Reading
 * that, changing one field and writing the result back as the user's transform
 * put the stabilizer in twice: nudging any Motion slider on a stabilized clip
 * doubled its zoom and froze one frame's shake correction into the whole clip.
 * Anything that edits the transform starts from this.
 */
fun Clip.userTransformAt(timelineMs: Long): Transform =
    keyframes.transformAt(timelineMs - timelineStartMs, staticTransform)

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
            text = overlay.text,
            // The strip reads a line's preferred row where it reads a sound's.
            layer = overlay.stripRow
        )
    }

    return TimelineState(
        clips = videoClips + audioClips + captions,
        selectedClipId = selectedClipId,
        playheadMs = playheadMs,
        pixelsPerSecond = pixelsPerSecond,
        waveforms = audioWaveforms,
        effects = effects.map { e ->
            EffectSpan(e.id, e.kind.label, e.startMs, e.endMs, e.kind.icon, e.kind.color)
        },
        pictureEndMs = trimmedDurationMs
    )
}

/** Where an auto-reframe analysis has got to. */
data class ReframeProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Boolean = false
)
