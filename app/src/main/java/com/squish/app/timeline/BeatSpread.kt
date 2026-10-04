package com.squish.app.timeline

import kotlin.math.abs

/**
 * Spreading the shots across a song.
 *
 * "Fit shots to the beat" ([withShotsFittedToBeats]) shortens each shot to end
 * on the next beat, which tightens an edit that is already roughly the right
 * length. This is the other job, and the one people actually arrive with:
 * twenty clips and a thirty-second song, make it fit - every shot given its
 * share of the song, and every join pulled onto a beat.
 */
object BeatSpread {

    /**
     * Where the joins land when [count] shots share [lengthMs], with the
     * interior ones pulled to the nearest of [beats].
     *
     * The last join is the song's own end, not a beat: the edit ending with the
     * music is the point of the exercise, and a beat near the end would leave a
     * tail of silence or cut the last note off.
     *
     * Every shot is at least [minMs] long and the joins only ever go forwards,
     * so a crowded grid cannot produce a shot of nothing or a join that steps
     * back behind the one before it.
     */
    fun joins(count: Int, lengthMs: Long, beats: List<Long>, minMs: Long = MIN_CLIP_MS): List<Long> {
        if (count <= 0 || lengthMs <= 0L) return emptyList()
        val even = (1..count).map { lengthMs * it / count }
        // Not enough song to give everyone a shot worth having: an even split is
        // the honest answer, and snapping would only make some of them shorter.
        if (beats.isEmpty() || lengthMs < count * minMs) return even

        var last = 0L
        val out = ArrayList<Long>(count)
        even.forEachIndexed { i, want ->
            if (i == count - 1) {
                out += lengthMs
                return@forEachIndexed
            }
            // Room left for everyone after this one.
            val latest = lengthMs - (count - i - 1) * minMs
            val earliest = last + minMs
            val at = if (earliest > latest) latest
            else (beats.minByOrNull { abs(it - want) } ?: want).coerceIn(earliest, latest)
            out += at
            last = at
        }
        return out
    }
}

/**
 * The main track's shots spread across [lengthMs], each trimmed to its share and
 * the joins on the beat.
 *
 * A shot with less footage than its share keeps all it has - stretching it would
 * mean a speed change nobody asked for - so the track can come out shorter than
 * the song. Each shot keeps its head: the part people framed is the part they
 * kept, and trimming from the front would throw it away.
 */
fun TimelineState.withShotsSpreadOver(lengthMs: Long, beats: List<Long>): TimelineState {
    val shots = baseVideoClips
    if (shots.isEmpty() || lengthMs <= 0L) return this
    val ends = BeatSpread.joins(shots.size, lengthMs, beats)
    if (ends.size != shots.size) return this

    var from = 0L
    val wanted = HashMap<String, Long>(shots.size)
    ends.forEach { end ->
        val i = wanted.size
        wanted[shots[i].id] = (end - from).coerceAtLeast(MIN_CLIP_MS)
        from = end
    }
    val trimmed = clips.map { clip ->
        val want = wanted[clip.id] ?: return@map clip
        val played = clip.durationMs
        if (played <= 0L || want >= played) return@map clip
        // The share in the clip's own clock: a shot at 2x covers twice as much
        // footage in the same stretch of the edit. Taken at the clip's average
        // rate rather than by walking its curve - a ramp's exact inverse is not
        // worth a search here, and the join lands on the beat either way
        // because the trim is what moves, not the grid.
        val source = (clip.sourceSpanMs * want / played).coerceAtLeast(1L)
        val out = (clip.sourceInMs + source).coerceAtMost(clip.sourceDurationMs)
        if (out <= clip.sourceInMs) clip else clip.copy(sourceOutMs = out)
    }
    return copy(clips = trimmed).rippleVideo()
}
