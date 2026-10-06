package com.squish.app.editor

import android.net.Uri
import com.squish.app.media.ExportPlan
import com.squish.app.media.ExportPresets
import com.squish.app.media.ExportProgress
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings
import com.squish.app.media.MediaCompat
import com.squish.app.media.SquishError
import com.squish.app.media.audio.Waveform
import com.squish.app.media.video.MotionTrack
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipAttributes
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.selectionAfterRestore
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

/**
 * The shape of the edit's frame. A fixed ratio cuts the picture to that shape,
 * or - with a background set (see CanvasBackground) - is a canvas the picture
 * is fitted whole into. The list is the one every upload page asks for.
 */
enum class CropAspect(val label: String, val ratio: Float?) {
    Original("Original", null),
    Portrait("9:16", 9f / 16f),
    Square("1:1", 1f),
    Landscape("16:9", 16f / 9f),
    ThreeFour("3:4", 3f / 4f),
    FourThree("4:3", 4f / 3f),
    FourFive("4:5", 4f / 5f),
    TwoOne("2:1", 2f),
    Cinema("2.35:1", 2.35f),

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

enum class SyncStatus { Idle, Analyzing, Matched, NoMatch }

/** Whose speech auto-captions listen to: the shots' own sound, the sounds added over them, or both. */
enum class CaptionSource(val label: String) {
    Camera("Camera sound"),
    Sounds("Added sounds"),
    Both("Both")
}

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
    val boxFraction: Float = 0.14f,
    /** What the track was measured on, so it is not pinned to other footage (a clip reversed or replaced since). */
    val trackedUri: String? = null,
    val trackedReversed: Boolean = false
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
 * The pulse of whichever track was analysed: its tempo, how much to trust it,
 * and which beat the bar starts on.
 *
 * The beats themselves live on the sound that was listened to
 * ([Clip.beats], in the file's own time), named here by [clipId], so they
 * travel with the clip and through its speed curve; the grid on the timeline
 * is read through [EditorUiState.beatGrid]. Only a grid found on the camera's
 * own audio - which no sound clip can carry - is kept here in [beatsMs], in
 * timeline time, as every grid was before beats moved onto the clip.
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
    /** The sound the beats are on, or null for a grid on the camera audio. */
    val clipId: String? = null,
    /**
     * The density: every beat, every second one, or every bar. A toggle the
     * dots on the clip, "Snap to the beat" and "Cut on the beat" all read, so
     * what is drawn is what a cut lands on.
     */
    val every: Int = 1,
    /**
     * What is being listened to while [running]. Separate from [clipLabel] so that
     * a new analysis leaves the grid already found in place until it has an
     * answer: undo can then put back the grid as it was, rather than a grid that
     * says it is still listening to something.
     */
    val listeningTo: String = "",
    /**
     * Why the last listen failed, when the answer was "there is a pulse, but
     * not where this clip plays".
     *
     * The listen covers the first six minutes of the *file* and the grid is
     * read through the clip's own window, so a sound trimmed to start after
     * that comes back with a full BeatMap of which no dot is reachable. Without
     * somewhere to say so, that took the success path and the panel showed the
     * words it had before the tap. Not written to the draft: like [failed], it
     * is about the last attempt and not about the edit.
     */
    val failedOutsideWindow: Boolean = false
) {
    /** Whether a grid was found: on a clip, or on the camera audio. */
    val hasBeats: Boolean get() = beatsMs.size >= 2 || clipId != null

    /**
     * Whether there is anything here worth writing to the draft.
     *
     * Not the same question as [hasBeats], which asks whether a *grid* was
     * found and wants two beats before it says yes. One beat somebody tapped
     * with "Add beat" is a live part of the edit - it is drawn on the ruler and
     * drags snap to it, because nothing that reads the grid applies that gate -
     * and the draft was written and read under `hasBeats`, so that beat, the
     * density and the downbeat went missing on every save.
     */
    val worthSaving: Boolean get() = beatsMs.isNotEmpty() || clipId != null

    /** The grid alone, as undo keeps it: no analysis in flight, no last failure. */
    val settled: BeatProgress
        get() = if (!running && !failed && !failedOutsideWindow && listeningTo.isEmpty()) this
        else copy(running = false, failed = false, failedOutsideWindow = false, listeningTo = "", finished = hasBeats)
}

/**
 * A take being recorded over the timeline, from the Sound sheet's Record.
 *
 * The count-in gives the picture a moment to be looked at and the mic a moment
 * to open; then the timeline plays, silently, while the mic listens, until
 * Stop or the end of the edit. [replacesId] is a take being done again, on
 * purpose: the new one lands where it was and it goes.
 */
data class RecordingState(
    val phase: Phase = Phase.Idle,
    /** Seconds left in the count-in, while [Phase.Countdown]. */
    val countdown: Int = 0,
    /** Where on the timeline the take starts. */
    val startMs: Long = 0L,
    /** How much the mic has heard so far, in ms: what the panel's clock and the strip's growing take read. */
    val recordedMs: Long = 0L,
    /** The mic's level just now, 0..1, for the meter. */
    val level: Float = 0f,
    val replacesId: String? = null,
    /**
     * The picture did not start playing within a few seconds of the mic
     * opening. The take goes on without it rather than ending on its own
     * mid-sentence, and the panel says so.
     */
    val pictureStalled: Boolean = false,
    /** The mic could not be opened, or nothing was heard; shown until the next attempt. */
    val failed: Boolean = false
) {
    enum class Phase { Idle, Countdown, Recording, Saving }

    /** Whether the editor's sound should be held quiet: from the count-in until the take is saved. */
    val active: Boolean get() = phase != Phase.Idle

    /** Where the take being made ends on the timeline just now. */
    val endMs: Long get() = startMs + recordedMs
}

/**
 * The id of the take being recorded as the strip draws it: a sound clip that
 * grows under the playhead so the take is seen against the picture, as
 * CapCut's is. It is the strip's alone - not in the edit, not played, not
 * saved - and a tap on it selects nothing.
 */
const val RECORDING_CLIP_ID = "recording"

/**
 * The editor asking the preview to play or to pause - the one direction the
 * transport did not have. Playback is the preview's own (its button, a tap on
 * the picture); the editor reads what it is doing. A recording that has to
 * start the timeline, and a song auditioned that has to stop it, ask through
 * this. [nonce] tells a new request from the last one answered.
 */
data class TransportRequest(val play: Boolean, val nonce: Long)

data class CaptionProgress(
    val running: Boolean = false,
    val stage: String = "",
    val total: Int = 0,
    val transcribed: Int = 0,
    /** Lines worked through so far, words or not - what the progress bar measures. */
    val done: Int = 0,
    /**
     * Lines actually put on the timeline.
     *
     * Not [done]: a stretch the detector planned can come back with no words at
     * all - music or street noise under no speech - and is held back rather
     * than landed. Stopping there, the card read "the lines made so far are on
     * the timeline" off [done] when nothing had been added.
     */
    val landed: Int = 0,
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
    val imported: Int = 0,
    /** Sound was found but no line held words: music or noise, and nothing was added. */
    val noWords: Boolean = false
)

/**
 * Where the low-resolution stand-in for heavy footage has got to. Only 4K-and-up
 * sources ever leave [NotNeeded]; everything smaller plays fine as it is.
 */
enum class ProxyStatus { NotNeeded, Building, Ready, Failed }

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
    val canvasBackground: CanvasBackground,
    /**
     * The beat grid, as [BeatProgress.settled]. Scaling it, shifting the bar and
     * clearing it are edits like any other, and were the only ones undo could
     * not reach.
     */
    val beats: BeatProgress,
    val stabilizeStrength: Float
)

