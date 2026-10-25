package com.squish.app.editor

import com.squish.app.timeline.TransitionType
import kotlin.math.roundToLong

/**
 * A finished edit waiting for its shots (EDGE_PLAN H1, CapCut's biggest draw):
 * a Squish original, cuts on its beats, the joins between them, and one of the
 * [Template]s for the look, the title and the effects. The person's own shots
 * fill the slots in order, each slid to its liveliest stretch, and everything
 * after is the ordinary edit - every cut, join and look can be changed on its own.
 *
 * Twenty, made of pieces that already exist: the originals' tempos are known
 * exactly (MusicSynth.beatMap), so a slot counted in beats lands on the beat
 * with nothing to detect. [base] names a [Template] by its enum name rather than
 * holding one, so this table and its arithmetic can be executed without the
 * editor's state (SlotTemplateChecks reads the names against Template.kt).
 */
enum class SlotTemplate(
    val label: String,
    val blurb: String,
    val base: String,
    /** A Squish original, by MusicSynth id. */
    val songId: String,
    /** Each slot's length in beats, in order. */
    val slots: List<Int>,
    /** The joins, used in turn: a cut where the list says None. */
    val joins: List<TransitionType>
) {
    QuickCuts("Quick cuts", "Eight fast cuts, a punch on each", "Reel", "party-bounce",
        listOf(4, 2, 2, 4, 2, 2, 4, 8), listOf(TransitionType.None, TransitionType.Flash, TransitionType.None, TransitionType.Whip)),
    Weekend("Weekend", "Easy vlog pace, slides between", "Vlog", "weekend",
        listOf(8, 8, 4, 4, 8, 8), listOf(TransitionType.SlideLeft, TransitionType.CrossFade)),
    GoldenHour("Golden hour", "Long warm shots, soft dissolves", "Sunset", "lofi-sunset",
        listOf(8, 8, 8, 8), listOf(TransitionType.CrossFade)),
    Trailer("Trailer", "Slow build, hard cuts, a flash to black", "Trailer", "epic-rise",
        listOf(8, 8, 4, 4, 2, 2, 2, 2, 8), listOf(TransitionType.DipToBlack, TransitionType.None, TransitionType.None, TransitionType.Flash)),
    NightDrive("Night drive", "Neon, steady cuts, a glow on the turn", "Night", "night-drive",
        listOf(4, 4, 4, 4, 8, 8), listOf(TransitionType.None, TransitionType.Glow)),
    Workout("Workout", "Punchy cuts on every bar", "Fitness", "trap-energy",
        listOf(4, 4, 4, 4, 4, 4, 4, 4), listOf(TransitionType.None, TransitionType.ZoomIn)),
    Travel("Travel", "A day out, pushed from place to place", "Travel", "travel-vlog",
        listOf(8, 4, 4, 8, 4, 4, 8), listOf(TransitionType.Push, TransitionType.SlideLeft, TransitionType.CrossFade)),
    Birthday("Birthday", "Bright, bouncing, a flash on the wish", "Birthday", "kids-party",
        listOf(4, 4, 4, 4, 8, 8), listOf(TransitionType.Flash, TransitionType.ZoomIn)),
    LoveStory("Love story", "Slow and warm, white dips", "Love", "romantic-piano",
        listOf(8, 8, 8, 8), listOf(TransitionType.DipToWhite, TransitionType.CrossFade)),
    Memories("Memories", "Black and white, long dissolves", "Memories", "memories",
        listOf(8, 8, 8, 8), listOf(TransitionType.CrossFade)),
    Retro("Retro", "Tape look, jittery cuts", "Vhs", "retro-arcade",
        listOf(4, 4, 4, 4, 4, 4, 8), listOf(TransitionType.Jitter, TransitionType.None)),
    Party("Party", "Neon flashes on every second cut", "Party", "festival",
        listOf(2, 2, 4, 2, 2, 4, 4, 4, 8), listOf(TransitionType.None, TransitionType.Flash)),
    Food("Food", "Rich colour, slow pushes between dishes", "Food", "jazz-cafe",
        listOf(8, 8, 8, 8), listOf(TransitionType.SlideUp, TransitionType.CrossFade)),
    Product("Product", "Clean reveals, a price at the end", "Product", "tech-launch",
        listOf(4, 4, 4, 4, 8), listOf(TransitionType.WipeRight, TransitionType.None)),
    Cinematic("Cinematic", "Wide, slow, dissolves only", "Cinematic", "adventure",
        listOf(8, 8, 8, 8, 8), listOf(TransitionType.CrossFade)),
    Thriller("Thriller", "Cold cuts, a defocus on the turn", "Thriller", "dark-tension",
        listOf(8, 4, 4, 8, 8), listOf(TransitionType.None, TransitionType.Defocus)),
    City("City walk", "Street pace, whips between corners", "City", "city-walk",
        listOf(4, 4, 8, 4, 4, 8), listOf(TransitionType.Whip, TransitionType.None)),
    Dance("Dance", "On every beat for a run, then hold", "Trippy", "chill-house",
        listOf(1, 1, 1, 1, 2, 2, 4, 4, 8), listOf(TransitionType.None)),
    Pets("Pets", "Playful pops between", "Pets", "silly-march",
        listOf(4, 4, 4, 4, 8), listOf(TransitionType.PopIn, TransitionType.None)),
    Inspire("Inspire", "Uplifting, a glow into the last shot", "Documentary", "inspiring",
        listOf(8, 8, 4, 4, 8), listOf(TransitionType.CrossFade, TransitionType.None, TransitionType.Glow));

    /** The join into slot [index] (1 and on): the list in turn. */
    fun joinInto(index: Int): TransitionType = if (index <= 0 || joins.isEmpty()) TransitionType.None else joins[(index - 1) % joins.size]
}

