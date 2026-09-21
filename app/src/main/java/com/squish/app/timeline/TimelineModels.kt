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
    val speed: Float = 1f,
    val text: String? = null,

    /** Transition into this clip from whatever precedes it on the same layer. */
    val transitionIn: Transition = Transition(),

    // Compositing. Layer 0 is the base picture; anything above floats over it,
    // which is how a picture-in-picture, a logo bug or a reaction cam is built.
    val layer: Int = 0,
    val opacity: Float = 1f,
    val scale: Float = 1f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f
) {
    val durationMs: Long get() = (sourceOutMs - sourceInMs).coerceAtLeast(0)
    val timelineEndMs: Long get() = timelineStartMs + durationMs
    fun spans(ms: Long): Boolean = ms > timelineStartMs && ms < timelineEndMs
    val isOverlay: Boolean get() = layer > 0
}

data class TimelineState(
    val clips: List<Clip> = emptyList(),
    val selectedClipId: String? = null,
    val playheadMs: Long = 0,
    val pixelsPerSecond: Float = 42f
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
}

const val MIN_CLIP_MS = 200L
private const val ZOOM_MIN = 8f
private const val ZOOM_MAX = 400f

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

fun TimelineState.withTransition(clipId: String, transition: Transition): TimelineState =
    copy(clips = clips.map { if (it.id == clipId) it.copy(transitionIn = transition) else it }).rippleVideo()

/** Promote to an overlay layer or drop back onto the base picture. */
fun TimelineState.withLayerChanged(clipId: String, delta: Int): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    if (clip.kind != ClipKind.Video) return this
    val newLayer = (clip.layer + delta).coerceIn(0, MAX_LAYER)
    if (newLayer == clip.layer) return this

    // Coming off the base track it keeps its position; going onto it, ripple decides.
    val moved = clip.copy(layer = newLayer, transitionIn = if (newLayer > 0) Transition() else clip.transitionIn)
    return copy(clips = clips.map { if (it.id == clipId) moved else it }).rippleVideo()
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

fun TimelineState.withClipAdded(clip: Clip): TimelineState {
    val next = copy(clips = clips + clip, selectedClipId = clip.id)
    return if (clip.kind == ClipKind.Video) next.rippleVideo() else next
}

fun TimelineState.withClipRemoved(clipId: String): TimelineState {
    val removed = clips.firstOrNull { it.id == clipId } ?: return this
    val next = copy(
        clips = clips.filterNot { it.id == clipId },
        selectedClipId = if (selectedClipId == clipId) null else selectedClipId
    )
    return if (removed.kind == ClipKind.Video) next.rippleVideo() else next
}

/** Drag. Video reorders against its neighbours; audio and text move freely. */
fun TimelineState.withClipMoved(clipId: String, deltaMs: Long): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this

    if (clip.kind != ClipKind.Video) {
        val moved = clip.copy(timelineStartMs = (clip.timelineStartMs + deltaMs).coerceAtLeast(0L))
        return copy(clips = clips.map { if (it.id == clipId) moved else it })
    }

    val ordered = videoClips.toMutableList()
    val index = ordered.indexOfFirst { it.id == clipId }
    if (index < 0) return this
    val target = clip.timelineStartMs + deltaMs
    var newIndex = index
    if (deltaMs < 0 && index > 0 && target < ordered[index - 1].timelineStartMs + ordered[index - 1].durationMs / 2) {
        newIndex = index - 1
    } else if (deltaMs > 0 && index < ordered.lastIndex &&
        target + clip.durationMs > ordered[index + 1].timelineStartMs + ordered[index + 1].durationMs / 2
    ) {
        newIndex = index + 1
    }
    if (newIndex == index) return this

    ordered.removeAt(index)
    ordered.add(newIndex, clip)
    return copy(clips = ordered + clips.filter { it.kind != ClipKind.Video }).rippleVideo()
}

/** Drag a clip edge. Trims the source window without moving anything else. */
fun TimelineState.withClipTrimmed(clipId: String, startDeltaMs: Long, endDeltaMs: Long): TimelineState {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this

    val newIn = (clip.sourceInMs + startDeltaMs).coerceIn(0L, clip.sourceOutMs - MIN_CLIP_MS)
    val maxOut = if (clip.sourceDurationMs > 0) clip.sourceDurationMs else clip.sourceOutMs + endDeltaMs
    val newOut = (clip.sourceOutMs + endDeltaMs).coerceIn(newIn + MIN_CLIP_MS, maxOut)

    val trimmed = clip.copy(
        sourceInMs = newIn,
        sourceOutMs = newOut,
        // Trimming the head of a freely placed clip keeps its on-screen position.
        timelineStartMs = if (clip.kind == ClipKind.Video) clip.timelineStartMs
        else (clip.timelineStartMs + startDeltaMs).coerceAtLeast(0L)
    )
    val next = copy(clips = clips.map { if (it.id == clipId) trimmed else it })
    return if (clip.kind == ClipKind.Video) next.rippleVideo() else next
}

/**
 * Razor cut at the playhead. Splits whichever clip the playhead is inside on each
 * track, which is what a cut means to an editor - not just the selected one.
 */
fun TimelineState.withSplitAtPlayhead(): TimelineState {
    val cut = playheadMs
    val victims = clips.filter { it.spans(cut) && it.durationMs > MIN_CLIP_MS * 2 }
    if (victims.isEmpty()) return this

    val rebuilt = clips.flatMap { clip ->
        if (clip !in victims) listOf(clip)
        else {
            val offset = cut - clip.timelineStartMs
            listOf(
                clip.copy(sourceOutMs = clip.sourceInMs + offset),
                clip.copy(
                    id = UUID.randomUUID().toString(),
                    sourceInMs = clip.sourceInMs + offset,
                    timelineStartMs = cut
                )
            )
        }
    }
    return copy(clips = rebuilt, selectedClipId = null)
}
