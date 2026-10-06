package com.squish.app.timeline

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Something on a free-placement track - a sound, a line of text, an effect - as
 * the row packer sees it: where it is, and which row it would rather be on.
 */
data class LaneItem(val id: String, val startMs: Long, val endMs: Long, val preferredRow: Int = 0)

/** Where a snapped span starts, and the moment it snapped to - drawn as a line - if it did. */
data class Snap(val startMs: Long, val lineMs: Long?)

/**
 * Where a clip lifted off the main track would land: its place in the running
 * order among the others (what `reorderClip` takes), and the join drawn as the
 * insertion slot while the finger is down.
 */
data class ReorderSlot(val index: Int, val atMs: Long)

/**
 * The strip's arithmetic, apart from anything it draws: which row each item
 * goes on, what a dragged edge snaps to, what a trim handle asks of the clip,
 * where a lifted shot would land. Pure, so it is executed on the JVM
 * (`tools/jvm/LaneChecks.kt`) rather than reasoned about.
 */
object TimelineLanes {

    /** Close enough to a butt cut that a retime carries the next clip along. */
    const val TOUCHING_MS = 40L

    /**
     * A row for every item, so nothing on a row overlaps anything else on it.
     *
     * Sound and words used to share one row each, overlaps drawn on top of one
     * another with the shortest in front and a tap cycling down through the pile.
     * It worked and nobody could see that it did: a song under a sound effect
     * looked like the effect alone until it was tapped twice. Each overlap now
     * gets a row of its own, as it does in every editor people already know.
     *
     * Each item keeps the row it prefers where nothing already there is in the
     * way - rows are placed lowest preference first, then earliest first, so the
     * result does not depend on the order the list happens to be in - and
     * otherwise takes the lowest free row. Rows nothing ended up on are closed
     * up, so there is never a blank row to scroll past.
     */
    fun rows(items: List<LaneItem>): Map<String, Int> {
        if (items.isEmpty()) return emptyMap()
        val taken = mutableListOf<MutableList<LongArray>>()
        val raw = HashMap<String, Int>()
        // A preference can never usefully be past one row per item; a larger one
        // (a hand-edited draft, say) would otherwise allocate rows for nothing.
        val cap = items.size
        items.sortedWith(compareBy<LaneItem>({ it.preferredRow.coerceIn(0, cap) }, { it.startMs }, { it.id }))
            .forEach { item ->
                // A zero-length item still occupies its moment.
                val end = maxOf(item.endMs, item.startMs + 1)
                fun free(row: Int) = taken.getOrNull(row)?.none { it[0] < end && item.startMs < it[1] } ?: true
                val wanted = item.preferredRow.coerceIn(0, cap)
                val row = if (free(wanted)) wanted else generateSequence(0) { it + 1 }.first { free(it) }
                while (taken.size <= row) taken.add(mutableListOf())
                taken[row].add(longArrayOf(item.startMs, end))
                raw[item.id] = row
            }
        val compact = raw.values.distinct().sorted().withIndex().associate { (i, row) -> row to i }
        return raw.mapValues { compact.getValue(it.value) }
    }

    /** How many rows [rows] used. */
    fun rowCount(rows: Map<String, Int>): Int = (rows.values.maxOrNull() ?: -1) + 1

    /**
     * The preferences to store after [movedId] is dropped at [startMs] on
     * [targetRow]: every other item's preference set to the row it is shown on
     * now, so nothing the finger did not touch changes row because a stored
     * preference from long ago suddenly came free.
     *
     * The moved item's is a row that is free for it there - [targetRow] if it is,
     * else the nearest one below it, else above - rather than the row asked for.
     * Asked for a taken row, [rows] used to settle it by start time, so a sound
     * dropped just before one already on that row took the row and pushed the
     * other one off it: the finger moved two things. And what the strip drew
     * under the finger was the row asked for, not the row it went to.
     *
     * The rows are numbered as they are shown now, and one past the last is a
     * new row; so the moved item's entry is also where the strip draws it while
     * it is carried (see [placedRow]).
     */
    fun preferencesAfterMove(items: List<LaneItem>, movedId: String, targetRow: Int, startMs: Long): Map<String, Int> {
        val shown = rows(items)
        val moved = items.firstOrNull { it.id == movedId } ?: return shown
        val start = startMs.coerceAtLeast(0L)
        val end = start + maxOf(moved.endMs - moved.startMs, 1L)
        val others = items.filter { it.id != movedId }
        fun free(row: Int) = others.none {
            shown[it.id] == row && it.startMs < end && start < maxOf(it.endMs, it.startMs + 1)
        }
        // One new row below the rest at most: a drop far below is a drop below.
        val last = rowCount(shown)
        val wanted = targetRow.coerceIn(0, last)
        val row = if (free(wanted)) wanted
        else (1..last + 1).asSequence()
            .flatMap { d -> sequenceOf(wanted + d, wanted - d) }
            .first { it in 0..last && free(it) }
        return shown + (movedId to row)
    }

