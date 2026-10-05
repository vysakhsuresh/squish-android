import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.CutSoundFit
import com.squish.app.timeline.CutSoundVariant
import com.squish.app.timeline.CutSounds
import com.squish.app.timeline.TimelineState
import kotlin.system.exitProcess

// A sound on every cut. The two things that make it read as design rather than
// as a mistake are that the sounds alternate and that two never land on top of
// each other, and both are arithmetic.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private val VARIANTS = listOf(
    CutSoundVariant("sfx-reverse", 1_000L),
    CutSoundVariant("sfx-swish", 1_000L),
    CutSoundVariant("sfx-cloth", 1_000L)
)
private val SHORT = VARIANTS.map { it.copy(lengthMs = 500L) }
private val TINY = VARIANTS.map { it.copy(lengthMs = 300L) }

private fun shot(id: String, ms: Long, start: Long) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = 0, sourceOutMs = ms,
    timelineStartMs = start, sourceDurationMs = ms
)

fun main() {
    // --- One sound per join, in order, alternating. -------------------------
    run {
        val joins = listOf(2_000L, 5_000L, 9_000L, 14_000L)
        val plan = CutSounds.plan(joins, VARIANTS, CutSoundFit.LeadsIn)
        check(plan.size == 4, "four joins gave ${plan.size} sounds")
        check(plan.map { it.atMs } == listOf(1_000L, 4_000L, 8_000L, 13_000L), "a lead-in does not end on its join: ${plan.map { it.atMs }}")
        check(
            plan.map { it.effectId } == listOf("sfx-reverse", "sfx-swish", "sfx-cloth", "sfx-reverse"),
            "the sounds did not go round in turn: ${plan.map { it.effectId }}"
        )
        check(plan.map { it.atMs } == plan.map { it.atMs }.sorted(), "the sounds came out unsorted")
    }

    // --- Landing on the cut starts there instead. ---------------------------
    run {
        val plan = CutSounds.plan(listOf(2_000L, 5_000L), VARIANTS, CutSoundFit.LandsOn)
        check(plan.map { it.atMs } == listOf(2_000L, 5_000L), "a landing sound does not start on its join: ${plan.map { it.atMs }}")
    }

    // --- Joins too close together: the first keeps its sound. ---------------
    run {
        val joins = listOf(2_000L, 2_100L, 2_200L, 6_000L)
        val plan = CutSounds.plan(joins, SHORT, CutSoundFit.LandsOn)
        check(plan.size == 2, "a run of close joins gave ${plan.size} sounds, want 2")
        check(plan.map { it.atMs } == listOf(2_000L, 6_000L), "the wrong one of a close pair kept its sound: ${plan.map { it.atMs }}")
        // Never two in the same place, whatever is thrown at it.
        for (gap in 1..400 step 7) {
            val many = (1..20).map { it * gap.toLong() + 1_000L }
            val p = CutSounds.plan(many, TINY, CutSoundFit.LandsOn)
            p.zipWithNext().forEach { (a, b) ->
                check(b.atMs - a.atMs >= CutSounds.MIN_GAP_MS - 1, "two sounds ${b.atMs - a.atMs} ms apart at gap $gap")
            }
        }
    }

    // --- A lead-in never runs back over the join before it. ------------------
    //
    // The gap rule is about the joins; this is about the sounds. A reverse
    // whoosh is over a second long, so on joins a second apart it used to start
    // before the previous join and lie across the whole shot before it, with
    // the next one on top of it. Each join takes the longest variant that fits.
    run {
        val mixed = listOf(
            CutSoundVariant("long", 1_200L),
            CutSoundVariant("mid", 600L),
            CutSoundVariant("short", 300L)
        )
        for (gap in 350..3_000 step 37) {
            val joins = (1..12).map { 2_000L + it * gap.toLong() }
            val plan = CutSounds.plan(joins, mixed, CutSoundFit.LeadsIn)
            plan.zipWithNext().forEach { (a, b) ->
                check(
                    b.atMs >= a.atMs + a.lengthMs,
                    "at ${gap}ms joins a sound starts at ${b.atMs} while the one before runs to ${a.atMs + a.lengthMs}"
                )
            }
            check(plan.none { it.atMs < 0L }, "a lead-in started before 0 at gap $gap")
            // And something is laid at every gap wide enough for the shortest.
            if (gap >= 350) check(plan.isNotEmpty(), "nothing at all was laid at gap $gap")
        }
        // The short one is picked where the long one will not fit.
        val tight = CutSounds.plan(listOf(2_000L, 2_500L, 3_000L), mixed, CutSoundFit.LeadsIn)
        check(tight.size == 3, "a tight run laid ${tight.size} sounds, want 3")
        check(tight.drop(1).all { it.lengthMs <= 500L }, "a sound too long for its room was laid: $tight")
    }

    // --- Nothing starts before the edit does. --------------------------------
    run {
        val plan = CutSounds.plan(listOf(400L, 5_000L), VARIANTS, CutSoundFit.LeadsIn)
        check(plan.none { it.atMs < 0L }, "a lead-in started before 0: ${plan.map { it.atMs }}")
        check(plan.size == 1 && plan[0].atMs == 4_000L, "the join with no room before it was not dropped: $plan")
    }

    // --- The start of the edit is not a cut. ---------------------------------
    run {
        val plan = CutSounds.plan(listOf(0L, 3_000L), SHORT, CutSoundFit.LandsOn)
        check(plan.size == 1 && plan[0].atMs == 3_000L, "0:00 was treated as a cut: $plan")
    }

    // --- The cap holds, and nothing is laid for nothing. ---------------------
    run {
        val many = (1..500).map { it * 1_000L }
        val plan = CutSounds.plan(many, TINY, CutSoundFit.LandsOn)
        check(plan.size == CutSounds.MAX_SOUNDS, "the cap gave ${plan.size}")
        check(CutSounds.plan(emptyList(), SHORT, CutSoundFit.LandsOn).isEmpty(), "no joins gave sounds")
        check(CutSounds.plan(listOf(1_000L), emptyList(), CutSoundFit.LandsOn).isEmpty(), "no variants gave sounds")
        check(CutSounds.plan(listOf(1_000L), VARIANTS.map { it.copy(lengthMs = 0L) }, CutSoundFit.LandsOn).isEmpty(), "a sound of no length was laid")
    }

    // --- The joins of a real track, gaps included. ---------------------------
    run {
        val state = TimelineState(
            clips = listOf(
                shot("a", 3_000, 0),
                shot("b", 4_000, 3_000),
                // A gap from 7 s to 9 s: the picture changes at both ends of it.
                shot("c", 5_000, 9_000)
            )
        )
        val joins = CutSounds.joinsOf(state)
        check(joins == listOf(3_000L, 7_000L, 9_000L), "the joins of a track with a gap came out $joins")
        check(joins.none { it == 0L }, "0:00 was called a join")
        check(joins.none { it == state.durationMs }, "the end of the edit was called a join")
    }
    run {
        val one = TimelineState(clips = listOf(shot("a", 5_000, 0)))
        check(CutSounds.joinsOf(one).isEmpty(), "one shot with no gap has a join: ${CutSounds.joinsOf(one)}")
    }

    println("cut sounds: one on every join, alternating, never on top of each other")
    if (problems.isEmpty()) println("PASS - a lead-in ends on its cut, a landing starts on it, and close joins keep the first")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
