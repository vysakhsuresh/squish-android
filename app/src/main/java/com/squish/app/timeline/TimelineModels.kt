package com.squish.app.timeline

import android.net.Uri
import java.util.UUID
import kotlin.math.abs

enum class ClipKind { Video, Audio, Text }

/** The transition sheet's tabs: the kind of move each transition makes. */
enum class TransitionCategory(val label: String) {
    Basic("Basic"),
    Camera("Camera"),
    Glitch("Glitch"),
    Light("Light")
}

/**
 * The transitions. Every one is drawn by ExportPlan.blend for the file and the
 * preview alike, from a handful of per-shot draws - fade, shift, scale, cut
 * away, whiten - so a kind is only ever added there. The names are what drafts
 * carry, so they stay as they are; only the labels may change.
 */
enum class TransitionType(val label: String, val category: TransitionCategory) {
    None("Cut", TransitionCategory.Basic),
    CrossFade("Dissolve", TransitionCategory.Basic),
    DipToBlack("Dip to black", TransitionCategory.Basic),
    DipToWhite("Dip to white", TransitionCategory.Basic),
    SlideLeft("Slide left", TransitionCategory.Camera),
    SlideRight("Slide right", TransitionCategory.Camera),
    SlideUp("Slide up", TransitionCategory.Camera),
    SlideDown("Slide down", TransitionCategory.Camera),
    /** The new shot pushes the old one off to the left. */
    Push("Push", TransitionCategory.Camera),
    WipeRight("Wipe right", TransitionCategory.Camera),
    WipeLeft("Wipe left", TransitionCategory.Camera),
    /** The new shot lands from larger, dissolving in. */
    ZoomIn("Zoom", TransitionCategory.Camera),
    /** A hard cut with the picture shaken on either side of it. */
    Jitter("Jitter", TransitionCategory.Glitch),
    /** The two shots alternate, faster, until the new one holds. */
    Flicker("Flicker", TransitionCategory.Glitch),
    /** A burst of white on the cut. */
    Flash("Flash", TransitionCategory.Light),
    /** A dissolve that brightens through its middle. */
    Glow("Glow", TransitionCategory.Light)
}

/**
 * Sits on the boundary *into* a clip, which is how editors think about it: the
 * transition belongs to the incoming shot. [durationMs] is the overlap, taken
 * half from each side of the cut.
 */
data class Transition(
    val type: TransitionType = TransitionType.None,
    val durationMs: Long = 500L
) {
    val isActive: Boolean get() = type != TransitionType.None && durationMs > 0
}

/**
 * One piece of media placed on the timeline.
 *
 * [sourceInMs]/[sourceOutMs] address the original file; [timelineStartMs] is where
 * that slice sits in the edit. Splitting and trimming only move these numbers, so
 * nothing is ever re-encoded until export and every edit is non-destructive.
 */
