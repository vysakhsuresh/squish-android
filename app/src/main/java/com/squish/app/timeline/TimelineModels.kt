package com.squish.app.timeline

import android.net.Uri
import java.util.UUID

enum class ClipKind { Video, Audio, Text }

enum class TransitionType(val label: String) {
    None("Cut"),
    CrossFade("Dissolve"),
    DipToBlack("Dip to black"),
    SlideLeft("Slide"),
    WipeRight("Wipe")
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
    val stabilizer: List<Keyframe> = emptyList()
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
    }
}

data class TimelineState(
    val clips: List<Clip> = emptyList(),
    val selectedClipId: String? = null,
    val playheadMs: Long = 0,
    val pixelsPerSecond: Float = 42f,
    /** Each sound file's waveform, by URI, drawn on its clips. */
    val waveforms: Map<String, com.squish.app.media.audio.Waveform> = emptyMap()
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

    /**
     * Added sounds packed into as few rows as they will fit, by the same greedy
     * rule every editor uses: a clip goes on the first row where nothing already
     * occupies its span. Two tracks that never overlap therefore share a row, and
     * ones that do get a row each - so a music bed under a voiceover reads as two
     * things rather than one drawn on top of the other.
     */
    val audioLanes: List<List<Clip>>
        get() {
            val lanes = mutableListOf<MutableList<Clip>>()
            audioClips.forEach { clip ->
                val lane = lanes.firstOrNull { row ->
                    row.none { it.timelineStartMs < clip.timelineEndMs && clip.timelineStartMs < it.timelineEndMs }
                }
                if (lane != null) lane.add(clip) else lanes.add(mutableListOf(clip))
            }
            return lanes
        }
    val textClips: List<Clip> get() = clips.filter { it.kind == ClipKind.Text }.sortedBy { it.timelineStartMs }
    val durationMs: Long get() = clips.maxOfOrNull { it.timelineEndMs } ?: 0L
    val selectedClip: Clip? get() = clips.firstOrNull { it.id == selectedClipId }
}

const val MIN_CLIP_MS = 200L
const val ZOOM_MIN = 0.05f
const val ZOOM_MAX = 2_000f

/**
 * Video runs as one continuous strip - the way every cuts-only editor behaves -
 * so removing or reordering a clip closes the gap instead of leaving a hole where
 * the picture would go black. Audio and text stay where they are put, because a
 * music cue or a caption is deliberately placed against the picture.
 */
fun TimelineState.rippleVideo(): TimelineState {
    val base = baseVideoClips
    var cursor = 0L
    val rippled = base.mapIndexed { index, clip ->
        // A transition is an overlap, so the incoming clip starts early and the
        // edit gets shorter. Capped at half the shorter shot so a long dissolve
        // between two short clips cannot swallow either of them.
        val overlap = if (index == 0 || !clip.transitionIn.isActive) 0L else {
            clip.transitionIn.durationMs.coerceAtMost(minOf(clip.durationMs, base[index - 1].durationMs) / 2)
        }
        val start = (cursor - overlap).coerceAtLeast(0L)
        val placed = clip.copy(timelineStartMs = start)
        cursor = start + clip.durationMs
        placed
    }
    val others = clips.filter { !(it.kind == ClipKind.Video && it.layer == 0) }
    return copy(clips = rippled + others)
}

/**
 * Setting a transition pulls only this clip back over its predecessor by the
 * overlap. Everything else stays exactly where the editor put it.
 */
fun TimelineState.withTransition(clipId: String, transition: Transition): TimelineState {
    val base = baseVideoClips
    val index = base.indexOfFirst { it.id == clipId }
    val tagged = clips.map { if (it.id == clipId) it.copy(transitionIn = transition) else it }
    if (index <= 0) return copy(clips = tagged)

    val clip = base[index]
    val previous = base[index - 1]
    val overlap = if (transition.isActive) {
        transition.durationMs.coerceAtMost(minOf(clip.durationMs, previous.durationMs) / 2)
    } else 0L
    val start = (previous.timelineEndMs - overlap).coerceAtLeast(0L)

    return copy(
        clips = tagged.map {
            if (it.id == clipId) it.copy(timelineStartMs = start) else it
        }
    )
}

/** Promote to an overlay layer or drop back onto the base picture. */
fun TimelineState.withLayerChanged(clipId: String, delta: Int): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    if (clip.kind != ClipKind.Video) return this
    val newLayer = (clip.layer + delta).coerceIn(0, MAX_LAYER)
    if (newLayer == clip.layer) return this

    // Keeps its position either way; an overlay has no cut transition to carry.
    val moved = clip.copy(layer = newLayer, transitionIn = if (newLayer > 0) Transition() else clip.transitionIn)
    return copy(clips = clips.map { if (it.id == clipId) moved else it })
}

fun TimelineState.withOverlayGeometry(
    clipId: String,
    opacity: Float? = null,
    scale: Float? = null,
    offsetX: Float? = null,
    offsetY: Float? = null
): TimelineState = copy(
    clips = clips.map { clip ->
        if (clip.id != clipId) clip
        else clip.copy(
            opacity = (opacity ?: clip.opacity).coerceIn(0f, 1f),
            scale = (scale ?: clip.scale).coerceIn(0.1f, 2f),
            offsetXFraction = (offsetX ?: clip.offsetXFraction).coerceIn(-1f, 1f),
            offsetYFraction = (offsetY ?: clip.offsetYFraction).coerceIn(-1f, 1f)
        )
    }
)