    /** The row, numbered as the rows are shown now, that [movedId] lands on; see [preferencesAfterMove]. */
    fun placedRow(items: List<LaneItem>, movedId: String, targetRow: Int, startMs: Long): Int =
        preferencesAfterMove(items, movedId, targetRow, startMs)[movedId] ?: targetRow

    /**
     * What the playhead may snap to while the strip is dragged: the start and end
     * of the edit, every marker and beat, and both ends of every clip and effect.
     * Not the playhead itself, which is the thing being moved.
     */
    fun scrubTargets(state: TimelineState, markers: List<Long>): List<Long> {
        val out = HashSet<Long>()
        out.add(0L)
        out.add(state.durationMs)
        out.addAll(markers)
        state.clips.forEach { out.add(it.timelineStartMs); out.add(it.timelineEndMs) }
        state.effects.forEach { out.add(it.startMs); out.add(it.endMs) }
        return out.toList()
    }

    /**
     * Off the main track, the clips carried along when [before] is retimed: the
     * ones butted after it, within [touchingMs], and the ones butted after those,
     * each moved by as much as its end moved - so butt cuts stay butt cuts and a
     * gap placed by hand stays where it was.
     *
     * Sounds by kind alone, whichever rows they are drawn on: a sound's row is
     * only where the strip draws it (see [TimelineState.withClipPlaced]), and a
     * follower moved to another row to get it out of the way is still what plays
     * next. Overlays by their layer as well, which is what they are stacked by.
     * Only what starts at an end in the chain, never what starts inside the
     * retimed clip: a sound effect laid over the middle of a song was carried to
     * the song's new end.
     */
    fun rippleAfterRetime(clips: List<Clip>, before: Clip, touchingMs: Long): List<Clip> {
        val retimed = clips.firstOrNull { it.id == before.id } ?: return clips
        val shift = retimed.timelineEndMs - before.timelineEndMs
        if (shift == 0L || before.isMain) return clips
        val lane = clips.filter {
            it.id != before.id && it.kind == before.kind && (it.kind != ClipKind.Video || it.layer == before.layer)
        }
        val moving = LinkedHashSet<String>()
        val ends = ArrayDeque<Long>().apply { add(before.timelineEndMs) }
        while (ends.isNotEmpty()) {
            val end = ends.removeFirst()
            lane.forEach {
                if (it.id !in moving && abs(it.timelineStartMs - end) <= touchingMs) {
                    moving += it.id
                    ends += it.timelineEndMs
                }
            }
        }
        val rippled = if (moving.isEmpty()) clips else clips.map {
            if (it.id in moving) it.copy(timelineStartMs = (it.timelineStartMs + shift).coerceAtLeast(0L)) else it
        }
        return if (before.kind == ClipKind.Video && shift > 0L) overlaysClearedAfter(rippled, retimed) else rippled
    }

    /**
     * An overlay layer made whole again after [grown] got longer: whatever on
     * its layer, from its start on, now runs into what is before it is pushed
     * along just far enough to clear it, in order - only as much as the room
     * that was there did not absorb. Two overlays on one layer are the state
     * every other path refuses (the preview has one player per layer and
     * shows one of them, the file draws both); a slow-down rippled only the
     * butted followers, straight over the next overlay a little way on, and a
     * slow-down with nothing butted ran the clip itself into it.
     */
    fun overlaysClearedAfter(clips: List<Clip>, grown: Clip): List<Clip> {
        val now = clips.firstOrNull { it.id == grown.id } ?: return clips
        val row = clips.filter {
            it.kind == ClipKind.Video && it.isOverlay && it.layer == now.layer &&
                (it.id == now.id || it.timelineStartMs >= now.timelineStartMs)
        }.sortedWith(compareBy<Clip>({ it.timelineStartMs }, { if (it.id == now.id) 0 else 1 }))
        val pushed = HashMap<String, Long>()
        var cursor = Long.MIN_VALUE
        row.forEach { clip ->
            val start = maxOf(clip.timelineStartMs, cursor)
            if (start != clip.timelineStartMs) pushed[clip.id] = start
            cursor = start + clip.durationMs
        }
        if (pushed.isEmpty()) return clips
        return clips.map { c -> pushed[c.id]?.let { c.copy(timelineStartMs = it) } ?: c }
    }