data class Clip(
    val id: String = UUID.randomUUID().toString(),
    val kind: ClipKind,
    val uri: Uri? = null,
    val label: String,
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val timelineStartMs: Long,
    val sourceDurationMs: Long = sourceOutMs,
    val volume: Float = 1f,

    /**
     * The sound switched off, over whatever [volume] and [volumeKeys] say, so
     * a shot ducked by hand can be silenced and brought back with its duck
     * intact. Mute used to write a level of nothing, which on a keyed clip
     * dropped a key at the playhead and silenced nothing.
     */
    val muted: Boolean = false,

    /**
     * The sound's rise from silence at its start and fall to it at its end, in
     * played milliseconds - the strip's clock, so a fade drawn as a wedge on
     * the clip is the length it plays. A music bed trimmed to the picture's end
     * used to stop dead; a voiceover started with a click.
     */
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,

    /** A voice effect on this clip's own sound; see [VoiceEffect]. */
    val voice: VoiceEffect = VoiceEffect.None,

    /**
     * Beats on this sound, in the file's own time, so they travel with the clip
     * and through its speed curve. Found by the beat detector or tapped in by
     * ear; drawn as dots on the clip, and what the cuts snap to.
     */
    val beats: List<Long> = emptyList(),

    /**
     * How fast this clip plays, and where that changes across it.
     *
     * Per clip rather than per project, which is the whole difference between a
     * setting and an edit: one shot can go to quarter speed on the landing while
     * everything around it stays where it was.
     */
    val speedRamp: SpeedRamp = SpeedRamp(),
    val text: String? = null,

    /** Transition into this clip from whatever precedes it on the same layer. */
    val transitionIn: Transition = Transition(),

    // Compositing. Layer 0 is the base picture; anything above floats over it,
    // which is how a picture-in-picture, a logo bug or a reaction cam is built.
    val layer: Int = 0,
    val opacity: Float = 1f,
    val scale: Float = 1f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
    val rotation: Float = 0f,

    /**
     * Animation. Empty means the clip sits still at [staticTransform]; otherwise
     * these drive it over the clip's length. Kept sorted by time by the editor, so
     * evaluation on the render thread never has to sort.
     */
    val keyframes: List<Keyframe> = emptyList(),

    /**
     * Green screen, when this clip has one. Only useful on an overlay layer -
     * keying the base track just reveals black.
     */
    val chromaKey: ChromaKey? = null,

    /** Restricts the clip to a shape. Composes with [chromaKey] rather than replacing it. */
    val mask: Mask? = null,

    /** Background removal, when this clip has had its person found. */
    val background: BackgroundRemoval? = null,

    /**
     * The measured correction for camera shake, keyed by **source** time. Separate
     * from [keyframes] so an edit never destroys an analysis, and an analysis never
     * destroys an edit.
     */
    val stabilizer: List<Keyframe> = emptyList(),

    /**
     * What the stabilizer measured, kept so a change of Strength is solved again
     * from the numbers rather than by decoding the clip again: the analysis takes
     * a minute on a long shot, the solve takes a moment.
     */
    val stabilizerMeasurement: com.squish.app.media.video.StabilizerMeasurement? = null,

    /**
     * The strength this clip's correction was solved at, once its Strength
     * slider has been touched; null means the edit's default. Per clip, because
     * the slider sits on the clip's own sheet: one number for the edit meant a
     * nudge on one shot quietly re-solved every other.
     */
    val stabilizeStrength: Float? = null,

    /**
     * Opacity over the clip, in played time from its head, over [opacity] when
     * there are none; see [ValueKey]. A picture-in-picture fading up while a
     * placement key moves it is two tracks, keyed on their own moments.
     */
    val opacityKeys: List<ValueKey> = emptyList(),

    /** Level over the clip, over [volume] when there are none: a bed ducked under a line, by hand. */
    val volumeKeys: List<ValueKey> = emptyList(),

    /**
     * How the picture arrives, leaves and behaves in between, each with its
     * length in played milliseconds. Laid over the keyframes ([ClipAnimation]):
     * a move drawn across the clip survives an arrival being switched on.
     */
    val arrival: ClipArrival = ClipArrival.None,
    val arrivalMs: Long = ClipAnimation.DEFAULT_IN_MS,
    val leaving: ClipLeaving = ClipLeaving.None,
    val leavingMs: Long = ClipAnimation.DEFAULT_OUT_MS,
    val loop: ClipLoop = ClipLoop.None,
    val loopMs: Long = ClipAnimation.DEFAULT_LOOP_MS,

    /**
     * Whether the file blends neighbouring frames where slow motion has run out
     * of them (FrameBlendEffect). Export only: the preview plays the frames the
     * footage has.
     */
    val frameBlend: Boolean = false,

    /**
     * Whether the sound's pitch follows the speed, tape-style, or is held. Held
     * is what a voice wants and what the preview always did; Media3's own speed
     * change lets the pitch follow, so the file used to disagree with the
     * preview on every retimed clip until this chose for both.
     */
    val pitchFollowsSpeed: Boolean = false
) {
    /** How much of the file this clip covers. Unaffected by how fast it plays. */
    val sourceSpanMs: Long get() = (sourceOutMs - sourceInMs).coerceAtLeast(0)

    /**
     * How long the clip takes to play, which is what the strip draws and what the
     * exported file contains.
     *
     * Source span and played length stopped being the same number the moment speed
     * became per-clip: half speed is twice the strip. Everything downstream reads
     * this, so a ramp moves the clips after it without any of them knowing why.
     */
    val durationMs: Long get() = speedRamp.outputDurationMs(sourceSpanMs)

    val timelineEndMs: Long get() = timelineStartMs + durationMs
    fun spans(ms: Long): Boolean = ms > timelineStartMs && ms < timelineEndMs
    val isOverlay: Boolean get() = layer > 0

    /** On the main track: the magnetic spine of the edit. See [layOutMain]. */
    val isMain: Boolean get() = kind == ClipKind.Video && layer == 0

    /** A photo kept as a picture on an overlay row (see [isStillPicture]): drawn, never decoded. */
    val isStillPicture: Boolean get() = isStillPicture(uri?.toString())

    /** A take recorded in the editor (see [isVoiceover]): the strip marks it with a mic. */
    val isVoiceover: Boolean get() = isVoiceover(uri?.toString())

    /**
     * Whether the clip's own sound is ever heard: not muted, and its level -
     * the keys when it has any, the one level otherwise - above nothing
     * somewhere. What decides whether a row carries sound in the file and
     * whether the edit has any sound at all.
     */
    val isHeard: Boolean
        get() = !muted && (if (volumeKeys.isEmpty()) volume > 0f else volumeKeys.any { it.value > 0f })

    /**
     * Whether another overlay among [others] ends where this one starts on its
     * row - within the slip a drag leaves ([MIN_GAP_MS]) - which is when the
     * toolbar offers a transition, when the strip marks the join, and for as
     * long as a transition set there is kept ([withOverlayTransitionsFitted]).
     */
    fun hasOverlayJoin(others: List<Clip>): Boolean =
        isOverlay && others.any {
            it.id != id && it.kind == kind && it.layer == layer &&
                abs(it.timelineEndMs - timelineStartMs) < MIN_GAP_MS
        }

    /**
     * The highest overlay row this clip may sit on. Footage stops at
     * [MAX_FOOTAGE_LAYER]: each row of it is a decoder in the preview and in
     * the export, and a photo is none.
     */
    val topLayer: Int get() = if (kind == ClipKind.Video && !isStillPicture) MAX_FOOTAGE_LAYER else MAX_LAYER

    /**
     * How far into the file a cut at [timelineMs] would fall, or null when this
     * clip cannot be cut there.
     *
     * Refused, rather than nudged, within [MIN_CLIP_MS] of either end. The cut
     * used to be nudged in the file but left at the playhead on the strip, so a
     * cut 50ms into a clip gave two halves overlapping by 150ms - and near the
     * tail, a hole. And the button that offered the cut has to ask this same
     * question, or it lights up for cuts that then do nothing.
     */
    fun splitOffsetAt(timelineMs: Long): Long? {
        if (!spans(timelineMs) || sourceSpanMs <= MIN_CLIP_MS * 2) return null
        val offset = speedRamp.sourceOffsetAt(timelineMs - timelineStartMs, sourceSpanMs)
        return offset.takeIf { it >= MIN_CLIP_MS && it <= sourceSpanMs - MIN_CLIP_MS }
    }

    fun canSplitAt(timelineMs: Long): Boolean = splitOffsetAt(timelineMs) != null

    /**
     * The two halves of a cut at [timelineMs], or null when it is refused.
     *
     * The first half keeps this clip's id, so whatever refers to the clip by id
     * still finds the part it started with.
     *
     * The second half starts exactly where the first ends - which is where the
     * cut frame lands on the timeline (`timelineAtSource`), give or take the
     * rounding of a ramp - so the two are butted by construction on every lane.
     * It gets no transition: the one into this clip belongs to the first half,
     * and copying it made a dissolve of a shot into itself.
     *
     * Both halves keep the whole animation, the second moved back by the length
     * of the first, so each plays exactly the part of the move that was drawn over
     * its frames - no pose is re-sampled, so no easing restarts at the cut - and
     * revealing footage past the cut later brings back the move that was there.
     *
     * A fade belongs to an end: the head keeps the fade in and the tail the fade
     * out, so cutting a song in two does not put a dip in the middle of it.
     *
     * The halves have to add up to the whole, or everything after a main-track cut
     * shifts. A ramp's played length is rounded, and each half is rounded - and
     * its staircase stepped - on its own, so the two can come out a few
     * milliseconds long or short: once per cut, which a beat cutter makes a
     * hundred of. So when they do not add up, the cut goes on the nearest source
     * millisecond where they do, as long as that is within half a frame of the
     * playhead; past that, the cut stays where it was asked for.
     */
    fun splitAt(timelineMs: Long): Pair<Clip, Clip>? {
        val wanted = splitOffsetAt(timelineMs) ?: return null
        val exact = halvesAt(wanted)
        if (exact.first.durationMs + exact.second.durationMs == durationMs) return exact
        val playedCut = timelineMs - timelineStartMs
        return (1L..SPLIT_SEARCH_MS).asSequence()
            .flatMap { sequenceOf(wanted + it, wanted - it) }
            .filter { it >= MIN_CLIP_MS && it <= sourceSpanMs - MIN_CLIP_MS }
            .map { halvesAt(it) }
            .filter { (head, _) -> abs(head.durationMs - playedCut) <= SPLIT_DRIFT_MS }
            .firstOrNull { (head, tail) -> head.durationMs + tail.durationMs == durationMs }
            ?: exact
    }

    private fun halvesAt(offset: Long): Pair<Clip, Clip> {
        // An arrival belongs to a head and a leaving to a tail, as the fades do:
        // cutting a clip in two must not make its picture fade out and in again
        // at the cut.
        val head = copy(
            sourceOutMs = sourceInMs + offset,
            speedRamp = speedRamp.sliced(0L, offset),
            fadeOutMs = 0L,
            leaving = ClipLeaving.None
        )
        val tail = copy(
            id = UUID.randomUUID().toString(),
            sourceInMs = sourceInMs + offset,
            timelineStartMs = head.timelineEndMs,
            speedRamp = speedRamp.sliced(offset, sourceSpanMs),
            transitionIn = Transition(),
            keyframes = keyframes.shiftedBy(-head.durationMs),
            opacityKeys = opacityKeys.shiftedBy(-head.durationMs),
            volumeKeys = volumeKeys.shiftedBy(-head.durationMs),
            fadeInMs = 0L,
            arrival = ClipArrival.None
        )
        return head to tail
    }

    /**
     * The user's placement at a moment of the timeline - the keys, or the static
     * fields when there are none - without the stabilizer's measured correction.
     * A moment outside the clip reads the pose at the nearer end, which is what a
     * panel showing this clip's placement has to show while the playhead is
     * elsewhere.
     */
    fun placementAt(timelineMs: Long): Transform =
        keyframes.transformAt((timelineMs - timelineStartMs).coerceIn(0L, durationMs), staticTransform)

    /** The source frame on screen at a moment of the timeline. */
    fun sourceAt(timelineMs: Long): Long {
        val local = (timelineMs - timelineStartMs).coerceAtLeast(0L)
        return sourceInMs + speedRamp.sourceOffsetAt(local, sourceSpanMs)
    }

    /** How fast the clip is playing at a moment of the timeline. */
    fun speedAt(timelineMs: Long): Float {
        val local = (timelineMs - timelineStartMs).coerceAtLeast(0L)
        return speedRamp.speedAt(speedRamp.sourceOffsetAt(local, sourceSpanMs))
    }

    /** Where a timeline moment lands in the played clip, and where that is in the file. */
    fun timelineAtSource(sourceMs: Long): Long =
        timelineStartMs + speedRamp.outputOffsetAt(
            (sourceMs - sourceInMs).coerceAtLeast(0L),
            sourceSpanMs
        )

    /** Where the picture sits when nothing is animating it. */
    val staticTransform: Transform
        get() = Transform(scale, offsetXFraction, offsetYFraction, rotation)

    val isAnimated: Boolean get() = keyframes.size >= 2

    val isStabilized: Boolean get() = stabilizer.isNotEmpty()

    /**
     * The transform at a moment on the timeline, stabilization included.
     *
     * The two clocks are deliberately different. Keyframes are a move the editor
     * drew over the *played* clip, so they run on played time and a ramp carries
     * them with it. Stabilization is a measurement of a particular frame of the
     * file, so it runs on source time - handing it played time would apply one
     * frame's shake correction to a completely different frame.
     */
    fun transformAt(timelineMs: Long): Transform {
        val local = timelineMs - timelineStartMs
        return composeTransform(keyframes, staticTransform, stabilizer, local, sourceAt(timelineMs))
            .animated(animationFrame(local))
    }

    /** Whether an arrival, a leaving or a loop is set. */
    val hasAnimation: Boolean
        get() = arrival != ClipArrival.None || leaving != ClipLeaving.None || loop != ClipLoop.None

    /** The arrival, leaving and loop at [localMs] into the played clip; see [ClipAnimation]. */
    fun animationFrame(localMs: Long): AnimFrame =
        if (!hasAnimation) AnimFrame.STILL
        else ClipAnimation.frameAt(arrival, leaving, loop, arrivalMs, leavingMs, loopMs, localMs, durationMs)

    /**
     * How much of the picture shows at [localMs] into the played clip: the
     * opacity track, or the one level when there is none, through the
     * animation's own fade. What the preview draws the layer at and what the
     * file writes (TransitionEffect), per frame.
     */
    fun alphaAt(localMs: Long): Float =
        (opacityKeys.valueAt(localMs, opacity) * animationFrame(localMs).alpha).coerceIn(0f, 1f)

    /** [alphaAt] for a moment of the timeline. */
    fun opacityAt(timelineMs: Long): Float = alphaAt(timelineMs - timelineStartMs)

    /**
     * Whether the picture's opacity ever changes over the clip, or is below
     * full: what decides whether the export draws it through a per-frame pass.
     */
    val fadesPicture: Boolean
        get() = opacity < 1f || opacityKeys.isNotEmpty() ||
            arrival == ClipArrival.Fade || arrival == ClipArrival.Zoom || arrival == ClipArrival.Shrink || arrival == ClipArrival.Spin ||
            leaving != ClipLeaving.None || loop == ClipLoop.Flicker

    /**
     * The level at [localMs] into the played clip: nothing while [muted], else
     * the volume track, or the one level when there is none. Not through the
     * fades, which are the caller's (AudioRules.fadeGain), as they were.
     */
    fun volumeAt(localMs: Long): Float = if (muted) 0f else volumeKeys.valueAt(localMs, volume)

    /** [volumeAt] for a moment of the timeline. */
    fun volumeAtTimeline(timelineMs: Long): Float = volumeAt(timelineMs - timelineStartMs)
}

