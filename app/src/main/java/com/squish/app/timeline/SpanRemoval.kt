package com.squish.app.timeline

/**
 * Taking a stretch of time out of the edit, picture and sound together, and
 * closing up behind it.
 *
 * This is what editing by transcript does: a word is a moment, a run of words is
 * a stretch, and deleting them has to mean deleting the footage under them and
 * pulling the rest back - not leaving a hole. It is also what "remove the filler
 * words" and "remove the silences" want, which is why it lives here as one
 * operation on the model rather than inside a sheet.
 *
 * The cut is made on every track at once, the same way the beat cutter razors
 * them, so a song under the picture is cut at the same two moments and keeps
 * step. Overlays and sounds inside the stretch go with it; ones that straddle an
 * edge are cut at it and keep the part that survives.
 */
fun TimelineState.withSpanRemoved(fromMs: Long, toMs: Long): TimelineState {
    val start = minOf(fromMs, toMs).coerceAtLeast(0L)
    val end = maxOf(fromMs, toMs)
    // A stretch shorter than a frame is not a stretch; cutting twice at the same
    // moment leaves the second cut with nothing to do and the track unchanged.
    if (end - start < MIN_SPAN_MS) return this

    // Cut at both ends first, so everything that crosses an edge becomes two
    // clips and the stretch holds only whole ones.
    val cut = withPlayhead(start).withSplitAllTracks().withPlayhead(end).withSplitAllTracks()

    val inside = cut.clips.filter { it.timelineStartMs >= start - EDGE_MS && it.timelineEndMs <= end + EDGE_MS }
    if (inside.isEmpty()) return this

    // Removed one at a time so each main-track removal lays the track out
    // behind it, which is what closes the gap.
    var next = inside.fold(cut) { state, clip -> state.withClipRemoved(clip.id) }

    // Everything after the stretch comes back by what the *picture* lost. The
    // main track has already closed up (withClipRemoved relays it); the other
    // rows have not, so they are pulled back here or the sound would sit a
    // stretch late.
    //
    // By the picture's own loss, not by `end - start`. The relay keeps each
    // shot's gap from the one before it (mainSpacing), so a stretch that covers
    // a gap on the main track closes by less than its own length - and the
    // sounds and lines over it were pulled back by the whole of it, landing
    // that gap early, off the words they were cued to. With no main track at
    // all there is no picture to measure against and the stretch itself is the
    // answer.
    val before = cut.baseVideoClips.maxOfOrNull { it.timelineEndMs } ?: 0L
    val after = next.baseVideoClips.maxOfOrNull { it.timelineEndMs } ?: 0L
    val span = if (cut.baseVideoClips.isEmpty()) end - start else (before - after).coerceAtLeast(0L)
    val shifted = HashSet<String>()
    next = next.copy(
        clips = next.clips.map { clip ->
            if (!clip.isMain && clip.timelineStartMs >= end - EDGE_MS) {
                shifted += clip.id
                clip.copy(timelineStartMs = (clip.timelineStartMs - span).coerceAtLeast(0L))
            } else clip
        }
    )

    // An overlay that has moved into one that has not, on the same row, goes up
    // to a row with room - the same rule withSilencesRemoved follows, and for
    // the same reason: two on one row is one player in the preview and two
    // layers in the file, so the screen and the file disagree.
    //
    // It can happen because a cut at either edge is refused when it would leave
    // a piece under MIN_CLIP_MS: that overlay stays where it is, is not inside
    // the stretch, and is not shifted - while the next one on its row slides
    // back into it. Pictures only, and never past the row a clip may sit on
    // (Clip.topLayer): each row of footage is a decoder.
    var seated = next
    next.clips.filter { it.kind == ClipKind.Video && it.isOverlay && it.id in shifted }.forEach { o ->
        if (!seated.layerIsFree(o.layer, o.timelineStartMs, o.timelineEndMs, o.id)) {
            val row = seated.firstFreeLayer(o.timelineStartMs, o.timelineEndMs, o.id, top = o.topLayer)
            if (row != null) seated = seated.copy(clips = seated.clips.map { c -> if (c.id == o.id) c.copy(layer = row) else c })
        }
    }
    return seated.withRowsCompacted().withPlayhead(start)
}

/**
 * Several stretches taken out at once, back to front.
 *
 * Back to front because every removal moves everything after it: taken in the
 * order they were found, the second stretch's times would already be wrong by
 * the length of the first.
 */
fun TimelineState.withSpansRemoved(spans: List<LongRange>): TimelineState {
    if (spans.isEmpty()) return this
    val merged = merge(spans)
    return merged.sortedByDescending { it.first }
        .fold(this) { state, span -> state.withSpanRemoved(span.first, span.last) }
}

/** Overlapping or touching stretches are one stretch; cutting twice in the same place is not. */
internal fun merge(spans: List<LongRange>): List<LongRange> {
    val sorted = spans.filter { it.last > it.first }.sortedBy { it.first }
    if (sorted.isEmpty()) return emptyList()
    val out = mutableListOf(sorted.first())
    for (s in sorted.drop(1)) {
        val last = out.last()
        if (s.first <= last.last + EDGE_MS) out[out.lastIndex] = last.first..maxOf(last.last, s.last)
        else out += s
    }
    return out
}

/** Shorter than this and there is nothing to take out. A frame at 60 fps is 16 ms. */
const val MIN_SPAN_MS = 16L

/** Rounding slack: a cut lands on a frame boundary, not on the exact millisecond asked for. */
private const val EDGE_MS = 2L