    /** The nearest of [targets] no further than [withinMs] from [ms], or null. */
    fun nearest(ms: Long, targets: Collection<Long>, withinMs: Long): Long? {
        var best: Long? = null
        var bestGap = Long.MAX_VALUE
        for (t in targets) {
            val gap = abs(t - ms)
            if (gap <= withinMs && gap < bestGap) {
                best = t
                bestGap = gap
            }
        }
        return best
    }

    /**
     * A span [lengthMs] long that the finger would start at [startMs], with
     * whichever of its two ends is nearer a target pulled onto it. Never before
     * zero: an end that could only snap by pushing the start below it does not.
     */
    fun snapSpan(startMs: Long, lengthMs: Long, targets: Collection<Long>, withinMs: Long): Snap {
        val start = startMs.coerceAtLeast(0L)
        val head = nearest(start, targets, withinMs)
        val tail = nearest(start + lengthMs, targets, withinMs)?.takeIf { it - lengthMs >= 0L }
        val headGap = head?.let { abs(it - start) } ?: Long.MAX_VALUE
        val tailGap = tail?.let { abs(it - (start + lengthMs)) } ?: Long.MAX_VALUE
        return when {
            head == null && tail == null -> Snap(start, null)
            headGap <= tailGap -> Snap(head!!, head)
            else -> Snap(tail!! - lengthMs, tail)
        }
    }

    /**
     * What a dragged edge can snap to: the start of the edit, the playhead, every
     * marker and beat, and both ends of every clip and effect - except [exclude],
     * which is the thing being dragged and anything that moves with it.
     */
    fun snapTargets(state: TimelineState, markers: List<Long>, exclude: Set<String>): List<Long> {
        val out = HashSet<Long>()
        out.add(0L)
        out.add(state.playheadMs)
        out.addAll(markers)
        state.clips.forEach { if (it.id !in exclude) { out.add(it.timelineStartMs); out.add(it.timelineEndMs) } }
        state.effects.forEach { if (it.id !in exclude) { out.add(it.startMs); out.add(it.endMs) } }
        return out.toList()
    }

    /**
     * What a trim of a main-track clip's tail moves: the clip, and every shot
     * after it, which the magnetic track pulls along. None of those can be a
     * snap target - a target that moves with the edge is always under it.
     */
    fun movingWithTail(state: TimelineState, clipId: String): Set<String> {
        val clip = state.clips.firstOrNull { it.id == clipId } ?: return setOf(clipId)
        if (!clip.isMain) return setOf(clipId)
        return state.baseVideoClips.filter { it.timelineStartMs >= clip.timelineStartMs }.map { it.id }.toSet() + clipId
    }

    /**
     * How far into the file [playedMs] of this clip lands - past either end too,
     * at the rate the curve holds there, which is the rate the model reveals
     * footage at (see `withClipTrimmed`). Negative before the clip's head.
     */
    fun sourceOffsetBeyond(clip: Clip, playedMs: Long): Long {
        val ramp = clip.speedRamp
        val span = clip.sourceSpanMs
        return when {
            playedMs <= 0L -> (playedMs * ramp.speedAt(0L).toDouble()).roundToLong()
            playedMs >= clip.durationMs ->
                span + ((playedMs - clip.durationMs) * ramp.speedAt(span).toDouble()).roundToLong()
            else -> ramp.sourceOffsetAt(playedMs, span)
        }
    }

    /**
     * The trim - (source in delta, source out delta), what `withClipTrimmed`
     * takes - that puts one edge of [clip] at [edgeMs] on the timeline.
     *
     * The handles used to send the finger's travel as the trim, in source time,
     * so on a shot at 2x the edge went half as far as the finger and a snap to a
     * beat could never land on it. Asked from the clip as it is now, so a stream
     * of these converges on the finger rather than adding up.
     *
     * Not for the head of a main-track clip, whose edge does not move: see
     * [anchoredHeadIn].
     */
    fun edgeTrim(clip: Clip, head: Boolean, edgeMs: Long): Pair<Long, Long> {
        val offset = sourceOffsetBeyond(clip, edgeMs - clip.timelineStartMs)
        return if (head) offset to 0L else 0L to (offset - clip.sourceSpanMs)
    }

    /**
     * Where the file should start after the head handle of a main-track clip has
     * travelled [travelMs] from where it was at [anchor], the clip as it was when
     * the finger went down.
     *
     * The magnetic track keeps the clip's start butted to the shot before it, so
     * that edge never moves; the footage moves under it instead, by as much played
     * time as the finger went. Measured from the anchor, because the clip as it is
     * now has already been trimmed by the earlier part of the same drag.
     */
    fun anchoredHeadIn(anchor: Clip, travelMs: Long): Long =
        anchor.sourceInMs + sourceOffsetBeyond(anchor, travelMs)