data class TimelineState(
    val clips: List<Clip> = emptyList(),
    val selectedClipId: String? = null,
    val playheadMs: Long = 0,
    val pixelsPerSecond: Float = 42f,
    /** Each sound file's waveform, by URI, drawn on its clips. */
    val waveforms: Map<String, com.squish.app.media.audio.Waveform> = emptyMap(),
    /** The effects library's placements, on a lane of their own. */
    val effects: List<EffectSpan> = emptyList(),
    /**
     * Where the picture ends, which words are kept inside (see
     * `EditRules.clampedShift`). The editor's own figure, because with no video
     * clips it is not something the clips here can say; null for "the end of
     * the last video clip".
     */
    val pictureEndMs: Long? = null
) {
    val videoClips: List<Clip> get() = clips.filter { it.kind == ClipKind.Video }.sortedBy { it.timelineStartMs }
    /** The base picture - the cuts-only spine of the edit. */
    val baseVideoClips: List<Clip>
        get() = clips.filter { it.kind == ClipKind.Video && it.layer == 0 }.sortedBy { it.timelineStartMs }
    /** Anything floating over the base: picture-in-picture, bugs, reaction cams. */
    val overlayClips: List<Clip>
        get() = clips.filter { it.kind == ClipKind.Video && it.layer > 0 }.sortedBy { it.timelineStartMs }
    val layerCount: Int get() = clips.filter { it.kind == ClipKind.Video }.maxOfOrNull { it.layer } ?: 0
    val audioClips: List<Clip> get() = clips.filter { it.kind == ClipKind.Audio }.sortedBy { it.timelineStartMs }
    val textClips: List<Clip> get() = clips.filter { it.kind == ClipKind.Text }.sortedBy { it.timelineStartMs }
    val durationMs: Long get() = clips.maxOfOrNull { it.timelineEndMs } ?: 0L
    val selectedClip: Clip? get() = clips.firstOrNull { it.id == selectedClipId }

    /** The main-track clip under a moment. None at a join: there is nothing to cut there. */
    fun mainClipAt(ms: Long): Clip? = baseVideoClips.lastOrNull { it.spans(ms) }

    /**
     * What Cut acts on: the selected clip if there is one, otherwise the main-track
     * clip under the playhead. Never every track at once - a music bed cut into
     * forty pieces because the picture was is not what anyone asked for.
     *
     * A selection this state does not hold (an effect, or something owned
     * elsewhere) is no selection as far as the razor is concerned.
     */
    fun splitTarget(selection: String? = selectedClipId): Clip? =
        selection?.let { id -> clips.firstOrNull { it.id == id } } ?: mainClipAt(playheadMs)

    /** Whether Cut would do anything - the one rule the button and the cut share. */
    fun canSplit(selection: String? = selectedClipId): Boolean =
        splitTarget(selection)?.canSplitAt(playheadMs) == true
}

