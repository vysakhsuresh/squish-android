import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MIN_SPAN_MS
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.withSpanRemoved
import com.squish.app.timeline.withSpansRemoved
import kotlin.system.exitProcess

// Taking a stretch of time out of the edit, picture and sound together, and
// closing up behind it - what editing by transcript does when a run of words is
// deleted, and what "remove the filler words" does over and over.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun video(id: String, span: Long, start: Long = 0, layer: Int = 0) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000, layer = layer
)

private fun audio(id: String, span: Long, start: Long = 0) = Clip(
    id = id, kind = ClipKind.Audio, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000
)

private fun state(vararg clips: Clip) = clips.fold(TimelineState()) { s, c -> s.withClipAdded(c) }

private val TimelineState.mainLength: Long get() = baseVideoClips.maxOfOrNull { it.timelineEndMs } ?: 0L

fun main() {
    // --- One shot, a stretch out of the middle. -----------------------------
    run {
        val before = state(video("a", 10_000))
        val after = before.withSpanRemoved(3_000, 5_000)
        check(after.mainLength == 8_000L, "ten seconds less two came to ${after.mainLength}")
        check(after.baseVideoClips.size == 2, "the shot was not cut in two round the stretch")
        check(after.baseVideoClips[0].timelineStartMs == 0L, "the track does not start at 0")
        check(after.baseVideoClips[1].timelineStartMs == 3_000L, "the second half did not close up")
        // The frames either side are the frames that were either side.
        check(after.baseVideoClips[0].sourceOutMs == 3_000L, "the first half kept the wrong footage")
        check(after.baseVideoClips[1].sourceInMs == 5_000L, "the second half kept the wrong footage")
        check(after.playheadMs == 3_000L, "the playhead did not land where the stretch was")
    }

    // --- Nothing is taken out for nothing. ----------------------------------
    run {
        val before = state(video("a", 10_000))
        check(before.withSpanRemoved(3_000, 3_000).mainLength == 10_000L, "an empty stretch changed the edit")
        check(before.withSpanRemoved(3_000, 3_005).mainLength == 10_000L, "a stretch under a frame changed the edit")
        // Given back to front it is the same stretch.
        check(before.withSpanRemoved(5_000, 3_000).mainLength == 8_000L, "a stretch given backwards did nothing")
    }

    // --- The sound under it keeps step. -------------------------------------
    run {
        val before = state(video("a", 10_000), audio("song", 10_000))
        val after = before.withSpanRemoved(2_000, 4_000)
        val sound = after.clips.filter { it.kind == ClipKind.Audio }.sortedBy { it.timelineStartMs }
        check(sound.size == 2, "the song was not cut round the stretch: ${sound.size} clips")
        check(sound[0].timelineEndMs == 2_000L, "the song's first half ends at ${sound[0].timelineEndMs}")
        check(sound[1].timelineStartMs == 2_000L, "the song's second half did not close up")
        check(sound.last().timelineEndMs == 8_000L, "the song did not come back by the stretch's length")
    }

    // --- A whole clip inside the stretch goes. ------------------------------
    run {
        val before = state(video("a", 4_000), video("b", 4_000, start = 4_000), video("c", 4_000, start = 8_000))
        val after = before.withSpanRemoved(4_000, 8_000)
        check(after.baseVideoClips.map { it.id } == listOf("a", "c"), "the middle shot did not go: ${after.baseVideoClips.map { it.id }}")
        check(after.mainLength == 8_000L, "the track did not close up to 8 s: ${after.mainLength}")
    }

    // --- A stretch across a join cuts both shots. ---------------------------
    run {
        val before = state(video("a", 4_000), video("b", 4_000, start = 4_000))
        val after = before.withSpanRemoved(3_000, 5_000)
        check(after.mainLength == 6_000L, "the track is ${after.mainLength}, not 6 s")
        check(after.baseVideoClips.size == 2, "a stretch across a join left ${after.baseVideoClips.size} shots")
        check(after.baseVideoClips[0].timelineEndMs == 3_000L, "the first shot kept too much")
        check(after.baseVideoClips[1].sourceInMs == 1_000L, "the second shot kept the wrong footage")
    }

    // --- An overlay inside goes; one after comes back. ----------------------
    run {
        val before = state(
            video("a", 10_000),
            video("pip", 1_000, start = 3_000, layer = 1),
            video("late", 1_000, start = 8_000, layer = 1)
        )
        val after = before.withSpanRemoved(2_000, 5_000)
        val overlays = after.clips.filter { it.layer > 0 }
        check(overlays.none { it.id == "pip" }, "an overlay inside the stretch survived")
        val late = overlays.firstOrNull { it.id == "late" }
        check(late != null && late.timelineStartMs == 5_000L, "the overlay after the stretch is at ${late?.timelineStartMs}, not 5 s")
    }

    // --- Several stretches, which is what the filler-word pass does. --------
    run {
        val before = state(video("a", 20_000))
        // Taken in the order found, the second stretch's times would already be
        // wrong by the length of the first - so they are taken back to front.
        val after = before.withSpansRemoved(listOf(2_000L..3_000L, 7_000L..8_000L, 15_000L..16_000L))
        check(after.mainLength == 17_000L, "three seconds out of twenty came to ${after.mainLength}")
        // The footage that survives is the footage that should: the four runs
        // 0-2, 3-7, 8-15, 16-20 butted together.
        val ins = after.baseVideoClips.map { it.sourceInMs to it.sourceOutMs }
        check(ins == listOf(0L to 2_000L, 3_000L to 7_000L, 8_000L to 15_000L, 16_000L to 20_000L),
            "the surviving footage is $ins")
    }

    // --- Stretches that touch or overlap are one stretch. -------------------
    run {
        val before = state(video("a", 10_000))
        val joined = before.withSpansRemoved(listOf(2_000L..4_000L, 3_000L..5_000L))
        check(joined.mainLength == 7_000L, "two overlapping stretches took ${10_000 - joined.mainLength} ms, not 3000")
        val butted = before.withSpansRemoved(listOf(2_000L..4_000L, 4_000L..6_000L))
        check(butted.mainLength == 6_000L, "two butted stretches took ${10_000 - butted.mainLength} ms, not 4000")
        check(before.withSpansRemoved(emptyList()).mainLength == 10_000L, "no stretches changed the edit")
    }

    // --- A stretch running off the end takes what is there. -----------------
    run {
        val before = state(video("a", 10_000))
        val after = before.withSpanRemoved(8_000, 30_000)
        check(after.mainLength == 8_000L, "a stretch past the end left ${after.mainLength}")
        val all = before.withSpanRemoved(0, 30_000)
        check(all.baseVideoClips.isEmpty(), "taking everything out left ${all.baseVideoClips.size} shots")
    }

    // --- When it refuses, it changes nothing at all. ------------------------
    //
    // Two cases, and TextEdits.removeSpans has to tell them from a removal: it
    // moves the lines of words by the stretch's length itself, and used to do
    // that whether or not the footage moved - so a word over a gap, or a line
    // an old draft left past the end, slid every caption after it off its
    // footage for good.
    run {
        val before = state(video("a", 10_000))
        // Shorter than a frame.
        check(before.withSpanRemoved(3_000, 3_000 + MIN_SPAN_MS - 1) == before, "a stretch under a frame changed the edit")
        check(before.withSpanRemoved(3_000, 3_000) == before, "an empty stretch changed the edit")
        // Past the end of everything: nothing is in it.
        check(before.withSpanRemoved(20_000, 25_000) == before, "a stretch past the end changed the edit")
        // And in a gap: a clip at 0..4s and another at 8..12s, the stretch between.
        // Built straight, not through withClipAdded, which would butt the second shot up.
        val gapped = TimelineState(clips = listOf(video("a", 4_000), video("b", 4_000, start = 8_000)))
        check(gapped.withSpanRemoved(5_000, 7_000) == gapped, "a stretch inside a gap changed the edit")
        // While a stretch with something in it does change it, so the test the
        // caller makes is a test of something.
        check(gapped.withSpanRemoved(1_000, 3_000) != gapped, "a stretch with a shot in it changed nothing")
    }

    println("span removal: picture, sound and overlays, singly and in runs")
    if (problems.isEmpty()) println("PASS - a stretch comes out and the edit closes up behind it")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