    /**
     * How much played time of unused footage lies past one edge of [clip] - what
     * the grey stretch beside a handle shows while it is dragged. Zero where the
     * file's length is not known, and for words, which have no file.
     */
    fun unusedBeyond(clip: Clip, head: Boolean): Long {
        if (clip.kind == ClipKind.Text) return 0L
        val ramp = clip.speedRamp
        return if (head) {
            (clip.sourceInMs / ramp.speedAt(0L).toDouble()).toLong().coerceAtLeast(0L)
        } else {
            if (clip.sourceDurationMs <= clip.sourceOutMs) 0L
            else ((clip.sourceDurationMs - clip.sourceOutMs) / ramp.speedAt(clip.sourceSpanMs).toDouble())
                .toLong().coerceAtLeast(0L)
        }
    }

    /**
     * Where a main-track clip lifted with the finger at [fingerMs] would land.
     * Counted against the other shots' middles, as the model's own drag is, and
     * by the finger rather than the lifted clip's middle - a long shot's middle
     * can be screens away from where the finger is pointing.
     */
    fun reorderSlot(main: List<Clip>, liftedId: String, fingerMs: Long): ReorderSlot {
        val others = main.filterNot { it.id == liftedId }.sortedBy { it.timelineStartMs }
        val index = others.count { it.timelineStartMs + it.durationMs / 2 < fingerMs }
        val at = when {
            others.isEmpty() -> 0L
            index == 0 -> others.first().timelineStartMs
            else -> others[index - 1].timelineEndMs
        }
        return ReorderSlot(index, at)
    }

    /** Where [clipId] sits in the main track's running order now, or -1. */
    fun mainIndexOf(main: List<Clip>, clipId: String): Int =
        main.sortedBy { it.timelineStartMs }.indexOfFirst { it.id == clipId }

    /**
     * How far the view moves in one frame while a lifted clip is held at the edge
     * of the strip: nothing outside [zonePx] of either edge, rising to
     * [maxMsPerFrame] at the very edge. Negative to the left.
     */
    fun edgeScrollMs(fingerPx: Float, viewportPx: Int, zonePx: Float, maxMsPerFrame: Double): Double {
        if (viewportPx <= 0 || zonePx <= 0f) return 0.0
        val left = zonePx - fingerPx
        val right = fingerPx - (viewportPx - zonePx)
        return when {
            left > 0f -> -maxMsPerFrame * (left / zonePx).coerceAtMost(1f)
            right > 0f -> maxMsPerFrame * (right / zonePx).coerceAtMost(1f)
            else -> 0.0
        }
    }

    /**
     * The zoom that shows the whole edit whatever the playhead: half the strip's
     * width, because the playhead is fixed in the middle and the edit can reach
     * from it to either edge.
     */
    /**
     * The most footage a fit will try to show, in seconds.
     *
     * A fit of the *whole* edit is only a good opening view while the edit is
     * short. A minute and a half of video fitted into a phone's strip is about
     * three pixels a second of zoom, and under a playhead that does not move
     * the strip then creeps eight pixels a second - which is not slow, it is
     * invisible. Opening a video and watching it play looked like nothing was
     * happening at all, and that is how it was reported: "the play head is not
     * moving".
     *
     * Beyond this the fit stops and the edit simply runs off the right-hand
     * edge, which is what every editor does and what a double tap on the ruler
     * undoes. Thirty seconds across the strip is about twenty-three pixels a
     * second on this phone: unmistakably moving, and still enough of the edit
     * in view to see where you are.
     */
    const val MAX_FIT_SECONDS = 30f

    /**
     * The zoom that lays the whole edit out from the playhead line to the
     * strip's right edge - or [MAX_FIT_SECONDS] of it, whichever is less.
     *
     * What is usable is everything *after* the line, because a fit is asked for
     * with the playhead at 0:00 and the edit runs to the right of it. That used
     * to be half the strip, which is where the line used to be; it is three
     * quarters now, so an opened video is drawn half as big again
     * (TimelineWindow.PLAYHEAD_FRACTION says why the line moved). The 12 dp is
     * so the last frame is not flush against the edge.
     */
    fun fitZoom(durationMs: Long, viewportDp: Float): Float {
        val seconds = (durationMs / 1000f).coerceAtLeast(0.001f)
        val usable = (viewportDp * (1f - TimelineWindow.PLAYHEAD_FRACTION) - 12f).coerceAtLeast(1f)
        return maxOf(usable / seconds, usable / MAX_FIT_SECONDS).coerceIn(ZOOM_MIN, ZOOM_MAX)
    }
}