const val MIN_CLIP_MS = 200L
const val ZOOM_MIN = 0.05f
const val ZOOM_MAX = 2_000f
/**
 * Overlay rows. Three was a cap a logo, a reaction clip and a caption card
 * already filled. Only the lowest [MAX_FOOTAGE_LAYER] of them take footage; a
 * photo on a row costs no decoder at all, so the rest are for pictures.
 */
const val MAX_LAYER = 6

/**
 * The overlay rows footage may use. Each row of it is its own player in the
 * preview and its own sequence in the export, so with the two main-track rolls
 * three rows is five video decoders at once - what the old three-row cap kept
 * to. Six rows of footage was eight, past what a mid-range phone (the moto g84's
 * hardware decoders) opens, and the failure is a black preview and an export
 * that stops at a decoder error.
 */
const val MAX_FOOTAGE_LAYER = 3

/**
 * Whether [address] is a picture this app keeps for an overlay row - a PNG it
 * wrote under its own files/stills/ (StillClips.overlayFromImage). Here, from
 * the address alone, so the rows' rules can tell a photo from footage; gallery
 * picks are all content:// addresses.
 */
fun isStillPicture(address: String?): Boolean {
    if (address == null || !address.startsWith("file:")) return false
    val path = address.substringBefore('?').substringBefore('#')
    return path.endsWith(".png") && "/stills/" in path
}

/**
 * Whether [address] is a take the editor recorded - a WAV it wrote under its
 * own files/voice/ (VoiceRecorder). From the address alone, like a still, so
 * no draft field is needed to draw the mic on it.
 */
fun isVoiceover(address: String?): Boolean {
    if (address == null || !address.startsWith("file:")) return false
    val path = address.substringBefore('?').substringBefore('#')
    return path.endsWith(".wav") && "/voice/" in path
}

/**
 * How close two key times may be before they are the same key, when a placement
 * edit writes one. A frame at 30fps.
 */
const val KEY_TOLERANCE_MS = 33L

/** Source milliseconds either side of a cut tried, nearest first, for halves that add up. */
private const val SPLIT_SEARCH_MS = 40L

/** How far from the playhead, in played time, a cut may land to get halves that add up: half a frame. */
private const val SPLIT_DRIFT_MS = 16L

// ---- The main track -------------------------------------------------------------
//
// The main track is magnetic: its clips are always butted end to end from zero,
// in order, and nothing on it can overlap - except a transition, which is an
// overlap by definition. Every operation that touches a main-track clip finishes
// by laying the track out again, so there is no sequence of edits that leaves a
// black hole or two shots on top of each other. It used to be free-floating, and
// every delete, tail trim and transition left a gap that played as black until
// someone found "Close gaps". Overlays, sound and text stay where they are put:
// a cue is deliberately placed against the picture.
//
// Except for spacing the edit did not make. A draft saved while the track was
// free-floating can hold a clip deliberately parked a few seconds after the one
// before it, against a music cue, or laid over it. Closing that up on the first
// unrelated trim would slide the rest of the picture out of sync with the sound,
// captions and effects that were placed against it, and autosave would keep the
// damage. So every edit keeps the spacing each clip already had from the one
// before it (see [mainSpacing]) and only closes what the edit itself opens; on a
// track that was butted - every project made since - that spacing is zero and
// the track stays butted. "Close gaps" ([rippleVideo]) is the one thing that
// takes old spacing away.

/** How far into the clip before it a transition into [clip] reaches. */
private fun overlapInto(clip: Clip, previous: Clip): Long = transitionOverlapMs(clip, previous)

/**
 * How long the transition into [clip] from [previous] actually plays: what was
 * asked for, capped at half the shorter of the two shots so a long dissolve
 * between two short clips cannot swallow either. The sheet shows the cap when
 * it bites, so a slider that stops doing anything says why.
 */
fun transitionOverlapMs(clip: Clip, previous: Clip): Long =
    if (!clip.transitionIn.isActive) 0L
    else clip.transitionIn.durationMs.coerceAtMost(minOf(clip.durationMs, previous.durationMs) / 2)

/**
 * Each main clip's distance from where a butted track would put it: positive for
 * a gap before it, negative for an overlap. Only non-zero entries are kept, so on
 * any track laid out since the track became magnetic this is empty.
 */
private fun TimelineState.mainSpacing(): Map<String, Long> {
    val base = baseVideoClips
    val spacing = HashMap<String, Long>()
    base.forEachIndexed { i, clip ->
        val butted = if (i == 0) 0L else base[i - 1].timelineEndMs - overlapInto(clip, base[i - 1])
        val gap = clip.timelineStartMs - butted
        if (gap != 0L) spacing[clip.id] = gap
    }
    return spacing
}

/**
 * Lays the main track out in [order], from zero, each clip [spacing] away from
 * butted (see above; almost always nothing).
 *
 * A transition is an overlap, so the incoming clip starts early and the edit gets
 * shorter. Capped at half the shorter shot so a long dissolve between two short
 * clips cannot swallow either of them. The first clip has nothing to transition
 * from, so a transition left on it (by a delete or a reorder) is dropped rather
 * than carried: the export would read it as a fade up from black at the very
 * start (it fades the incoming shot in over its opening), and the strip would
 * badge a join that is not there. Every path that puts a clip first is an undo
 * step, which is where that transition comes back from.
 */
private fun TimelineState.layOutMain(order: List<Clip>, spacing: Map<String, Long> = emptyMap()): TimelineState {
    var cursor = 0L
    val laid = order.mapIndexed { index, clip ->
        val overlap = if (index == 0) 0L else overlapInto(clip, order[index - 1])
        val start = (cursor - overlap + (spacing[clip.id] ?: 0L)).coerceAtLeast(0L)
        val placed = clip.copy(
            timelineStartMs = start,
            transitionIn = if (index == 0) Transition() else clip.transitionIn
        )
        cursor = start + clip.durationMs
        placed
    }
    return copy(clips = laid + clips.filterNot { it.isMain })
}

/**
 * The main track closed up end to end, in the order it is in now, gaps and
 * overlaps left by a pre-magnetic draft included. What "Close gaps" runs.
 */
fun TimelineState.rippleVideo(): TimelineState = layOutMain(baseVideoClips)

