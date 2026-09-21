package com.squish.app.timeline

import android.net.Uri
import java.util.UUID

enum class ClipKind { Video, Audio, Text }

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
    val text: String? = null
) {
    val durationMs: Long get() = (sourceOutMs - sourceInMs).coerceAtLeast(0)
    val timelineEndMs: Long get() = timelineStartMs + durationMs
    fun spans(ms: Long): Boolean = ms > timelineStartMs && ms < timelineEndMs
}

data class TimelineState(
    val clips: List<Clip> = emptyList(),
    val selectedClipId: String? = null,
    val playheadMs: Long = 0,
    val pixelsPerSecond: Float = 42f
) {
    val videoClips: List<Clip> get() = clips.filter { it.kind == ClipKind.Video }.sortedBy { it.timelineStartMs }
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
    var cursor = 0L
    val rippled = videoClips.map { clip ->
        val placed = clip.copy(timelineStartMs = cursor)
        cursor += clip.durationMs
        placed
    }
    val others = clips.filter { it.kind != ClipKind.Video }
    return copy(clips = rippled + others)
}

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
