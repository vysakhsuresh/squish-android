import com.squish.app.timeline.BeatSpread
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withShotsSpreadOver
import kotlin.system.exitProcess

// Twenty clips and a thirty-second song: every shot gets its share and every
// join lands on a beat. The other half of fitting to the beat.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun shot(id: String, ms: Long, start: Long = 0) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = 0, sourceOutMs = ms,
    timelineStartMs = start, sourceDurationMs = ms
)

private fun laid(vararg lengths: Long): TimelineState {
    var at = 0L
    return TimelineState(clips = lengths.mapIndexed { i, ms -> shot("c$i", ms, at).also { at += ms } })
}

fun main() {
    val beats = (1..120).map { it * 500L }

    // --- The shape of the answer, whatever is asked. ------------------------
    for (count in 1..12) for (length in listOf(3_000L, 10_000L, 30_000L, 97_321L)) {
        val j = BeatSpread.joins(count, length, beats)
        check(j.size == count, "$count shots over $length gave ${j.size} joins")
        check(j.last() == length, "$count over $length ends at ${j.last()}, not the song's end")
        check(j == j.sorted() && j.distinct() == j, "$count over $length stepped back or repeated")
        var last = 0L
        j.forEach { at ->
            check(at - last >= MIN_CLIP_MS || length < count * MIN_CLIP_MS, "$count over $length made a shot of ${at - last}")
            last = at
        }
    }

    // --- The interior joins really are on beats. ----------------------------
    run {
        val j = BeatSpread.joins(4, 30_000, beats)
        check(j.dropLast(1).all { it % 500L == 0L }, "an interior join missed the grid: $j")
        check(j.last() == 30_000L, "the last join is not the song's end")
        // Near the even split, not dragged far by the snap.
        j.dropLast(1).forEachIndexed { i, at ->
            val want = 30_000L * (i + 1) / 4
            check(Math.abs(at - want) <= 250L, "join $i moved from $want to $at")
        }
    }

    // --- No beats: an even split, exactly. ----------------------------------
    run {
        val j = BeatSpread.joins(3, 9_000, emptyList())
        check(j == listOf(3_000L, 6_000L, 9_000L), "no beats gave $j")
    }

    // --- A crowded grid cannot squeeze a shot out of existence. -------------
    run {
        val dense = (1..2000).map { it * 10L }
        val j = BeatSpread.joins(10, 2_000, dense)
        check(j.size == 10 && j.last() == 2_000L, "a crowded grid gave $j")
        check(j == j.sorted() && j.distinct() == j, "a crowded grid went backwards")
    }

    // --- One shot takes the whole song. -------------------------------------
    check(BeatSpread.joins(1, 12_345, beats) == listOf(12_345L), "one shot did not take the song")

    // --- Over a timeline. ---------------------------------------------------
    run {
        val before = laid(10_000, 10_000, 10_000)
        val after = before.withShotsSpreadOver(9_000, beats)
        val shots = after.baseVideoClips
        check(shots.size == 3, "a shot went missing")
        check(shots.last().timelineEndMs == 9_000L, "the track is ${shots.last().timelineEndMs}, not the song's 9 s")
        check(shots.zipWithNext().all { (a, b) -> b.timelineStartMs == a.timelineEndMs }, "the shots are not butted")
        check(shots.all { it.sourceInMs == 0L }, "a shot's head was thrown away")
    }

    // --- A shot with less footage than its share keeps what it has. ---------
    run {
        val before = laid(10_000, 1_000, 10_000)
        val after = before.withShotsSpreadOver(30_000, beats)
        val shots = after.baseVideoClips
        check(shots[1].durationMs == 1_000L, "a short shot was stretched to ${shots[1].durationMs}")
        check(shots.last().timelineEndMs < 30_000L, "the track filled the song with footage that is not there")
        check(shots.zipWithNext().all { (a, b) -> b.timelineStartMs == a.timelineEndMs }, "a gap was left where the short shot is")
    }

    // --- Nothing to do. -----------------------------------------------------
    run {
        val before = laid(5_000)
        check(before.withShotsSpreadOver(0, beats) == before, "a song of nothing changed the edit")
        check(TimelineState().withShotsSpreadOver(9_000, beats).baseVideoClips.isEmpty(), "an empty edit grew shots")
    }

    println("beat spread: joins over a song, on the grid")
    if (problems.isEmpty()) println("PASS - every shot gets its share and the edit ends with the music")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