/** Where a main-track clip would go if its middle were at [midMs], among [others]. */
private fun insertionIndex(others: List<Clip>, midMs: Long): Int =
    others.count { it.timelineStartMs + it.durationMs / 2 < midMs }

/**
 * Moves a main-track clip to [index] in the running order, closing up behind it
 * and making room where it lands. What a long-press lift and drop does. The clip
 * lands butted, whatever spacing it had where it was.
 */
fun TimelineState.withClipReordered(clipId: String, index: Int): TimelineState {
    val base = baseVideoClips
    val clip = base.firstOrNull { it.id == clipId } ?: return this
    val others = base.filterNot { it.id == clipId }
    val at = index.coerceIn(0, others.size)
    if (base.indexOf(clip) == at) return this
    return layOutMain(others.take(at) + clip + others.drop(at), mainSpacing() - clipId)
}

/**
 * Sets the transition into a clip. On the main track the clip is pulled back over
 * its predecessor by the overlap - and everything after it comes back with it.
 * Only the clip itself used to move, which left a hole the length of the
 * transition after it.
 */
fun TimelineState.withTransition(clipId: String, transition: Transition): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    if (clip.transitionIn == transition) return this
    val tagged = copy(clips = clips.map { if (it.id == clipId) it.copy(transitionIn = transition) else it })
    if (!clip.isMain) return tagged
    val order = baseVideoClips.map { if (it.id == clipId) it.copy(transitionIn = transition) else it }
    return tagged.layOutMain(order, mainSpacing())
}

/**
 * Every overlay whose transition has lost its join - the overlay before it on
 * its row moved, trimmed short or gone - with that transition dropped. An
 * overlay's transition is its arrival over that join (ExportPlan.ownDrawAt) and
 * the toolbar offers it only while the join is there, so a transition kept past
 * it played on with no way back to its sheet to turn it off. Run on every change
 * to the timeline, the way the main track drops a transition from whichever shot
 * comes first (see [layOutMain]); the undo step that broke the join is where it
 * comes back from.
 */
fun TimelineState.withOverlayTransitionsFitted(): TimelineState {
    if (clips.none { it.isOverlay && it.transitionIn.isActive }) return this
    val overlays = clips.filter { it.kind == ClipKind.Video && it.isOverlay }
    return copy(clips = clips.map {
        if (it.isOverlay && it.transitionIn.isActive && !it.hasOverlayJoin(overlays)) it.copy(transitionIn = Transition()) else it
    })
}

/**
 * Below this a gap is rounding, not a gap: shorter than a frame at 50 fps. Two
 * clips this close are butted, for the export's pieces (ExportPlan) and for an
 * overlay's join alike.
 */
const val MIN_GAP_MS = 20L

/**
 * A stretch of the main track with no picture on it: before [clipId], from
 * [fromMs] to [toMs]. Only a draft from before the track was magnetic has any
 * (see above), and the strip draws each one so it can be closed by itself.
 */
data class MainGap(val clipId: String, val fromMs: Long, val toMs: Long)

/** Every gap on the main track, in order. Empty on any track laid out since it became magnetic. */
fun TimelineState.mainGaps(): List<MainGap> {
    val base = baseVideoClips
    return base.mapIndexedNotNull { i, clip ->
        val butted = if (i == 0) 0L else base[i - 1].timelineEndMs - overlapInto(clip, base[i - 1])
        if (clip.timelineStartMs > butted) MainGap(clip.id, butted, clip.timelineStartMs) else null
    }
}

/**
 * The one gap before [clipId] closed, and every other left as it is: the shots
 * from there on move up by its length, and the spacing kept elsewhere stays.
 * "Close gaps" closed them all at once, which also slid every later shot out
 * from under the sound placed against it.
 */
fun TimelineState.withGapClosed(clipId: String): TimelineState {
    val spacing = mainSpacing()
    if ((spacing[clipId] ?: 0L) <= 0L) return this
    return layOutMain(baseVideoClips, spacing - clipId)
}

// ---- Rows on the strip ------------------------------------------------------------

/**
 * A long-press drop: [clipId] to start at [startMs] on [row].
 *
 * For an overlay the row is its layer, which is also which picture is drawn over
 * which, so it only changes to a layer that is free for the whole clip at its new
 * time; otherwise the clip moves along its own row as a drag would (see
 * [withClipMoved]). Never onto the main track - joining it re-lays every shot
 * after it, which is "Switch to main", not something a drop should do by being
 * a few pixels low. Footage stops at its own top row ([Clip.topLayer]), as it
 * does every other way onto a row.
 *
 * The rows are not closed up here: the strip asks this where a carried clip
 * would land and draws it there, among the rows it is showing. The editor
 * closes them up after the drop ([withRowsCompacted]).
 *
 * For a sound the row is only where it is drawn, kept in its [Clip.layer] as a
 * preference (see [TimelineLanes.rows]); every other sound's preference is set to
 * the row it is shown on now, so nothing else changes row by itself, and the
 * moved one goes to the nearest row free for it there (see
 * [TimelineLanes.preferencesAfterMove]).
 *
 * The main track reorders instead ([withClipReordered]); words live in the
 * editor's own list and are placed there.
 */
fun TimelineState.withClipPlaced(clipId: String, startMs: Long, row: Int): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    val start = startMs.coerceAtLeast(0L)
    return when {
        clip.isMain -> withClipMoved(clipId, start - clip.timelineStartMs)
        clip.kind == ClipKind.Video -> {
            val target = row.coerceIn(1, clip.topLayer)
            if (target != clip.layer && layerIsFree(target, start, start + clip.durationMs, clipId)) {
                copy(clips = clips.map { if (it.id == clipId) it.copy(layer = target, timelineStartMs = start) else it })
            } else {
                withClipMoved(clipId, start - clip.timelineStartMs)
            }
        }
        clip.kind == ClipKind.Audio -> {
            val sounds = audioClips
            val prefs = TimelineLanes.preferencesAfterMove(
                sounds.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) },
                clipId,
                row,
                start
            )
            val next = copy(
                clips = clips.map { c ->
                    if (c.kind != ClipKind.Audio) c
                    else c.copy(
                        layer = prefs[c.id] ?: c.layer,
                        timelineStartMs = if (c.id == clipId) start else c.timelineStartMs
                    )
                }
            )
            if (next.clips == clips) this else next
        }
        else -> this
    }
}

// ---- Overlay rows ---------------------------------------------------------------

private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
    aStart < bEnd && bStart < aEnd

/** Whether nothing else sits on overlay [layer] between [startMs] and [endMs]. */
fun TimelineState.layerIsFree(layer: Int, startMs: Long, endMs: Long, exceptId: String? = null): Boolean =
    clips.none {
        it.kind == ClipKind.Video && it.layer == layer && it.id != exceptId &&
            overlaps(startMs, endMs, it.timelineStartMs, it.timelineEndMs)
    }

/**
 * The lowest overlay row with room for [startMs]..[endMs], or null when every row
 * is taken there.
 *
 * Two overlays on one row used to be allowed, and the preview - one player per
 * row - showed only one of them while the export drew both. An overlay added
 * onto an occupied row goes to the lowest one with room instead.
 */