/** The slot arithmetic, apart from the editor (SlotTemplateChecks). */
object SlotRules {

    /** One slot filled: which of the person's shots, how many beats it gets, and where it ends on the song. */
    data class Fill(val shot: Int, val beats: Int, val endMs: Long)

    /** A join's length: half a beat, at most [MAX_JOIN_MS]. */
    const val MAX_JOIN_MS = 500L

    fun joinMs(beatMs: Double): Long = minOf(MAX_JOIN_MS, (beatMs / 2).roundToLong())

    /**
     * The slots filled with [shotSpansMs] - each shot's footage, null for a photo
     * (any length) - in order, the shots going round again when there are more
     * slots than shots. A slot longer than its shot's footage takes as many whole
     * beats as the footage holds, never less than one, so every cut still lands on
     * a beat; and the ends are counted on the song's own grid from the start, so
     * a hundred slots do not drift by their rounding.
     */
    fun fill(slots: List<Int>, beatMs: Double, shotSpansMs: List<Long?>): List<Fill> {
        if (slots.isEmpty() || shotSpansMs.isEmpty() || beatMs <= 0.0) return emptyList()
        var beat = 0
        return slots.mapIndexed { i, want ->
            val shot = i % shotSpansMs.size
            val span = shotSpansMs[shot]
            val room = if (span == null) want else ((span - joinMs(beatMs)) / beatMs).toInt().coerceAtLeast(1)
            val beats = minOf(want, room).coerceAtLeast(1)
            beat += beats
            Fill(shot, beats, (beat * beatMs).roundToLong())
        }
    }

    /**
     * How long shot [index] of a fill is laid in for, before it is trimmed to its
     * beat: from the last slot's end to its own, plus the join into it (which the
     * track overlaps) and a little more, so the beat trim only ever shortens.
     */
    fun laidLengthMs(fills: List<Fill>, index: Int, beatMs: Double, joinIn: Boolean): Long {
        val from = if (index == 0) 0L else fills[index - 1].endMs
        val join = if (joinIn && index > 0) joinMs(beatMs) else 0L
        return fills[index].endMs - from + join + SLACK_MS
    }

    /** More than the overlap a join takes, less than a beat at any tempo here (60-150 bpm). */
    const val SLACK_MS = 40L
}
