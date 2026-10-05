package com.squish.app.timeline

/*
 * A sound on every cut.
 *
 * A twenty-two second wedding reel cut in FinalCut was read off a screen
 * recording of its timeline: twenty-four shots, one song chopped into ten
 * pieces - and *ten sound effects*, one on nearly every join. A reverse whoosh
 * pulling into a cut, a glass shard on a whip pan, cloth on a turn, a sub under
 * an impact. That layer is most of what separates a reel that feels made from
 * one that feels assembled, and it is the only part of that edit Squish could
 * not do in a reasonable number of taps: find an effect, add it, drag it to the
 * join, do it nine more times.
 *
 * This is the rule for doing it in one. Nothing here touches Android, and
 * tools/jvm/CutSoundChecks.kt executes it.
 */

/** One sound to lay: which effect, and when it starts. */
data class CutSound(val effectId: String, val atMs: Long, val lengthMs: Long)

/**
 * One of the sounds a tap will go round in turn. Each carries its own length,
 * because a sound that leads in has to *end* on the cut and they are not all
 * the same length - a swish is a third of a second, a reverse whoosh over a
 * second.
 */
data class CutSoundVariant(val effectId: String, val lengthMs: Long)

/**
 * How a sound sits against the join it belongs to.
 *
 * A whoosh that *arrives* at the cut is the one that works - it pulls the eye
 * into the next shot. One that starts at the cut chases the shot that has
 * already gone.
 */
enum class CutSoundFit(val label: String) {
    /** Ends on the cut: the sound leads in. The default, and what a reverse whoosh is for. */
    LeadsIn("Leads into the cut"),

    /** Starts on the cut: an impact lands with the new shot. */
    LandsOn("Lands on the cut")
}

object CutSounds {

    /**
     * How close two joins have to be before the second gets nothing.
     *
     * Under a third of a second the two sounds overlap into mud, and a reel
     * cut on every beat of a fast song has joins that close. The first of a
     * pair keeps its sound.
     */
    const val MIN_GAP_MS = 320L

    /** The most sounds one tap will lay, so a hundred-cut edit does not become a hundred clips. */
    const val MAX_SOUNDS = 60

    /**
     * Which sounds to lay over [joins].
     *
     * [variants] are played round in turn rather than one sound repeated: ten
     * identical whooshes read as a mistake, three alternating read as design.
     * A sound that leads in starts its own length before its join and is
     * dropped when there is no room before 0, rather than being squashed or
     * started before the edit.
     *
     * The room a lead-in needs is its own length, not [minGapMs]. A reverse
     * whoosh is over a second long, so on joins a second apart it would start
     * before the previous join and run back over the whole shot before it -
     * two of them laid over one another is the mud this is supposed to avoid,
     * and the gap rule alone let it through. Each join takes the first variant
     * whose length fits in the room there is, starting from the one whose turn
     * it is, so a fast edit gets its short sounds rather than nothing.
     */
    fun plan(
        joins: List<Long>,
        variants: List<CutSoundVariant>,
        fit: CutSoundFit,
        minGapMs: Long = MIN_GAP_MS,
        limit: Int = MAX_SOUNDS
    ): List<CutSound> {
        val usable = variants.filter { it.lengthMs > 0L }
        if (joins.isEmpty() || usable.isEmpty()) return emptyList()
        val wanted = joins.filter { it > 0L }.distinct().sorted()
        val out = ArrayList<CutSound>()
        // Null rather than Long.MIN_VALUE for "nothing laid yet": the subtraction
        // below overflows against MIN_VALUE and comes back negative, so every
        // join read as too close and the whole thing laid nothing.
        var lastAt: Long? = null
        var i = 0
        wanted.forEach { join ->
            if (out.size >= limit) return@forEach
            val since = lastAt
            if (since != null && join - since < minGapMs) return@forEach
            // The earliest a sound may start: the join before it, so one never
            // runs back over the shot before, and never before the edit begins.
            val floor = maxOf(0L, since ?: 0L)
            val turn = (0 until usable.size).firstOrNull { offset ->
                val v = usable[(i + offset) % usable.size]
                (if (fit == CutSoundFit.LeadsIn) join - v.lengthMs else join) >= floor
            } ?: return@forEach
            val variant = usable[(i + turn) % usable.size]
            val at = if (fit == CutSoundFit.LeadsIn) join - variant.lengthMs else join
            out += CutSound(variant.effectId, at, variant.lengthMs)
            lastAt = join
            // Past the one taken, so the next join does not start on it again.
            i += turn + 1
        }
        return out
    }

    /**
     * The joins of the main video track: every place one shot becomes another,
     * and never the very start or the very end.
     *
     * Taken from where the shots sit rather than from the cut list, so a gap
     * on the main track counts as two joins and not one - the picture does
     * change at both ends of it.
     */
    fun joinsOf(state: TimelineState): List<Long> = joinsOf(state.baseVideoClips, state.durationMs)

    /** The same from the shots alone, which is what the editor has to hand. */
    fun joinsOf(shots: List<Clip>, durationMs: Long): List<Long> {
        if (shots.isEmpty()) return emptyList()
        return buildList {
            shots.forEach { clip ->
                if (clip.timelineStartMs > 0L) add(clip.timelineStartMs)
                if (clip.timelineEndMs < durationMs) add(clip.timelineEndMs)
            }
        }.distinct().sorted()
    }
}