fun TimelineState.firstFreeLayer(startMs: Long, endMs: Long, exceptId: String? = null, top: Int = MAX_LAYER): Int? =
    (1..top).firstOrNull { layerIsFree(it, startMs, endMs, exceptId) }

/**
 * The overlay rows closed up: a row nothing is on any more - its overlay
 * deleted, moved to the main track or raised past it - is taken out, and the
 * rows above come down one, in the order they were. The strip draws a lane for
 * every row up to the highest, so an empty row was an empty lane pushing the
 * main track and the sound down. Only ever lowers a row, so footage stays within
 * [MAX_FOOTAGE_LAYER].
 */
fun TimelineState.withRowsCompacted(): TimelineState {
    val used = clips.filter { it.kind == ClipKind.Video && it.layer > 0 }.map { it.layer }.distinct().sorted()
    if (used.withIndex().all { (i, layer) -> layer == i + 1 }) return this
    val row = used.withIndex().associate { (i, layer) -> layer to i + 1 }
    return copy(clips = clips.map { if (it.kind == ClipKind.Video && it.layer > 0) it.copy(layer = row.getValue(it.layer)) else it })
}

/**
 * Adds a clip. On the main track it is slotted in where it was placed and the
 * track closes up around it. On an overlay row that is taken at that moment it
 * goes up to the first free row; when every row is taken there the state comes
 * back unchanged, and the caller has to say why nothing happened.
 */
fun TimelineState.withClipAdded(clip: Clip): TimelineState {
    if (clip.isMain) {
        val base = baseVideoClips
        val at = insertionIndex(base, clip.timelineStartMs + clip.durationMs / 2)
        return copy(clips = clips + clip, selectedClipId = clip.id)
            .layOutMain(base.take(at) + clip + base.drop(at), mainSpacing())
    }
    if (clip.kind == ClipKind.Video && clip.isOverlay &&
        (clip.layer > clip.topLayer || !layerIsFree(clip.layer, clip.timelineStartMs, clip.timelineEndMs))
    ) {
        val row = firstFreeLayer(clip.timelineStartMs, clip.timelineEndMs, top = clip.topLayer) ?: return this
        return copy(clips = clips + clip.copy(layer = row), selectedClipId = clip.id)
    }
    return copy(clips = clips + clip, selectedClipId = clip.id)
}

/**
 * Promote to an overlay row or drop back onto the main track.
 *
 * Leaving the main track closes it up behind the clip; joining it slots the clip
 * in by where it sits and makes room. Between overlay rows, a row that is taken
 * at that moment is skipped for the next free one in the same direction, and
 * when there is none the clip stays where it is.
 *
 * Only the row directly above the main track drops onto it. Joining the main
 * track re-lays every shot after the clip, so it is never where a Lower ends up
 * by skipping over busy rows: an overlay on row 3 with rows 2 and 1 taken used
 * to fall straight through into the picture and push the rest of the edit out
 * of sync with its sound, when what was asked for was one row down.
 */
fun TimelineState.withLayerChanged(clipId: String, delta: Int): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    if (clip.kind != ClipKind.Video || delta == 0) return this

    val target = if (clip.layer + delta <= 0) {
        if (clip.layer == 1) 0 else return this
    } else {
        val step = if (delta > 0) 1 else -1
        generateSequence((clip.layer + delta).coerceAtMost(clip.topLayer)) { it + step }
            .takeWhile { it in 1..clip.topLayer }
            .firstOrNull { layerIsFree(it, clip.timelineStartMs, clip.timelineEndMs, clipId) }
            ?: return this
    }
    if (target == clip.layer) return this

    // An overlay has no cut to transition across.
    val moved = clip.copy(layer = target, transitionIn = if (target > 0) Transition() else clip.transitionIn)
    val next = copy(clips = clips.map { if (it.id == clipId) moved else it })
    if (target > 0) {
        return if (clip.isMain) next.layOutMain(baseVideoClips.filterNot { it.id == clipId }, mainSpacing())
        else next.withRowsCompacted()
    }

    val others = baseVideoClips
    val at = insertionIndex(others, clip.timelineStartMs + clip.durationMs / 2)
    return next.layOutMain(others.take(at) + moved + others.drop(at), mainSpacing()).withRowsCompacted()
}

/**
 * Sets an overlay's placement, inside the one set of [TransformLimits].
 *
 * On an animated clip the static placement is never read - the keys decide - so
 * writing it there moved the sliders and not the picture. There the change is
 * written as a key at the playhead instead, from the animation's own value at
 * that moment (never the stabilizer's correction, which is measured, not drawn).
 *
 * With the playhead off the clip there is no moment of it to key. Writing the key
 * at the nearer end, as this first did, quietly rewrote the end of the move while
 * the picture on screen was some other clip. There the change moves the whole
 * animation instead - every key scaled, slid or turned by the same amount - which
 * is what a panel showing the pose at that end (see [Clip.placementAt]) implies.
 */
fun TimelineState.withOverlayGeometry(
    clipId: String,
    opacity: Float? = null,
    scale: Float? = null,
    offsetX: Float? = null,
    offsetY: Float? = null,
    rotation: Float? = null
): TimelineState = copy(
    clips = clips.map { clip ->
        if (clip.id != clipId) return@map clip
        // The opacity through its own track's rule (ValueTracks): a key at the
        // playhead once the clip has any, the one level until then.
        val faded = if (opacity == null) clip else clip.withValueAt(ValueTrack.Opacity, playheadMs, opacity, 0f..1f)
        if (clip.keyframes.isEmpty()) {
            val placed = Transform(
                scale ?: clip.scale,
                offsetX ?: clip.offsetXFraction,
                offsetY ?: clip.offsetYFraction,
                rotation ?: clip.rotation
            ).clamped()
            faded.copy(
                scale = placed.scale,
                offsetXFraction = placed.offsetXFraction,
                offsetYFraction = placed.offsetYFraction,
                rotation = placed.rotationDegrees
            )
        } else {
            val now = clip.placementAt(playheadMs)
            val placed = Transform(
                scale ?: now.scale,
                offsetX ?: now.offsetXFraction,
                offsetY ?: now.offsetYFraction,
                rotation ?: now.rotationDegrees
            ).clamped()
            val local = playheadMs - clip.timelineStartMs
            when {
                placed == now -> faded
                local in 0L..clip.durationMs -> faded.copy(
                    keyframes = clip.keyframes.upserted(
                        Keyframe(local, placed, clip.keyframes.easingAt(local)),
                        KEY_TOLERANCE_MS
                    )
                )
                else -> faded.copy(
                    keyframes = clip.keyframes.map { key ->
                        val t = key.transform
                        key.copy(
                            transform = Transform(
                                scale = if (now.scale > 0f) t.scale * placed.scale / now.scale else placed.scale,
                                offsetXFraction = t.offsetXFraction + placed.offsetXFraction - now.offsetXFraction,
                                offsetYFraction = t.offsetYFraction + placed.offsetYFraction - now.offsetYFraction,
                                rotationDegrees = t.rotationDegrees + placed.rotationDegrees - now.rotationDegrees
                            ).clamped()
                        )
                    }
                )
            }
        }
    }
)