/**
 * A fitted export that came out over its limit. The file is whole, in the
 * gallery and in the library - a render is never thrown away for missing by
 * a few per cent - and the sheet asks whether to keep it or run once more,
 * tighter (ExportSettings.retryScale).
 */
data class FitOvershoot(
    val path: String,
    val actualBytes: Long,
    val targetBytes: Long
)

data class EditorUiState(
    /**
     * Which project this is (ProjectRules.newId): the draft's slot on disk,
     * and the id in the editor's route. Blank until the editor is opened on
     * one, and nothing is saved while it is. A project used to be its first
     * video, so two cuts of the same footage could not both exist.
     */
    val projectId: String = "",
    val sourceUri: Uri? = null,
    /**
     * What the person called this project, or null for none yet - the header
     * and the drafts list then show the first clip's name, as they always did.
     * Not an undo step: naming the project is not an edit to the video. It is
     * saved with the draft, so a rename alone is enough to keep one.
     */
    val projectName: String? = null,
    /**
     * The file's own name, read once when it is opened. Read again on Render,
     * a document provider's round trip stalled the one button that has to
     * feel immediate; the export's title and its record take this instead.
     */
    val sourceName: String? = null,
    /** When the project was started, for the name it shows until it is given one. */
    val startedAtMillis: Long = 0L,
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
    /** The file's frame rate, or [ExportSettings.SOURCE_FPS] for the footage's own. */
    val outputFps: Int = ExportSettings.SOURCE_FPS,
    val quality: ExportQuality = ExportQuality.Recommended,
    /** "Smaller file": written in HEVC when this phone has an encoder for it; see [exportCodecHevc]. */
    val hevc: Boolean = false,
    /** Keep an HDR source's HDR rather than converting it to ordinary colour, the default. */
    val keepHdr: Boolean = false,
    /** Whether this phone has an HEVC encoder: null until asked (EditorViewModel.probeCodecs). */
    val hevcAvailable: Boolean? = null,
    /** The largest short edge this phone's encoder writes for the edit's frame shape; 0 until asked. */
    val encoderCeilingP: Int = 0,
    /** Bitrate scale for the next fit-to-size run, pulled down after one that missed; see [FitOvershoot]. */
    val fitScale: Float = 1f,
    /** A fitted export that came out over its limit, kept, and waiting to be accepted or run again. */
    val fitOvershoot: FitOvershoot? = null,

    val muteOriginal: Boolean = false,
    val originalVolume: Float = 1f,
    val rotationDegrees: Int = 0,
    /**
     * The platform furniture drawn over the picture as a guide, or null for
     * none. A view setting, not an edit: it is never written into the file and
     * never costs an undo step, so it is not in the draft either.
     */
    val safeArea: SafeArea? = null,
    val cropAspect: CropAspect = CropAspect.Original,
    /**
     * What fills a ratio's canvas round the picture, when the picture is fitted
     * into it rather than cut to it; see [CanvasBackground]. The look and the
     * colour sliders are each clip's own now (Clip.grade), not the edit's.
     */
    val canvasBackground: CanvasBackground = CanvasBackground.NONE,

    val textOverlays: List<TextOverlayItem> = emptyList(),
    /** A line's style, copied and waiting to be pasted onto another. Not an edit until it is. */
    val styleClipboard: TextStyleSpec? = null,
    /**
     * What auto-captions listen to, and in which language (null: the phone's
     * own). Settings, not edits. The camera alone unless asked: a song added
     * from Sound is a sound too, and listening to everything by default put a
     * caption on every loud bar of it, over the lines the speech had.
     */
    val captionSource: CaptionSource = CaptionSource.Camera,
    val captionLanguage: String? = null,
    /** The line being read aloud into a sound clip, while the voice is made. */
    val speakingId: String? = null,
    /** Timed effects from the library - shake, glitch, flash and the rest. */
    val effects: List<TimedEffect> = emptyList(),
    /** Where auto-reframe has got to; the tracks themselves are on the shots (Clip.reframe). */
    val reframeProgress: ReframeProgress = ReframeProgress(),
    /** Finding the person in a clip, for background removal. */
    val backgroundProgress: ReframeProgress = ReframeProgress(),
    /** Measuring how much is happening in each shot, for "Use the liveliest bit". */
    val bestBitsProgress: ReframeProgress = ReframeProgress(),
    val captions: CaptionProgress = CaptionProgress(),
    val stabilize: StabilizeProgress = StabilizeProgress(),
    val stabilizeStrength: Float = 0.5f,
    val tracking: TrackProgress = TrackProgress(),
    val beats: BeatProgress = BeatProgress(),
    /** A voiceover being taken; see [RecordingState]. */
    val recording: RecordingState = RecordingState(),
    /** The editor's last ask of the preview's transport; see [TransportRequest]. */
    val transportRequest: TransportRequest? = null,

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
    /**
     * Which sound the sync result above is about.
     *
     * Without it the line was editor-wide and never cleared on a change of
     * selection, so every sound's Sync sheet reported the last sound's result
     * as its own - a second take opened saying "Matched · 94% confidence"
     * before anything had listened to it.
     */
    val syncClipId: String? = null,

    /** The hand-drawn crop, used when [cropAspect] is [CropAspect.Custom]. */
    val cropRect: CropRect = CropRect(),

    val selectedClipId: String? = null,
    /**
     * The clips selected alongside [selectedClipId] - Select more, then taps on
     * the strip. Delete and a carry act on all of them. Let go of with the
     * selection, as any selection is; not an undo step either.
     */
    val selectedClipIds: Set<String> = emptySet(),
    /** Taps on the strip add to the selection rather than replace it: Select more is on. */
    val selectingMore: Boolean = false,
    /** A file picked to go under a clip, waiting on where in it to start; see [ReplaceRequest]. */
    val replacing: ReplaceRequest? = null,
    /** A clip's settings, copied and waiting to be pasted onto another. Not an edit until they are. */
    val attributeClipboard: ClipAttributes? = null,
    /** The clips whose reversed render is being made, each with how far along it is (0..1). */
    val reversing: Map<String, Float> = emptyMap(),
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
    /**
     * How far the stand-in being built has got, 0..100, once its encoder has
     * said; null between builds. One at a time, so one number.
     */
    val proxyPercent: Int? = null,
    /** How many photos or blanks are being made into clips right now. */
    val preparingStills: Int = 0,

    /**
     * A file in the edit that can no longer be opened - deleted from the
     * gallery, or a grant that did not outlive the process - named when the
     * project is opened, with the clips that play it left on the strip as
     * placeholders. Relink (EditorViewModel.relink) puts another file under
     * every one of them; null once it has, or when nothing is missing.
     */
    val missingMedia: Uri? = null,

    // The last failure, in sentences. Null whenever the editor is healthy.
    val failure: SquishError? = null,

    val isExporting: Boolean = false,
    /** What the encoder says it has done, while it is doing it. */
    val exportProgress: ExportProgress = ExportProgress(),
    val estimatedOutputBytes: Long = 0,
    /** What the phone's encoder will write for [outputResolution], once asked; see [writtenResolution]. */
    val encoderAnswer: ExportPresets.EncoderAnswer? = null
) {
    /**
     * How long the finished video runs.
     *
     * This is where the last frame *lands*, not the sum of the clip lengths. Once
     * clips can be dragged apart - which is what makes manual sync possible - a
     * gap is part of the edit, and summing durations would report a video shorter
     * than the one that actually gets written.
     *
     * Sounds count. A song added to an edit is cut to end with the picture
     * (EditRules.soundLanding), so a file is never longer than its shots by
     * accident; but one dragged out past the last shot runs on, over black, as
     * the strip shows it and the preview plays it - and the file is the same
     * length, as in every other editor. It used to stop at the last shot while
     * the preview played on, so the preview promised a stretch the file left
     * out. One number, read by the header, the export sheet, the file and the
     * recovery card alike.
     */
    val trimmedDurationMs: Long
        get() {
            val laid = maxOf(
                videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L,
                audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L
            )
            // The source file's own window only when *nothing* has been laid
            // down, which is what a project looks like before its clips are
            // made. It used to be whenever there was no *picture*, so an edit
            // whose shots had all been deleted - which is a supported state,
            // and the one a sound-only edit is in - reported the length of the
            // file it was opened on instead of its own. That number is the
            // header, the export sheet, the file's length and the recovery
            // card, and the drafts list writes it into the sidecar.
            return if (laid > 0L) laid else (trimEndMs - trimStartMs).coerceAtLeast(0L)
        }

    /** The full span the strip has to cover: the edit, and any line an old draft left past its end. */
    val timelineDurationMs: Long
        get() = maxOf(
            trimmedDurationMs,
            audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L,
            textOverlays.maxOfOrNull { it.endMs } ?: 0L
        )

    val frameMs: Long get() = Timecode.frameDurationMs(fps)

    /** Every clip selected, the lead included: what Delete and a carry act on with several selected. */
    val allSelectedIds: Set<String> get() = selectedClipIds + setOfNotNull(selectedClipId)

    /** Whether several things are selected at once, so the toolbar shows what acts on all of them. */
    val multiSelected: Boolean get() = selectedClipIds.isNotEmpty()

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
     * Whether the picture is fitted whole into a canvas of the chosen ratio,
     * with a background round it, rather than cut to the ratio. Only a fixed
     * ratio makes a canvas; a hand-drawn rectangle is the frame itself.
     */
    val paddedCanvas: Boolean get() = canvasBackground.pads && cropAspect.ratio != null

    /**
     * The shape of the canvas the preview composes on and the export writes:
     * the ratio when the frame is padded, else the picture's own shape. The
     * crop is drawn over the picture in the second case, not applied to the
     * canvas, so the whole frame stays on screen while it is chosen.
     */
    val canvasAspect: Float
        get() = if (paddedCanvas) cropAspect.ratio ?: sourceFrameAspect else sourceFrameAspect

    /**
     * The shape the preview should frame to, or null for the whole picture.
     *
     * The preview crops by aspect, which a hand-drawn rectangle can be reduced to
     * for framing purposes even though the export cuts the rectangle itself.
     */
    val previewCropRatio: Float?
        get() = when {
            paddedCanvas -> null
            cropAspect == CropAspect.Custom ->
                if (cropRect.isFull) null else cropRect.aspect(sourceFrameAspect)
            else -> cropAspect.ratio
        }

    /** The rectangle actually kept, whichever way the crop was chosen. A padded canvas keeps all of it. */
    val effectiveCrop: CropRect
        get() = when {
            paddedCanvas -> CropRect()
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
            markers = markers,
            selectedClipId = selectedClipId,
            muteOriginal = muteOriginal,
            originalVolume = originalVolume,
            rotationDegrees = rotationDegrees,
            cropAspect = cropAspect,
            cropRect = cropRect,
            canvasBackground = canvasBackground,
            beats = beats.settled,
            stabilizeStrength = stabilizeStrength
        )

    /**
     * The same fields put back, leaving the playhead and the zoom where they are.
     *
     * The snapshot carries the lead alone, so the rest of a Select more set is
     * reconciled with it here (selectionAfterRestore): kept, less anything the
     * step took away, only while the restored lead is one of it. Left as it
     * was, an undo could leave a set with no lead (the toolbar the set's,
     * Delete doing nothing, "Done selecting" unable to clear it), a set around
     * a lead that was never in it (Delete then took both), or ids of clips
     * that were gone. The Replace sheet is a question about one clip and goes
     * when the restored selection is another; Done replaced it out of sight.
     */
    fun restoring(snapshot: EditSnapshot): EditorUiState = copy(
        videoClips = snapshot.videoClips,
        audioClips = snapshot.audioClips,
        textOverlays = snapshot.textOverlays,
        effects = snapshot.effects,
        markers = snapshot.markers,
        selectedClipId = snapshot.selectedClipId,
        selectedClipIds = selectionAfterRestore(
            snapshot.selectedClipId,
            allSelectedIds,
            (snapshot.videoClips + snapshot.audioClips).map { it.id }.toSet() +
                snapshot.textOverlays.map { it.id } + snapshot.effects.map { it.id }
        ),
        selectingMore = selectingMore && snapshot.selectedClipId != null,
        replacing = replacing?.takeIf { it.clipId == snapshot.selectedClipId },
        muteOriginal = snapshot.muteOriginal,
        originalVolume = snapshot.originalVolume,
        rotationDegrees = snapshot.rotationDegrees,
        cropAspect = snapshot.cropAspect,
        cropRect = snapshot.cropRect,
        canvasBackground = snapshot.canvasBackground,
        // An analysis still listening keeps listening; the grid under it is
        // what goes back.
        beats = if (beats.running) snapshot.beats.copy(running = true, listeningTo = beats.listeningTo)
        else snapshot.beats,
        stabilizeStrength = snapshot.stabilizeStrength
    )

    val hasSeparateAudio: Boolean get() = audioClips.isNotEmpty()

    /**
     * Which audio clip the Audio panel acts on: whatever is selected, falling back
     * to the first, so the panel is never inert.
     */
    val targetAudioClip: Clip?
        get() = audioClips.firstOrNull { it.id == selectedClipId } ?: audioClips.firstOrNull()

    fun waveformFor(clip: Clip): Waveform? = clip.uri?.let { audioWaveforms[it.toString()] }

    /**
     * Where the picture ends: the last shot's or overlay's end, sounds left
     * out. What "Loop to the end" fills up to, and what a recording stops at.
     */
    val pictureEndMs: Long get() = videoClips.maxOfOrNull { it.timelineEndMs } ?: 0L

    /**
     * Every beat on the timeline, at the chosen density: the sounds' own beats
     * carried to where they play, or the camera-audio grid. See
     * [AudioRules.beatsOnTimeline].
     */
    val beatGrid: List<Long>
        get() = AudioRules.beatsOnTimeline(audioClips, beats.every, beats.downbeatOffset, beats.beatsMs)

    /** The beat grid at every beat, whatever the density: what the strip snaps to and the readout counts. */
    val allBeats: List<Long>
        get() = AudioRules.beatsOnTimeline(audioClips, 1, 0, beats.beatsMs)

    /**
     * The beats that start a bar, on the timeline: the readout's count and the
     * taller lines on the strip. Through the clips, like [beatGrid], because
     * the bar is a phase of each file's own list - counted over the merged
     * timeline list it drifted by a beat wherever two sounds or a cut song met.
     */
    val barGrid: List<Long>
        get() = AudioRules.beatsOnTimeline(audioClips, 4, beats.downbeatOffset, beats.beatsMs)

    /** Whether there is a grid to snap or cut to. */
    val hasBeatGrid: Boolean get() = allBeats.size >= 2

    /** The sound the beats are on, if it is still in the edit. */
    val beatClip: Clip? get() = beats.clipId?.let { id -> audioClips.firstOrNull { it.id == id } }

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
     *
     * The camera side reads [anyCameraAudio], not [sourceHasAudio]. That flag is
     * the *lead* file's alone, taken when the project opens; an edit whose first
     * shot happened to be silent and whose second was not said it had no sound
     * at all, which is the same trap the Camera sound panel fell into.
     *
     * This is whether the file will have sound *in* it. Whether it will have an
     * audio *track* is a different question and the answer is always yes -
     * see [estimatedExportBytes].
     */
    val hasAnyAudio: Boolean
        get() = (!muteOriginal && anyCameraAudio) || hasSeparateAudio ||
            videoClips.any { clip ->
                clip.isOverlay && clip.isHeard && !clip.isStillPicture &&
                    clip.uri?.let { com.squish.app.media.MediaCompat.cached(it) }
                        .let { it == null || (it.hasAudio && it.audioProblem == null) }
            }

    /**
     * Whether any shot in the edit has camera sound.
     *
     * [sourceHasAudio] is the *lead* file's flag, taken once when the project
     * opens and never recomputed when more shots are added. The Camera sound
     * panel read it alone, so an edit whose first clip happened to be silent
     * said "This clip has no audio track" and hid the switch and the level for
     * every shot that was not. A file whose metadata has not been read yet
     * counts as having sound, which is the side that leaves the control there.
     *
     * A still on the main track is not a shot. The guard used to be
     * `!isStillPicture`, which is the *overlay row's* PNG and never reaches
     * layer 0: the still that does is `isRenderedStill`, an MP4 this app writes
     * with a silent AAC track - so `hasAudio` is genuinely true for it. And
     * `sourceHasAudio` is true for the same reason in a photo-first project, so
     * both halves of the old test said yes. A slideshow of photos offered the
     * Camera sound switch and a level slider that moved nothing.
     *
     * The lead file's flag is gone from the test with it: "not read yet counts
     * as sound" is already what the per-clip reading does, and the flag was
     * answering for a file that may not be in the edit at all.
     */
    val anyCameraAudio: Boolean
        get() = videoClips.any { clip ->
            clip.isMain && !clip.isStillPicture && !com.squish.app.timeline.isRenderedStill(clip.uri?.toString()) &&
                clip.uri?.let { com.squish.app.media.MediaCompat.cached(it) }
                    .let { it == null || it.hasAudio }
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

    /** The part of the rotated frame the crop keeps, in pixels, before any size is chosen - or the padded canvas. */
    val croppedFrame: ExportPresets.Resolution
        get() = if (paddedCanvas) FrameRules.paddedCanvas(OutputSize.ORIGINAL, framedWidth, framedHeight, canvasAspect)
        else effectiveCrop.let { ExportPresets.croppedFrame(framedWidth, framedHeight, it.width, it.height) }

    /**
     * The frame the file is written at: what the crop keeps, at the chosen size
     * - or, padded, the canvas of the chosen ratio (FrameRules.paddedCanvas).
     * Fit-to-size keeps the kept part's own size and spends its budget on bitrate.
     */
    val outputResolution: ExportPresets.Resolution
        // The rotated shape, not the shot one. This sits after the rotation in
        // the render chain, so measured against the source's own numbers it asks
        // a turned frame to fit a box the wrong way round - which is the
        // letterboxed, wrong-shaped file that pressing Rotate produced. And the
        // cropped one: see ExportPresets.croppedFrame.
        get() {
            return resolutionAt(if (fitToSize) fittedOutputP else outputP)
        }

    /**
     * The frame written at short edge [p], the one way every size is worked out:
     * the encoder ceiling is measured on it too. Measured on the cropped pixels
     * instead, a 9:16 cut of a 960x720 picture (404 wide) asked for 3848x2160,
     * whose half is 4 pixels past the encoder's 1920, and every size above 864p
     * was greyed on a phone that writes 1080x1920.
     */
    fun resolutionAt(p: Int): ExportPresets.Resolution =
        if (paddedCanvas) FrameRules.paddedCanvas(p, framedWidth, framedHeight, canvasAspect)
        else effectiveCrop.let { ExportPresets.canvasFor(p, framedWidth, framedHeight, it.width, it.height) }

    /**
     * The canvas a base shot is composed on before the frame's crop is cut
     * from it, in the export's pixels: the padded canvas, or the picture's own
     * turned frame - the canvas the preview composes on (TimelinePreview),
     * whose fractions a shot's placement and the frame's crop are in. The
     * export fits each shot's picture into this before placing and cropping
     * it; done on the shot's own frame instead, a cropped shot's placement
     * was cut to the window's bounds and the frame's ratio was cut out of the
     * window rather than the canvas.
     */
    val composeResolution: ExportPresets.Resolution
        get() = if (paddedCanvas) writtenResolution else ExportPresets.Resolution(framedWidth, framedHeight)

    /**
     * The size a fitted export is written at, solved from the budget rather
     * than left at the frame's own; see ExportPresets.fitOutputP.
     */
    val fittedOutputP: Int
        get() {
            // Solved on the frame the file is written at: the padded canvas
            // (FrameRules.paddedCanvas) when there is one, else the crop.
            val frame = if (paddedCanvas) FrameRules.paddedCanvas(OutputSize.ORIGINAL, framedWidth, framedHeight, canvasAspect) else croppedFrame
            return ExportPresets.fitOutputPForBitrate(fitVideoBitrate, exportFps, frame.width, frame.height)
        }

    /**
     * The video bitrate of a fitted export: the target's budget, pulled down
     * after a run that missed its limit (see [FitOvershoot]; 1 until one
     * does). The size is solved from this same number, so a run aimed lower
     * steps down to a smaller frame rather than starving the one it had.
     */
    private val fitVideoBitrate: Int
        get() {
            // Always: the file gets a 128 kbps AAC track whether anything is
            // heard in it or not (see estimatedExportBytes), and a target that
            // did not set those bits aside overshot by them.
            //
            // Scaled off the *unfloored* solve. Scaling the floored one meant
            // that once the floor bit, the retry scale was arithmetically
            // discarded: "Try again, tighter" re-rendered at a byte-identical
            // bitrate, published another copy to the gallery, added another
            // library row and showed the same card. It is still floored here -
            // nothing is written under it - so a fit that cannot be met is
            // answered by [fitUnreachable] rather than by a retry that cannot
            // help.
            val budget = ExportPresets.solvedBitrateForTargetSize(
                targetSizeMb * 1_000_000L, trimmedDurationMs, includeAudio = true
            )
            return (budget * fitScale).toInt().coerceAtLeast(ExportPresets.MIN_VIDEO_BPS)
        }

    /**
     * Whether the size this export is fitted to can be met at all.
     *
     * Below the smallest bitrate anything is written at it cannot, and no
     * amount of aiming lower changes that: a ten-minute edit fitted to 16 MB
     * lands at about 32 MB on every run. The sheet says so under the chip, and
     * the overshoot card drops its retry.
     */
    val fitUnreachable: Boolean
        get() = fitToSize && !ExportPresets.fitReachable(
            targetSizeMb * 1_000_000L, trimmedDurationMs, includeAudio = true
        )

    /** The smallest this edit can be fitted to, for saying so when the target is under it. */
    val smallestFittedBytes: Long
        get() = ExportPresets.smallestFittedBytes(trimmedDurationMs, includeAudio = true)

    /** The rate the file is written at: the choice on the sheet, or the footage's own. */
    val exportFps: Float get() = ExportSettings.effectiveFps(outputFps, fps)

    /**
     * Whether any shot on the picture is HDR, as far as the background check
     * has read them (MediaCompat). Decides whether the sheet offers to keep it.
     */
    val hasHdrSource: Boolean
        get() = (videoClips.filter { !it.isOverlay }.mapNotNull { it.uri } + listOfNotNull(sourceUri))
            .any { MediaCompat.cached(it)?.hdr == true }

    /**
     * Whether the picture goes through the compositor: transitions, overlays
     * or gaps. Such a file is written in ordinary colour whatever is chosen -
     * its first input is the clock still, which sets the file's colour
     * (CompositionFactory) - so the HDR switch and the codec it drags in read
     * this rather than promising.
     */
    val isLayered: Boolean
        // The padded canvas counts, as it does in the export's own copy of this
        // decision (CompositionFactory.needsCompositing, which passes it): a
        // background under the picture is a second layer by definition. Left
        // out here, an edit on a padded canvas read as cuts-only on the sheet -
        // so "Keep HDR" offered itself, the codec row locked to HEVC to keep
        // it, and the render composited and tone-mapped anyway. The one thing
        // the two copies must not disagree about is what the render will do.
        get() = ExportPlan.needsCompositing(videoClips, trimmedDurationMs, paddedCanvas)

    /**
     * Whether the file keeps its HDR: asked for, on an HDR source, not
     * layered, and with no words or stickers on the picture. One answer for the
     * switch, the codec and the render: the switch used to show itself off on a
     * layered edit while the codec row above it stayed locked to HEVC "to keep
     * HDR" and the render asked Media3 to keep it anyway.
     *
     * The captions are in that list because of what Media3 does with an overlay
     * in an HDR graph. `OverlayShaderProgram.findHdrTypes` sorts each overlay
     * by its class: a `TextOverlay` is drawn as text, and anything else that is
     * a `BitmapOverlay` - which `SquishTextOverlay` is - is taken for an Ultra
     * HDR bitmap behind `checkState(SDK_INT >= 34)`. So an HLG clip with one
     * caption on it and Keep HDR on fails the render outright below Android 14,
     * and above it hands a plain ARGB_8888 bitmap with no gainmap to the
     * gainmap path. `isLayered` could not see this: it reads the video clips,
     * and a line of words is not one.
     */
    val effectiveKeepHdr: Boolean
        get() = keepHdr && canKeepHdr

    /**
     * Whether this edit *could* keep its HDR if asked - which is what the
     * switch's own enabled state and tick have to read, or the sheet promises
     * what the render will not do. See [effectiveKeepHdr] for why a line of
     * words is in the list.
     */
    val canKeepHdr: Boolean
        get() = hasHdrSource && !isLayered && textOverlays.isEmpty()

    /**
     * Whether the file is written in HEVC: asked for as the smaller file, or
     * needed to keep HDR, and only where this phone has the encoder. Media3
     * would refuse a codec the phone has no encoder for at the start of the
     * render, so the choice is made here, where the sheet can read it too.
     */
    val exportCodecHevc: Boolean
        get() = hevcAvailable == true && (hevc || effectiveKeepHdr)

    /**
     * The frame the file actually comes out at: [outputResolution] unless the
     * phone's encoder has said it will write something else for it. The canvas
     * every layer is fitted to, the sheet's number and the bitrate's pixels all
     * read this, so the size promised is the size delivered - 4K used to be
     * promised, budgeted for, and quietly written at half the size.
     */
    val writtenResolution: ExportPresets.Resolution
        get() = ExportPresets.writtenFrame(outputResolution, encoderAnswer)

    /**
     * The video bitrate this export is written at. One definition, read by the
     * estimate and by the encoder, so the size promised is the size delivered.
     */
    val exportVideoBitrate: Int
        get() = if (fitToSize) {
            fitVideoBitrate
        } else {
            val sourceBps = sourceVideoBps.takeIf { it > 0 }
                ?: ExportPresets.sourceVideoBitrate(originalSizeBytes, durationMs, sourceHasAudio)
            val recommended = ExportPresets.bitrateForFrame(
                writtenResolution, sourceWidth, sourceHeight, exportFps, sourceBps, sourceFps = fps
            )
            ExportSettings.scaledBitrate(recommended, quality, exportCodecHevc)
        }

    /**
     * What the finished file should weigh.
     *
     * The AAC track is counted whether or not anything is heard. `VideoProcessor`
     * sets `AUDIO_AAC` on the Transformer for every composed export, and the
     * encoder writes constant 128 kbps frames with silence in them as readily as
     * with sound: a three-second export of a clip with no audio was measured
     * with 50,597 bytes of mp4a in it against an estimate that had set aside
     * none. Counting it is the honest number and the safe side of a size target.
     *
     * [hasAnyAudio] is the other question - whether there is sound in the file.
     * Not writing the track at all when there is none would be better than
     * budgeting for it, and is the thing to try on a device: it is 128 kbps of
     * silence in every file a soundless edit makes.
     */
    val estimatedExportBytes: Long
        get() {
            val seconds = trimmedDurationMs / 1000.0
            if (audioOnly) return (ExportPresets.AUDIO_BITRATE_BPS * seconds / 8).toLong()
            return ((exportVideoBitrate * seconds + ExportPresets.AUDIO_BITRATE_BPS * seconds) / 8).toLong()
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
            label = overlay.stripLabel,
            sourceInMs = 0,
            sourceOutMs = (overlay.endMs - overlay.startMs).coerceAtLeast(MIN_CLIP_MS),
            timelineStartMs = overlay.startMs,
            sourceDurationMs = durationMs,
            text = overlay.stripLabel,
            // The strip reads a line's preferred row where it reads a sound's.
            layer = overlay.stripRow
        )
    }

    // The take under way, drawn growing on a sound row from where it started
    // to what the mic has heard, so it is seen against the picture. Sized by
    // the mic's own clock, not the playhead: it grows whether or not the
    // picture is playing.
    val taking = recording.takeIf { it.phase == RecordingState.Phase.Recording }?.let { rec ->
        Clip(
            id = RECORDING_CLIP_ID,
            kind = ClipKind.Audio,
            uri = null,
            label = "Recording…",
            sourceInMs = 0L,
            sourceOutMs = rec.recordedMs.coerceAtLeast(1L),
            timelineStartMs = rec.startMs
        )
    }

    return TimelineState(
        clips = videoClips + audioClips + listOfNotNull(taking) + captions,
        selectedClipId = selectedClipId,
        selectedIds = selectedClipIds,
        playheadMs = playheadMs,
        pixelsPerSecond = pixelsPerSecond,
        waveforms = audioWaveforms,
        effects = effects.map { e ->
            EffectSpan(e.id, e.kind.label, e.startMs, e.endMs, e.kind)
        },
        pictureEndMs = trimmedDurationMs,
        missingUris = setOfNotNull(missingMedia?.toString())
    )
}

/**
 * A file picked to replace a clip's footage, before it goes in: the clip, the
 * file and how long it is, and where in it the clip's window starts - chosen
 * on the Replace sheet, whose frame shows the moment picked. The window is the
 * clip's own ([neededMs]), so the pick is only *where*, never how much.
 */
data class ReplaceRequest(
    val clipId: String,
    val uri: Uri,
    val label: String,
    val fileMs: Long,
    val neededMs: Long,
    val inPointMs: Long = 0L,
    /** Where the slider opened - the old clip's own in-point - and what the sheet's Reset puts back, as every sheet's does. */
    val defaultInMs: Long = inPointMs
) {
    /** The last moment the window can start and still fit in the file. */
    val latestInMs: Long get() = (fileMs - neededMs).coerceAtLeast(0L)
}

/** Where an auto-reframe analysis has got to. */
data class ReframeProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Boolean = false
)
