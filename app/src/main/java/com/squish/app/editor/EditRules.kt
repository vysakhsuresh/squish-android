package com.squish.app.editor

import com.squish.app.timeline.SpeedRamp
import kotlin.math.abs

/** A stretch of time, start inclusive, end exclusive. */
data class Span(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * The arithmetic behind the view model's edits, kept free of Android so it can be
 * compiled and executed on the JVM (tools/jvm/EditRulesChecks.kt).
 *
 * Every function here answers one question an edit has to get right - how far a
 * caption may be dragged, where a title goes when the playhead is parked on the
 * last frame, which markers a beat grid may replace - and each was wrong at least
 * once in the view model, where nothing could run it.
 */
object EditRules {

    /**
     * How far a timed item may actually move when dragged by [deltaMs].
     *
     * The whole item moves or none of it does. Clamping the two ends separately
     * made a caption dragged past zero shrink instead of stopping - its start
     * held at zero while its end kept going - and nothing stopped one being
     * dragged past the last frame, where the export never draws it.
     */
    fun clampedShift(startMs: Long, endMs: Long, deltaMs: Long, pictureEndMs: Long): Long {
        val earliest = -startMs.coerceAtLeast(0L)
        // Already past the end (a draft from before this rule) may still come back.
        val latest = (pictureEndMs - endMs).coerceAtLeast(0L)
        return deltaMs.coerceIn(earliest, latest)
    }

    /**
     * An item's ends pulled by the given amounts: never past each other, never
     * shorter than [minMs], never before zero, and never pulled out past the end
     * of the picture. An end that was not moved stays exactly where it was, even
     * on an item already shorter than the minimum.
     */
    fun resized(
        startMs: Long,
        endMs: Long,
        startDeltaMs: Long,
        endDeltaMs: Long,
        pictureEndMs: Long,
        minMs: Long
    ): Span {
        val start = if (startDeltaMs == 0L) startMs
        else (startMs + startDeltaMs).coerceIn(0L, (endMs - minMs).coerceAtLeast(0L))
        val limit = maxOf(pictureEndMs, endMs, start + minMs)
        val end = if (endDeltaMs == 0L) endMs
        else (endMs + endDeltaMs).coerceIn(start + minMs, limit)
        return Span(start, end)
    }

    /**
     * Where a new title, caption or sticker of [lengthMs] goes when added at
     * [playheadMs].
     *
     * From the playhead, cut off at the end of the picture - unless that leaves
     * less than [minMs], which is what happens after watching the edit through:
     * the playhead parks on the last frame, and "add a title" then made one that
     * started where the video ended, in neither the preview nor the file. It ends
     * on the last frame instead.
     */
    fun placedAt(playheadMs: Long, lengthMs: Long, pictureEndMs: Long, minMs: Long): Span {
        if (pictureEndMs <= 0L) return Span(playheadMs.coerceAtLeast(0L), playheadMs.coerceAtLeast(0L) + lengthMs)
        if (pictureEndMs <= minMs) return Span(0L, pictureEndMs)
        val start = playheadMs.coerceIn(0L, pictureEndMs)
        val end = minOf(start + lengthMs, pictureEndMs)
        if (end - start >= minMs) return Span(start, end)
        val backed = (pictureEndMs - lengthMs).coerceAtLeast(0L)
        return Span(backed, pictureEndMs)
    }

    /** Where a sound added at the playhead goes, and how much of its file plays. */
    data class SoundLanding(val timelineStartMs: Long, val sourceOutMs: Long)

    /**
     * Where a sound of [trackMs] added at [playheadMs] lands on an edit that
     * ends at [editEndMs].
     *
     * At the playhead, ending with the edit: a song is usually longer than the
     * clip it goes under, and left whole it stretched the edit to the song's
     * length - a minute of black after an eight-second video. The rest of the
     * song is still there; drag the end out to use it. A file that fits in the
     * room left plays whole, where it was put - a sound effect on the last beat
     * stays on the last beat.
     *
     * One that does not fit, with under [lastMomentMs] of room - the playhead
     * parked on the end after watching the edit through, or a moment short of
     * it - is backed up to end with the edit, as a title is (see [placedAt]):
     * a song then covers the picture from the start. On the phone a song
     * added with the playhead half a second from the end got half a second of
     * itself under the last frames, out of sight under the sheet, and looked as
     * if nothing had been added at all. Past the end (a draft from before the
     * playhead was held to the edit) there is nothing to end with, so the sound
     * goes in whole at the playhead.
     */
    fun soundLanding(playheadMs: Long, editEndMs: Long, trackMs: Long, lastMomentMs: Long): SoundLanding {
        val playhead = playheadMs.coerceAtLeast(0L)
        if (editEndMs <= 0L) return SoundLanding(playhead, trackMs)
        val room = editEndMs - playhead
        if (room < 0L || trackMs <= room) return SoundLanding(playhead, trackMs)
        if (room >= lastMomentMs) return SoundLanding(playhead, room)
        val start = (editEndMs - trackMs).coerceAtLeast(0L)
        return SoundLanding(start, editEndMs - start)
    }

    /**
     * An item cut in two at [atMs], or null when either half would be shorter than
     * [minMs] - a cut there would leave a sliver nobody could grab.
     */
    fun splitAt(startMs: Long, endMs: Long, atMs: Long, minMs: Long): Pair<Span, Span>? {
        if (atMs - startMs < minMs || endMs - atMs < minMs) return null
        return Span(startMs, atMs) to Span(atMs, endMs)
    }

    /**
     * Whether Cut means the selected caption, sticker or effect rather than the
     * tracks: only with the playhead on it. Adding text selects it, so "selected"
     * alone meant a Cut ten seconds further on, nowhere near the sticker, did
     * nothing at all - where it used to cut the video and music under the
     * playhead.
     */
    fun cutsItem(startMs: Long, endMs: Long, atMs: Long): Boolean = atMs in (startMs + 1) until endMs

    /**
     * The markers after "Snap to the beat": the chosen beats, plus every marker
     * placed by hand.
     *
     * A marker within [toleranceMs] of any beat in the grid is taken to be one an
     * earlier snap put there, and is replaced; anything else was placed by hand
     * and is kept. It used to replace the whole list, so a marker dropped on the
     * one frame that mattered went the moment the grid was snapped to.
     */
    fun mergedBeatMarkers(
        markers: List<Long>,
        allBeats: List<Long>,
        chosen: List<Long>,
        toleranceMs: Long
    ): List<Long> {
        val manual = markers.filter { m -> allBeats.none { abs(it - m) <= toleranceMs } }
        val merged = ArrayList<Long>()
        for (m in (manual + chosen).sorted()) {
            if (merged.isEmpty() || m - merged.last() > toleranceMs) merged.add(m)
        }
        return merged
    }

    /**
     * The curve with nothing slower than [minSpeed], and otherwise its own shape.
     *
     * "Keep it smooth" used to replace a ramp with one flat rate, so a bullet-time
     * curve - fast, slow, fast - became a clip that crawled from end to end. Only
     * the parts that are too slow are raised; the rest of the curve is left as it
     * was drawn. The rate between points is interpolated linearly, so raising the
     * points is enough to raise every moment between them.
     */
    fun heldAtLeast(ramp: SpeedRamp, minSpeed: Float): SpeedRamp {
        if (ramp.points.isEmpty()) return if (1f >= minSpeed) ramp else SpeedRamp.flat(minSpeed)
        if (ramp.ordered.all { it.speed >= minSpeed }) return ramp
        return SpeedRamp(ramp.ordered.map { if (it.speed < minSpeed) it.copy(speed = minSpeed) else it })
    }

    /** Where a synced sound starts in its own file, where it sits on the timeline, and where it ends. */
    data class SyncPlacement(val sourceInMs: Long, val timelineStartMs: Long, val sourceOutMs: Long)

    /**
     * Media put into the main track: [index] clips stay before it, the first new
     * clip starts at [atMs], and every clip from [index] on moves [followersShiftMs].
     */
    data class Insertion(val index: Int, val atMs: Long, val followersShiftMs: Long)

    /**
     * A sound placed so that it lines up with the picture.
     *
     * [fileOffsetMs] is the analyser's answer - how far the sound file runs ahead
     * of the video *file*. The picture on the timeline is not the file, though: the
     * head clip plays file time `t + headDeltaMs` at timeline time `t`, where the
     * delta is its source in-point less its timeline start. Leaving that out was
     * right only for an untrimmed clip sitting at zero; after a trim and "close
     * gaps", or with the clip dragged along, every synced sound landed off by
     * exactly that much.
     *
     * The sound is placed as early as it can be while keeping that alignment:
     * from the top of the timeline, entering the file part-way when it runs
     * ahead, or starting later when it runs behind.
     */
    fun syncPlacement(
        fileOffsetMs: Long,
        headDeltaMs: Long,
        currentOutMs: Long,
        sourceDurationMs: Long,
        minMs: Long
    ): SyncPlacement {
        val delta = fileOffsetMs + headDeltaMs
        val ceiling = if (sourceDurationMs > 0L) sourceDurationMs else maxOf(currentOutMs, delta + minMs)
        val sourceIn = delta.coerceAtLeast(0L).coerceAtMost((ceiling - minMs).coerceAtLeast(0L))
        val start = (-delta).coerceAtLeast(0L)
        val out = currentOutMs.coerceIn(minOf(sourceIn + minMs, ceiling), ceiling.coerceAtLeast(sourceIn + minMs))
        return SyncPlacement(sourceIn, start, out)
    }

    /** [ids] with [id] moved to [index] - counted in the list as it will be. */
    fun reordered(ids: List<String>, id: String, index: Int): List<String> {
        if (id !in ids) return ids
        val without = ids.filterNot { it == id }
        val at = index.coerceIn(0, without.size)
        return without.subList(0, at) + id + without.subList(at, without.size)
    }

    /**
     * Where media added "at the playhead" goes on the main track, and how far the
     * shots after it move to make room.
     *
     * On a cut, never inside a shot. Inside a clip it is whichever of its two
     * edges is nearer - the one the person was closer to - and in a gap it is
     * straight after the clip before it. Past the end it is the end.
     *
     * A cut is between two clips, not at a moment: with a transition the second
     * clip starts before the first one ends. So the new media goes after the
     * first clip's *end*, and the clips from the second on move far enough that
     * none of them overlaps it - the second keeps its transition, now over the
     * new clip, cut down to half the new clip if that is shorter. Placing at a
     * moment and moving only the clips that started after it left the second
     * clip where it was, under the new one: two shots on the main track at once.
     *
     * @param spans the main track's clips, in track order.
     * @param addedMs the lengths of what is being added, in order.
     */
    fun insertion(spans: List<Span>, playheadMs: Long, addedMs: List<Long>): Insertion {
        if (spans.isEmpty()) return Insertion(0, 0L, 0L)
        val inside = spans.indexOfFirst { playheadMs > it.startMs && playheadMs < it.endMs }
        val index = if (inside >= 0) {
            val s = spans[inside]
            if (playheadMs - s.startMs <= s.endMs - playheadMs) inside else inside + 1
        } else spans.count { it.endMs <= playheadMs }
        val at = if (index == 0) spans[0].startMs.coerceAtMost(playheadMs.coerceAtLeast(0L)) else spans[index - 1].endMs
        if (index == spans.size) return Insertion(index, at, 0L)
        val next = spans[index]
        // How far the next clip reached back over the cut, and how much of that
        // it can keep over the last of the new clips.
        val reachBack = (at - next.startMs).coerceAtLeast(0L)
        val kept = minOf(reachBack, (addedMs.lastOrNull() ?: 0L) / 2)
        return Insertion(index, at, addedMs.sum() + reachBack - kept)
    }

    /**
     * Speech found in a file, cut to the part of it a clip actually plays.
     *
     * [segments] are in the file's own time. A caption for speech the clip trims
     * away is a caption over the wrong picture, so anything outside the window
     * goes, and anything straddling its edge is cut at the edge. What is left
     * shorter than [minMs] is a fragment of a word, and goes too.
     */
    fun speechInWindow(segments: List<Span>, sourceInMs: Long, sourceOutMs: Long, minMs: Long): List<Span> =
        segments.mapNotNull { s ->
            val start = maxOf(s.startMs, sourceInMs)
            val end = minOf(s.endMs, sourceOutMs)
            if (end - start < minMs) null else Span(start, end)
        }
}