/**
 * Where an overlay added from the picker lands: a picture-in-picture in the
 * top-right corner, small enough that the shot under it still reads.
 */
val OVERLAY_LANDING = Transform(scale = 0.4f, offsetXFraction = 0.45f, offsetYFraction = -0.45f)

/**
 * A clip's placement put back: still, with no animation, where a clip of its
 * kind lands - filling the frame on the main track, [OVERLAY_LANDING] on an
 * overlay row. The stabilizer's correction is a measurement, not a placement,
 * and is left alone; so are opacity, mask and key.
 */
fun TimelineState.withPlacementReset(clipId: String): TimelineState = copy(
    clips = clips.map { clip ->
        if (clip.id != clipId || clip.kind != ClipKind.Video) return@map clip
        val home = if (clip.isOverlay) OVERLAY_LANDING else Transform.Identity
        clip.copy(
            keyframes = emptyList(),
            scale = home.scale,
            offsetXFraction = home.offsetXFraction,
            offsetYFraction = home.offsetYFraction,
            rotation = home.rotationDegrees
        )
    }
)

// ---- Everyday edits ---------------------------------------------------------------

fun TimelineState.select(clipId: String?): TimelineState = copy(selectedClipId = clipId)

fun TimelineState.withPlayhead(ms: Long): TimelineState =
    copy(playheadMs = ms.coerceIn(0L, durationMs))

fun TimelineState.zoomedBy(factor: Float): TimelineState =
    copy(pixelsPerSecond = (pixelsPerSecond * factor).coerceIn(ZOOM_MIN, ZOOM_MAX))


/**
 * The main track re-laid after an edit, from the order it had before it: each
 * clip that was there replaced by what [replace] makes of it (itself, nothing,
 * or the halves of a cut), every clip keeping the spacing it had (see
 * [mainSpacing]). The order is taken from before the edit rather than re-sorted
 * after it, because an edit can move a clip's start past its neighbour's before
 * the layout puts it back.
 */
private fun TimelineState.relaidFrom(before: TimelineState, replace: (Clip) -> List<Clip>): TimelineState =
    layOutMain(before.baseVideoClips.flatMap(replace), before.mainSpacing())

/**
 * A clip at a new speed. On the main track the shots after it ripple with its new
 * length and keep their joins - a dissolve stays a dissolve. The editor used to
 * move the followers up to the retimed clip's end itself, which put the next shot
 * exactly on that end: a transition's overlap was lost from the strip while the
 * transition stayed set, so the export and the strip disagreed about the join.
 * Elsewhere the length changes and nothing else moves.
 */
fun TimelineState.withClipRetimed(clipId: String, ramp: SpeedRamp): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    val retimed = clip.copy(speedRamp = ramp)
    val next = copy(clips = clips.map { if (it.id == clipId) retimed else it })
    return if (clip.isMain) next.relaidFrom(this) { if (it.id == clipId) listOf(retimed) else listOf(it) } else next
}

/** Removing a main-track clip closes the hole; anywhere else the hole is the edit's. */
fun TimelineState.withClipRemoved(clipId: String): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    val next = copy(
        clips = clips.filterNot { it.id == clipId },
        selectedClipId = if (selectedClipId == clipId) null else selectedClipId
    )
    return when {
        clip.isMain -> next.relaidFrom(this) { if (it.id == clipId) emptyList() else listOf(it) }
        clip.kind == ClipKind.Video -> next.withRowsCompacted()
        else -> next
    }
}

/**
 * A copy of a clip, straight after it, selected - everything on it kept: trim,
 * speed, placement, keys, mask, key colour.
 *
 * On the main track the copy is slotted in right after the original and the
 * track makes room, so the shots after it move along by its length and keep
 * the joins they had. It has no transition of its own: a dissolve from a shot
 * into its own copy is not what anyone asked for, and the join after it keeps
 * the transition that was there.
 *
 * On an overlay row it goes where the original ends, up to the first row free
 * there if its own row is taken; with every row taken there the state comes back
 * unchanged and the caller says why. Sound goes where the original ends.
 */
fun TimelineState.withClipDuplicated(clipId: String, copyId: String): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    if (clip.kind == ClipKind.Text) return this
    val copy = clip.copy(id = copyId, timelineStartMs = clip.timelineEndMs, transitionIn = Transition())
    if (clip.isMain) {
        val laid = relaidFrom(this) { if (it.id == clipId) listOf(it, copy) else listOf(it) }
        return laid.copy(selectedClipId = copyId)
    }
    if (clip.kind == ClipKind.Video && clip.isOverlay) {
        val row = if (layerIsFree(clip.layer, copy.timelineStartMs, copy.timelineEndMs)) clip.layer
        else firstFreeLayer(copy.timelineStartMs, copy.timelineEndMs, top = clip.topLayer) ?: return this
        return copy(clips = clips + copy.copy(layer = row), selectedClipId = copyId)
    }
    return copy(clips = clips + copy, selectedClipId = copyId)
}

/**
 * Drag. [deltaMs] is how far from where it is now the finger wants the clip -
 * the strip measures a drag from where the clip was when it began (see ClipView),
 * so a clip held back by a neighbour, or moved to a new slot, still ends up
 * where the finger is.
 *
 * On the main track a drag is a change of order, not of position: the clip goes
 * where its middle would land among the others, and the track closes up around
 * it. A small drag therefore changes nothing, which is right - the track is
 * magnetic, and nudging one clip into the next is exactly the overlap it exists
 * to prevent. The swap point differs by direction (a neighbour's middle, laid
 * out with the clip on one side of it or the other), so a finger resting near
 * it does not flip the order back and forth.
 *
 * An overlay moves along its row and stops against a neighbour on it rather
 * than sliding under it; dragged far enough that the whole clip fits past the
 * neighbour, it jumps there. It never changes row on its own - a drag that did
 * would change which picture is drawn over which, and leave it there after the
 * finger had passed the obstacle. Rows are changed with Raise and Lower.
 *
 * Sound and text move freely; they are placed against the picture on purpose.
 * Every clip keeps its length, stopping at zero rather than shrinking into it.
 */
