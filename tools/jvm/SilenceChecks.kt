import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SilenceRules
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withSilencesRemoved
import com.squish.app.timeline.withClipTrimmed
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // A 20 s take: talking at 1-4 s, 4.3-6 s (a breath between), 10-12 s, and silence to the end.
    val speech = listOf(1_000L..4_000L, 4_300L..6_000L, 10_000L..12_000L)
    val kept = SilenceRules.keptWindows(speech, 0L, 20_000L)
    check(kept == listOf(850L..6_150L, 9_850L..12_150L), "the windows kept were wrong: $kept")
    check(SilenceRules.removedMs(kept, 0L, 20_000L) == 20_000L - 5_300L - 2_300L, "the time removed was wrong")

    // No speech: nothing to go by, nothing kept (the shot is left alone).
    check(SilenceRules.keptWindows(emptyList(), 0L, 20_000L).isEmpty(), "no speech still gave windows")
    // A short lead-in is kept rather than cut to nothing.
    check(SilenceRules.keptWindows(listOf(400L..3_000L), 0L, 3_300L) == listOf(0L..3_300L), "a short head or tail was cut")

    // On the timeline: shot A (0-20 s of its file) then shot B.
    val a = Clip(id = "a", kind = ClipKind.Video, uri = null, label = "a", sourceInMs = 0, sourceOutMs = 20_000, timelineStartMs = 0, sourceDurationMs = 20_000)
    val b = Clip(id = "b", kind = ClipKind.Video, uri = null, label = "b", sourceInMs = 0, sourceOutMs = 5_000, timelineStartMs = 20_000, sourceDurationMs = 5_000)
    val cut = TimelineState(clips = listOf(a, b)).withSilencesRemoved("a", kept)
    val main = cut.clips.filter { it.isMain }.sortedBy { it.timelineStartMs }
    check(main.size == 3, "the shot was not cut into two pieces plus B: ${main.map { it.id }}")
    check(main[0].id == "a", "the first piece lost the shot's id")
    check(main[0].timelineStartMs == 0L && main[1].timelineStartMs == main[0].timelineEndMs, "the pieces were not back to back")
    check(main[2].id == "b" && main[2].timelineStartMs == main[1].timelineEndMs, "B did not close up behind the pieces")
    check(main[1].sourceInMs == 9_850L && main[1].sourceOutMs == 12_150L, "the second piece was the wrong footage")
    // Asking for the whole shot changes nothing.
    val same = TimelineState(clips = listOf(a, b))
    check(same.withSilencesRemoved("a", listOf(0L..20_000L)) == same, "keeping everything changed the edit")

    layeredChecks()
    rowChecks()
    tinyTrimChecks()
    if (problems.isEmpty()) {
        println("SilenceChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}

fun layeredChecks() {
    // A caption-like overlay and a sound over the talking at 10-12 s of the shot move back with it.
    val a = Clip(id = "a", kind = ClipKind.Video, uri = null, label = "a", sourceInMs = 0, sourceOutMs = 20_000, timelineStartMs = 0, sourceDurationMs = 20_000)
    val pip = Clip(id = "p", kind = ClipKind.Video, uri = null, label = "p", sourceInMs = 0, sourceOutMs = 2_000, timelineStartMs = 10_000, sourceDurationMs = 2_000, layer = 1)
    val song = Clip(id = "s", kind = ClipKind.Audio, uri = null, label = "s", sourceInMs = 0, sourceOutMs = 5_000, timelineStartMs = 10_000, sourceDurationMs = 5_000)
    val kept = listOf(850L..6_150L, 9_850L..12_150L)
    val cut = TimelineState(clips = listOf(a, pip, song)).withSilencesRemoved("a", kept)
    val piece = cut.clips.filter { it.isMain }.sortedBy { it.timelineStartMs }[1]
    // The word at 10 s of the file is now at: piece start + (10000 - 9850).
    val wordAt = piece.timelineStartMs + 150L
    check(cut.clips.first { it.id == "p" }.timelineStartMs == wordAt, "the overlay did not move with its word: ${cut.clips.first { it.id == "p" }.timelineStartMs} vs $wordAt")
    check(cut.clips.first { it.id == "s" }.timelineStartMs == wordAt, "the sound did not move with its word")
    check(com.squish.app.timeline.shiftedPast(5_000L, listOf(1_000L..3_000L)) == 3_000L, "a moment after a removed stretch did not move back by it")
    check(com.squish.app.timeline.shiftedPast(2_000L, listOf(1_000L..3_000L)) == 1_000L, "a moment inside a removed stretch did not land at its start")
}

fun rowChecks() {
    // Overlay A on row 1 from 0-10 s (starts before the shot, stays); B on row 1 from 10-12 s moves back into A.
    val shot = Clip(id = "m", kind = ClipKind.Video, uri = null, label = "m", sourceInMs = 0, sourceOutMs = 20_000, timelineStartMs = 0, sourceDurationMs = 20_000)
    val a = Clip(id = "A", kind = ClipKind.Video, uri = null, label = "A", sourceInMs = 0, sourceOutMs = 10_000, timelineStartMs = 0, sourceDurationMs = 10_000, layer = 1)
    val b = Clip(id = "B", kind = ClipKind.Video, uri = null, label = "B", sourceInMs = 0, sourceOutMs = 2_000, timelineStartMs = 10_000, sourceDurationMs = 2_000, layer = 1)
    val cut = TimelineState(clips = listOf(shot, a, b)).withSilencesRemoved("m", listOf(0L..2_000L, 5_000L..20_000L))
    val ov = cut.clips.filter { it.isOverlay }
    val clash = ov.any { x -> ov.any { y -> x.id != y.id && x.layer == y.layer && x.timelineStartMs < y.timelineEndMs && y.timelineStartMs < x.timelineEndMs } }
    check(!clash, "two overlays share a row at once: ${ov.map { "${it.id}@${it.layer} ${it.timelineStartMs}-${it.timelineEndMs}" }}")

    // Footage stops at MAX_FOOTAGE_LAYER: each row of it is a decoder in the
    // preview and in the export. The re-seating asked for the lowest free row
    // of all six, so a shot pushed off its own row by a removal could land on 5
    // or 6 - one more decoder than either side budgets for.
    run {
        val base = Clip(id = "m", kind = ClipKind.Video, uri = null, label = "m", sourceInMs = 0, sourceOutMs = 20_000, timelineStartMs = 0, sourceDurationMs = 20_000)
        // Rows 1..MAX_FOOTAGE_LAYER full across the stretch the moved clip lands in.
        val walls = (1..com.squish.app.timeline.MAX_FOOTAGE_LAYER).map { row ->
            Clip(id = "w$row", kind = ClipKind.Video, uri = null, label = "w", sourceInMs = 0, sourceOutMs = 20_000, timelineStartMs = 0, sourceDurationMs = 20_000, layer = row)
        }
        val mover = Clip(id = "x", kind = ClipKind.Video, uri = null, label = "x", sourceInMs = 0, sourceOutMs = 2_000, timelineStartMs = 16_000, sourceDurationMs = 2_000, layer = 1)
        val out = TimelineState(clips = listOf(base) + walls + mover).withSilencesRemoved("m", listOf(0L..2_000L, 15_000L..20_000L))
        val moved = out.clips.firstOrNull { it.id == "x" }
        check(moved != null && moved.layer <= com.squish.app.timeline.MAX_FOOTAGE_LAYER,
            "a shot was re-seated on row ${moved?.layer}, past the footage ceiling of ${com.squish.app.timeline.MAX_FOOTAGE_LAYER}")
    }

    // And a sound is not re-seated at all: its layer is a row *preference* and
    // the free-row test looks at video clips alone, so a sound tested against
    // the video rows could be rewritten onto one of their numbers and jump to
    // another sound row for no reason.
    run {
        val base = Clip(id = "m", kind = ClipKind.Video, uri = null, label = "m", sourceInMs = 0, sourceOutMs = 20_000, timelineStartMs = 0, sourceDurationMs = 20_000)
        val wall = Clip(id = "w", kind = ClipKind.Video, uri = null, label = "w", sourceInMs = 0, sourceOutMs = 20_000, timelineStartMs = 0, sourceDurationMs = 20_000, layer = 1)
        val sound = Clip(id = "s", kind = ClipKind.Audio, uri = null, label = "s", sourceInMs = 0, sourceOutMs = 2_000, timelineStartMs = 16_000, sourceDurationMs = 2_000, layer = 1)
        val out = TimelineState(clips = listOf(base, wall, sound)).withSilencesRemoved("m", listOf(0L..2_000L, 15_000L..20_000L))
        check(out.clips.first { it.id == "s" }.layer == 1, "a sound was moved to row ${out.clips.first { it.id == "s" }.layer} by the overlay re-seating")
    }
}

fun tinyTrimChecks() {
    val tiny = Clip(id = "t", kind = ClipKind.Audio, uri = null, label = "t", sourceInMs = 0, sourceOutMs = 120, timelineStartMs = 0, sourceDurationMs = 120)
    val s = TimelineState(clips = listOf(tiny))
    val trimmed = runCatching { s.withClipTrimmed("t", 50, -20) }
    check(trimmed.isSuccess && trimmed.getOrNull() == s, "trimming a 120 ms clip threw or changed it: ${trimmed.exceptionOrNull()}")
}