const val MAX_LAYER = 3

fun TimelineState.select(clipId: String?): TimelineState = copy(selectedClipId = clipId)

fun TimelineState.withPlayhead(ms: Long): TimelineState =
    copy(playheadMs = ms.coerceIn(0L, durationMs))

fun TimelineState.zoomedBy(factor: Float): TimelineState =
    copy(pixelsPerSecond = (pixelsPerSecond * factor).coerceIn(ZOOM_MIN, ZOOM_MAX))

fun TimelineState.withClipAdded(clip: Clip): TimelineState =
    copy(clips = clips + clip, selectedClipId = clip.id)

/** Removing leaves the hole where it was; closing it is the editor's decision. */
fun TimelineState.withClipRemoved(clipId: String): TimelineState = copy(
    clips = clips.filterNot { it.id == clipId },
    selectedClipId = if (selectedClipId == clipId) null else selectedClipId
)

/**
 * Drag. Every clip moves freely along its lane, including video.
 *
 * It used to reorder video against its neighbours and then re-ripple, which meant
 * a single clip could not be moved at all and a dragged one always snapped back.
 * Free positioning is what makes manual sync possible: slide the picture against
 * the sound until it lines up. [rippleVideo] is still available as a deliberate
 * "close the gaps" action rather than something that fires behind your back.
 */
fun TimelineState.withClipMoved(clipId: String, deltaMs: Long): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    val moved = clip.copy(timelineStartMs = (clip.timelineStartMs + deltaMs).coerceAtLeast(0L))
    return copy(clips = clips.map { if (it.id == clipId) moved else it })
}

/** Drag a clip edge. Trims the source window without moving anything else. */
fun TimelineState.withClipTrimmed(clipId: String, startDeltaMs: Long, endDeltaMs: Long): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this

    val newIn = (clip.sourceInMs + startDeltaMs).coerceIn(0L, clip.sourceOutMs - MIN_CLIP_MS)
    val maxOut = if (clip.sourceDurationMs > 0) clip.sourceDurationMs else clip.sourceOutMs + endDeltaMs
    val newOut = (clip.sourceOutMs + endDeltaMs).coerceIn(newIn + MIN_CLIP_MS, maxOut)

    // How far into the *played* clip the new head sits. Not the same as how far
    // into the file it sits: trimming 100ms off a half-speed shot removes 200ms
    // from the timeline, and using the source figure would leave the clip sitting
    // in the wrong place by the difference.
    val playedShift = clip.speedRamp.outputOffsetAt(newIn - clip.sourceInMs, clip.sourceSpanMs)

    val trimmed = clip.copy(
        sourceInMs = newIn,
        sourceOutMs = newOut,
        // The curve is anchored to the source window, so it moves with it -
        // otherwise the ramp stays put in the file while the footage slides
        // underneath it.
        speedRamp = clip.speedRamp.sliced(newIn - clip.sourceInMs, newOut - clip.sourceInMs),
        // Dragging the head in moves the clip's start with it, so the frames you
        // keep stay put on the timeline instead of sliding under the playhead.
        timelineStartMs = (clip.timelineStartMs + playedShift).coerceAtLeast(0L)
    )
    return copy(clips = clips.map { if (it.id == clipId) trimmed else it })
}

/**
 * Razor cut at the playhead. Splits whichever clip the playhead is inside on each
 * track, which is what a cut means to an editor - not just the selected one.
 */
fun TimelineState.withSplitAtPlayhead(): TimelineState {
    val cut = playheadMs
    val victims = clips.filter { it.spans(cut) && it.sourceSpanMs > MIN_CLIP_MS * 2 }
    if (victims.isEmpty()) return this

    val rebuilt = clips.flatMap { clip ->
        if (clip !in victims) listOf(clip)
        else {
            // The playhead is in played time; the cut has to land on a frame of
            // the file. On a ramped clip those are different numbers, and using
            // the played offset as a source offset cuts the wrong frame by
            // however much the ramp has bent time up to that point.
            val played = cut - clip.timelineStartMs
            val offset = clip.speedRamp
                .sourceOffsetAt(played, clip.sourceSpanMs)
                .coerceIn(MIN_CLIP_MS, (clip.sourceSpanMs - MIN_CLIP_MS).coerceAtLeast(MIN_CLIP_MS))
            val span = clip.sourceSpanMs

            listOf(
                clip.copy(
                    sourceOutMs = clip.sourceInMs + offset,
                    speedRamp = clip.speedRamp.sliced(0L, offset)
                ),
                clip.copy(
                    id = UUID.randomUUID().toString(),
                    sourceInMs = clip.sourceInMs + offset,
                    timelineStartMs = cut,
                    speedRamp = clip.speedRamp.sliced(offset, span)
                )
            )
        }
    }
    return copy(clips = rebuilt, selectedClipId = null)
}