fun TimelineState.withClipMoved(clipId: String, deltaMs: Long): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    if (deltaMs == 0L) return this

    if (clip.isMain) {
        val others = baseVideoClips.filterNot { it.id == clipId }
        val at = insertionIndex(others, clip.timelineStartMs + deltaMs + clip.durationMs / 2)
        return withClipReordered(clipId, at)
    }

    var start = (clip.timelineStartMs + deltaMs).coerceAtLeast(0L)
    if (clip.kind == ClipKind.Video && clip.isOverlay &&
        !layerIsFree(clip.layer, start, start + clip.durationMs, clipId)
    ) {
        // Stop against whatever is in the way, on this side of it.
        val row = clips.filter { it.kind == ClipKind.Video && it.layer == clip.layer && it.id != clipId }
        start = if (deltaMs > 0) {
            val wall = row.filter { it.timelineStartMs >= clip.timelineEndMs }.minOfOrNull { it.timelineStartMs }
            if (wall != null) minOf(start, wall - clip.durationMs) else start
        } else {
            val wall = row.filter { it.timelineEndMs <= clip.timelineStartMs }.maxOfOrNull { it.timelineEndMs }
            if (wall != null) maxOf(start, wall) else start
        }.coerceAtLeast(0L)
        // Already overlapping where it was (a draft from before rows were kept
        // clear): leave it rather than make it worse.
        if (!layerIsFree(clip.layer, start, start + clip.durationMs, clipId)) return this
    }
    if (start == clip.timelineStartMs) return this
    val moved = clip.copy(timelineStartMs = start)
    return copy(clips = clips.map { if (it.id == clipId) moved else it })
}

/**
 * Drag a clip edge.
 *
 * The animation stays on the frames it was drawn over: the keys move with the
 * head by the played length trimmed, and none is ever removed. A key over footage
 * the trim hides is kept where it is, outside the clip, so the kept frames still
 * interpolate towards it exactly as before, and dragging the handle back out -
 * in the same gesture or a later one - brings the move back as it was. Trims
 * arrive as a stream of small steps, one per touch event; cutting keys off at
 * each step made the result depend on how fast the finger moved and threw the
 * end pose away the moment a handle went in.
 *
 * On the main track the clip stays butted to its neighbours: trimming either end
 * pulls everything after it along, and revealing earlier footage at the head
 * pushes it along. Elsewhere the kept frames stay where they are on the timeline,
 * so a head trim moves the clip's start by the played length removed - and a
 * clip cannot reveal footage from before the start of the edit, or grow into the
 * overlay next to it on its row: the handle stops there instead. A head drag
 * outwards used to leave the start where it was and grow the tail, over the
 * next clip, by the amount dragged.
 */
fun TimelineState.withClipTrimmed(clipId: String, startDeltaMs: Long, endDeltaMs: Long): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    val ramp = clip.speedRamp
    val span = clip.sourceSpanMs

    var newIn = (clip.sourceInMs + startDeltaMs).coerceIn(0L, clip.sourceOutMs - MIN_CLIP_MS)
    var maxOut = if (clip.sourceDurationMs > 0) clip.sourceDurationMs else clip.sourceOutMs + endDeltaMs

    if (!clip.isMain) {
        val row = if (clip.kind == ClipKind.Video && clip.isOverlay) {
            clips.filter { it.kind == ClipKind.Video && it.layer == clip.layer && it.id != clipId }
        } else emptyList()
        // How much played time there is to grow into on each side.
        val roomBefore = clip.timelineStartMs -
            (row.filter { it.timelineEndMs <= clip.timelineStartMs }.maxOfOrNull { it.timelineEndMs } ?: 0L)
        val wallAfter = row.filter { it.timelineStartMs >= clip.timelineEndMs }.minOfOrNull { it.timelineStartMs }
        // Source that fits in that played time, at the rate the curve holds past
        // each end. Rounded down, so the played result cannot overshoot the room.
        val revealable = (roomBefore.coerceAtLeast(0L) * ramp.speedAt(0L).toDouble()).toLong()
        newIn = newIn.coerceAtLeast(clip.sourceInMs - revealable)
        if (wallAfter != null) {
            val roomAfter = (wallAfter - clip.timelineEndMs).coerceAtLeast(0L)
            maxOut = minOf(maxOut, clip.sourceOutMs + (roomAfter * ramp.speedAt(span).toDouble()).toLong())
        }
    }
    val newOut = (clip.sourceOutMs + endDeltaMs).coerceIn(newIn + MIN_CLIP_MS, maxOf(maxOut, newIn + MIN_CLIP_MS))
    if (newIn == clip.sourceInMs && newOut == clip.sourceOutMs) return this

    // How far into the *played* clip the new head sits - negative when footage is
    // revealed before it. Not the same as how far into the file it sits: trimming
    // 100ms off a half-speed shot removes 200ms from the timeline.
    val playedShift = ramp.outputOffsetAt(newIn - clip.sourceInMs, span)

    val trimmed = clip.copy(
        sourceInMs = newIn,
        sourceOutMs = newOut,
        // The curve is anchored to the source window, so it moves with it -
        // otherwise the ramp stays put in the file while the footage slides
        // underneath it.
        speedRamp = ramp.sliced(newIn - clip.sourceInMs, newOut - clip.sourceInMs),
        timelineStartMs = (clip.timelineStartMs + playedShift).coerceAtLeast(0L),
        keyframes = clip.keyframes.shiftedBy(-playedShift),
        opacityKeys = clip.opacityKeys.shiftedBy(-playedShift),
        volumeKeys = clip.volumeKeys.shiftedBy(-playedShift)
    )
    val next = copy(clips = clips.map { if (it.id == clipId) trimmed else it })
    return if (clip.isMain) next.relaidFrom(this) { if (it.id == clipId) listOf(trimmed) else listOf(it) } else next
}

/**
 * Razor cut at the playhead: the selected clip if there is one, otherwise the
 * main-track clip under the playhead (see [TimelineState.splitTarget]).
 *
 * The selection is let go afterwards, as it always was: until the strip has a
 * way to deselect, a selection that outlived the cut would keep the next Cut
 * pinned to a half that is no longer under the playhead.
 */
fun TimelineState.withSplitAtPlayhead(selection: String? = selectedClipId): TimelineState {
    val target = splitTarget(selection) ?: return this
    // Text lives in the editor's own list, not here, and is cut there.
    if (target.kind == ClipKind.Text) return this
    val (head, tail) = target.splitAt(playheadMs) ?: return this
    val halves = { clip: Clip -> if (clip.id == target.id) listOf(head, tail) else listOf(clip) }
    val next = copy(clips = clips.flatMap(halves), selectedClipId = null)
    return if (target.isMain) next.relaidFrom(this, halves) else next
}

/**
 * Cuts every clip under the playhead that [include] accepts, on every track at
 * once - for the beat cutter, which razors the picture on each beat and passes
 * video only, so the music it is cutting to stays one clip. Each clip is cut or
 * refused by the same rule as a single cut.
 */
fun TimelineState.withSplitAllTracks(include: (Clip) -> Boolean = { true }): TimelineState {
    val cuts = clips
        .filter { it.kind != ClipKind.Text && include(it) }
        .mapNotNull { clip -> clip.splitAt(playheadMs)?.let { clip.id to it } }
        .toMap()
    if (cuts.isEmpty()) return copy(selectedClipId = null)
    val halves = { clip: Clip -> cuts[clip.id]?.toList() ?: listOf(clip) }
    val next = copy(clips = clips.flatMap(halves), selectedClipId = null)
    return if (clips.any { it.isMain && it.id in cuts }) next.relaidFrom(this, halves) else next
}
