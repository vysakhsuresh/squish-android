import com.squish.app.editor.EditRules
import com.squish.app.editor.EffectKind
import com.squish.app.editor.Span
import com.squish.app.editor.TimedEffect
import com.squish.app.editor.sourceAtExtended
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedRamp
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun clip(ramp: SpeedRamp = SpeedRamp(), srcIn: Long = 0, srcOut: Long = 10_000, start: Long = 0) =
    Clip(
        kind = ClipKind.Video, label = "c",
        sourceInMs = srcIn, sourceOutMs = srcOut,
        timelineStartMs = start, sourceDurationMs = 60_000, speedRamp = ramp
    )

fun main() {
    val minMs = 200L

    // --- Dragging a caption: whole, and inside the picture (T6/X7). -----------
    run {
        // 1.0-3.0 s dragged 1.5 s left: stops at zero with its two seconds intact.
        val d = EditRules.clampedShift(1_000, 3_000, -1_500, 10_000)
        check(d == -1_000L, "a caption dragged past zero moved $d, not -1000")
        check(3_000 + d - (1_000 + d) == 2_000L, "the dragged caption changed length")
        // Frame after frame of a drag past zero: never shorter, never below zero.
        var s = 1_000L; var e = 3_000L
        repeat(60) { val k = EditRules.clampedShift(s, e, -40, 10_000); s += k; e += k }
        check(s == 0L && e == 2_000L, "sixty frames of leftward drag left it at $s-$e")
        // Past the end: stops with its end on the last frame.
        val r = EditRules.clampedShift(7_000, 9_000, 5_000, 10_000)
        check(r == 1_000L, "a caption dragged past the end moved $r, not 1000")
        // One already past the end (an old draft) can come back, not go further.
        check(EditRules.clampedShift(9_000, 12_000, 500, 10_000) == 0L, "an overhanging caption was pushed further out")
        check(EditRules.clampedShift(9_000, 12_000, -500, 10_000) == -500L, "an overhanging caption could not be pulled back")
    }

    // --- Resizing: never inside out, never past the picture. ------------------
    run {
        val a = EditRules.resized(1_000, 3_000, -5_000, 0, 10_000, minMs)
        check(a == Span(0, 3_000), "head dragged past zero gave $a")
        val b = EditRules.resized(1_000, 3_000, 0, 20_000, 10_000, minMs)
        check(b == Span(1_000, 10_000), "tail dragged past the end gave $b")
        val c = EditRules.resized(1_000, 3_000, 5_000, 0, 10_000, minMs)
        check(c == Span(2_800, 3_000), "head dragged past the tail gave $c")
        val d = EditRules.resized(1_000, 3_000, 0, -5_000, 10_000, minMs)
        check(d == Span(1_000, 1_200), "tail dragged past the head gave $d")
        // An end not touched stays exactly where it was.
        val e = EditRules.resized(1_000, 1_050, 0, 0, 10_000, minMs)
        check(e == Span(1_000, 1_050), "an untouched short caption was changed to $e")
    }

    // --- New text at the playhead ends on the last frame at worst (X8). -------
    run {
        val atEnd = EditRules.placedAt(10_000, 3_000, 10_000, minMs)
        check(atEnd == Span(7_000, 10_000), "a title added on the last frame was placed at $atEnd")
        check(atEnd.endMs <= 10_000, "a title added at the end ran past it")
        val near = EditRules.placedAt(9_950, 3_000, 10_000, minMs)
        check(near == Span(7_000, 10_000), "a title 50 ms from the end was placed at $near")
        val mid = EditRules.placedAt(2_000, 3_000, 10_000, minMs)
        check(mid == Span(2_000, 5_000), "a title mid-edit was placed at $mid")
        val clipped = EditRules.placedAt(8_500, 3_000, 10_000, minMs)
        check(clipped == Span(8_500, 10_000), "a title near the end was placed at $clipped")
        val short = EditRules.placedAt(0, 3_000, 1_000, minMs)
        check(short == Span(0, 1_000), "a title on a one-second edit was placed at $short")
        // Every placement lies inside the picture, for a sweep of playheads.
        for (p in 0L..12_000L step 250) {
            val s = EditRules.placedAt(p, 3_000, 10_000, minMs)
            check(s.startMs >= 0 && s.endMs <= 10_000 && s.lengthMs >= minMs, "playhead $p placed text at $s")
        }
    }

    // --- Splitting a caption (X9). --------------------------------------------
    run {
        val halves = EditRules.splitAt(1_000, 5_000, 2_500, minMs)
        check(halves == Span(1_000, 2_500) to Span(2_500, 5_000), "split gave $halves")
        check(EditRules.splitAt(1_000, 5_000, 1_100, minMs) == null, "a split leaving a 100 ms sliver was allowed")
        check(EditRules.splitAt(1_000, 5_000, 4_900, minMs) == null, "a split leaving a 100 ms tail was allowed")
        check(EditRules.splitAt(1_000, 5_000, 6_000, minMs) == null, "a split outside the caption was allowed")
        // Cut goes to the selected item only with the playhead on it; elsewhere
        // it razors the tracks, as it did before anything was selected.
        check(EditRules.cutsItem(1_000, 5_000, 2_500), "the playhead on the caption did not cut it")
        check(!EditRules.cutsItem(1_000, 5_000, 10_000), "a Cut far past the selected sticker went to the sticker")
        check(!EditRules.cutsItem(1_000, 5_000, 1_000) && !EditRules.cutsItem(1_000, 5_000, 5_000), "an edge counted as on it")
    }

    // --- Snap to the beat keeps hand-placed markers (A10). --------------------
    run {
        val beats = listOf(0L, 500, 1_000, 1_500, 2_000)
        val manual = 730L
        val first = EditRules.mergedBeatMarkers(listOf(manual), beats, beats, 33)
        check(manual in first, "a hand-placed marker was wiped by snapping to every beat")
        check(beats.all { it in first }, "snapping to every beat lost a beat: $first")
        // Then every other beat: the old beat markers thin out, the manual one stays.
        val second = EditRules.mergedBeatMarkers(first, beats, listOf(0L, 1_000, 2_000), 33)
        check(second == listOf(0L, 730, 1_000, 2_000), "every-2 after every-beat gave $second")
        // A marker a frame off a beat is the beat's; no near-duplicates.
        val dup = EditRules.mergedBeatMarkers(listOf(1_010L), beats, beats, 33)
        check(dup.size == beats.size, "a marker a frame off a beat was kept as a duplicate: $dup")
    }

    // --- Keep it smooth raises slow parts, keeps the shape (V17). -------------
    run {
        val bullet = SpeedRamp.preset(RampShape.BulletTime, 4_000)
        val held = EditRules.heldAtLeast(bullet, 0.8f)
        check(held.isRamped, "keep it smooth flattened a bullet-time ramp")
        check(held.slowestSpeed >= 0.8f - 1e-4f, "the slowest part is still ${held.slowestSpeed}")
        check(abs(held.speedAt(0) - bullet.speedAt(0)) < 1e-4f, "the fast opening was changed")
        for (t in 0L..4_000L step 40) {
            check(held.speedAt(t) >= 0.8f - 1e-4f, "at $t ms the curve is ${held.speedAt(t)}")
        }
        val flat = EditRules.heldAtLeast(SpeedRamp.flat(0.25f), 0.8f)
        check(abs(flat.flatSpeed - 0.8f) < 1e-4f && !flat.isRamped, "a flat quarter speed became ${flat.flatSpeed}")
        check(EditRules.heldAtLeast(SpeedRamp(), 0.8f) == SpeedRamp(), "normal speed was changed")
        val fast = SpeedRamp.flat(2f)
        check(EditRules.heldAtLeast(fast, 0.8f) == fast, "a fast clip was changed")
    }

    // --- Sync against the head shot as it sits (A6). --------------------------
    run {
        // Untouched head: the old answer.
        val a = EditRules.syncPlacement(1_200, 0, 30_000, 60_000, minMs)
        check(a.sourceInMs == 1_200L && a.timelineStartMs == 0L, "an untouched head synced to $a")
        // Head trimmed 5 s then gaps closed: file 5000 plays at timeline 0.
        val b = EditRules.syncPlacement(1_200, 5_000, 30_000, 60_000, minMs)
        check(b.sourceInMs == 6_200L && b.timelineStartMs == 0L, "a trimmed-and-closed head synced to $b")
        // Head dragged 3 s later: file 0 plays at timeline 3000.
        val c = EditRules.syncPlacement(1_200, -3_000, 30_000, 60_000, minMs)
        check(c.sourceInMs == 0L && c.timelineStartMs == 1_800L, "a moved head synced to $c")
        // The invariant itself: at any timeline moment in both, sound file time
        // minus video file time is the analyser's offset.
        for ((offset, delta) in listOf(1_200L to 0L, 1_200L to 5_000L, -800L to 2_000L, 400L to -3_000L)) {
            val p = EditRules.syncPlacement(offset, delta, 30_000, 60_000, minMs)
            val t = 10_000L
            val soundFile = p.sourceInMs + (t - p.timelineStartMs)
            val videoFile = t + delta
            check(soundFile - videoFile == offset, "offset $offset, delta $delta: sound runs ${soundFile - videoFile} ahead")
        }
        val r = EditRules.syncPlacement(0, 0, 30_000, 60_000, minMs)
        check(r.sourceInMs == 0L && r.timelineStartMs == 0L, "a reset with an untouched head gave $r")
        // Out point always after in point, inside the file.
        val d = EditRules.syncPlacement(59_900, 0, 1_000, 60_000, minMs)
        check(d.sourceOutMs > d.sourceInMs && d.sourceOutMs <= 60_000, "a sync near the file end gave $d")
    }

    // --- Reorder and insert (task 9). -----------------------------------------
    run {
        val ids = listOf("a", "b", "c", "d")
        check(EditRules.reordered(ids, "a", 3) == listOf("b", "c", "d", "a"), "a to the end")
        check(EditRules.reordered(ids, "d", 0) == listOf("d", "a", "b", "c"), "d to the start")
        check(EditRules.reordered(ids, "b", 2) == listOf("a", "c", "b", "d"), "b one along")
        check(EditRules.reordered(ids, "b", 99) == listOf("a", "c", "d", "b"), "an index past the end")
        check(EditRules.reordered(ids, "x", 1) == ids, "an unknown id changed the order")

        val spans = listOf(Span(0, 4_000), Span(4_000, 10_000))
        fun at(s: List<Span>, p: Long, added: List<Long> = listOf(3_000)) = EditRules.insertion(s, p, added)
        check(at(spans, 1_000).atMs == 0L && at(spans, 1_000).index == 0, "near a clip's head")
        check(at(spans, 3_000).atMs == 4_000L && at(spans, 3_000).index == 1, "near a clip's tail")
        check(at(spans, 4_000).atMs == 4_000L, "on a cut")
        check(at(spans, 3_000).followersShiftMs == 3_000L, "butted followers moved ${at(spans, 3_000).followersShiftMs}")
        check(at(spans, 15_000).let { it.atMs == 10_000L && it.index == 2 && it.followersShiftMs == 0L }, "past the end")
        check(EditRules.insertion(emptyList(), 5_000, listOf(3_000)).atMs == 0L, "an empty track")
        val gap = listOf(Span(0, 2_000), Span(5_000, 8_000))
        check(at(gap, 3_000).atMs == 2_000L, "in a gap")
        check(at(gap, 3_000).followersShiftMs == 3_000L, "a gap after the cut was not kept")

        // A 1 s dissolve: B starts at 4 s, a second before A ends.
        val dissolve = listOf(Span(0, 5_000), Span(4_000, 9_000))
        val tail = at(dissolve, 3_500)
        check(tail.index == 1 && tail.atMs == 5_000L, "near A's end: $tail")
        // B keeps its second of dissolve, now over the new clip, and no more.
        check(4_000 + tail.followersShiftMs == 5_000L + 3_000L - 1_000L, "B landed at ${4_000 + tail.followersShiftMs}")
        // Near B's head, inside B: still after all of A, not at B's start inside A.
        val head = at(dissolve, 5_500)
        check(head.index == 1 && head.atMs == 5_000L, "near B's head the new clip went at ${head.atMs}")
        // A new clip shorter than two dissolves: B's overlap is cut to half of it.
        val short = at(dissolve, 3_500, listOf(1_000))
        check(4_000 + short.followersShiftMs == 5_000L + 1_000L - 500L, "over a short clip B landed at ${4_000 + short.followersShiftMs}")

        // A sweep: whatever the track and the playhead, nothing lands under the
        // clip before the cut, and the next clip overlaps the new media by at most
        // half the last new clip, never more than it overlapped before.
        val rnd = java.util.Random(7)
        repeat(2_000) {
            var cursor = 0L
            val track = (0 until 1 + rnd.nextInt(5)).map {
                val len = 400L + rnd.nextInt(6_000)
                val back = if (it > 0 && rnd.nextBoolean()) minOf(rnd.nextInt(1_500).toLong(), len / 2) else 0L
                val gapMs = if (back == 0L && rnd.nextInt(4) == 0) rnd.nextInt(2_000).toLong() else 0L
                val start = (cursor - back + gapMs).coerceAtLeast(0L)
                Span(start, start + len).also { s -> cursor = s.endMs }
            }
            val added = (0 until 1 + rnd.nextInt(3)).map { 200L + rnd.nextInt(5_000) }
            val p = rnd.nextInt(cursor.toInt() + 3_000).toLong()
            val ins = EditRules.insertion(track, p, added)
            if (ins.index > 0) check(ins.atMs >= track[ins.index - 1].endMs, "new media under the clip before the cut: $track at $p")
            if (ins.index < track.size) {
                val next = track[ins.index]
                val newStart = next.startMs + ins.followersShiftMs
                val addedEnd = ins.atMs + added.sum()
                val overlap = addedEnd - newStart
                val before = (ins.atMs - next.startMs).coerceAtLeast(0L)
                check(overlap <= added.last() / 2 && overlap <= before.coerceAtLeast(0L) || overlap <= 0L,
                    "the next clip overlapped the new media by $overlap: $track at $p adding $added")
                check(newStart >= ins.atMs, "the next clip starts before the new media: $track at $p")
            }
        }
    }

    // --- Auto-captions keep to the part of the file a clip plays (X1). --------
    run {
        val speech = listOf(Span(500, 1_500), Span(4_000, 6_000), Span(9_500, 12_000))
        val kept = EditRules.speechInWindow(speech, 5_000, 10_000, 150)
        check(kept == listOf(Span(5_000, 6_000), Span(9_500, 10_000)), "the window kept $kept")
        val none = EditRules.speechInWindow(speech, 1_450, 3_000, 150)
        check(none.isEmpty(), "a 50 ms fragment of a word was kept: $none")
    }

    // --- Captions and effects over a retimed clip (X2). -----------------------
    run {
        // 2x clip at timeline 0 showing file 0-20 s; a caption at 4-6 s of the timeline
        // is file 8-12 s.
        val fast = clip(SpeedRamp.flat(2f), srcIn = 0, srcOut = 20_000, start = 0)
        check(fast.durationMs == 10_000L, "a 2x twenty-second shot is not ten seconds")
        val fx = TimedEffect("e", EffectKind.Flash, 4_000, 6_000).shiftedInto(fast)
        check(fx.startMs == 8_000L && fx.endMs == 12_000L, "an effect over a 2x clip landed at ${fx.startMs}-${fx.endMs}")

        // Trimmed and moved, 1x: the old constant shift still holds.
        val moved = clip(srcIn = 3_000, srcOut = 9_000, start = 10_000)
        val m = TimedEffect("e", EffectKind.Flash, 11_000, 12_000).shiftedInto(moved)
        check(m.startMs == 4_000L && m.endMs == 5_000L, "an effect over a moved clip landed at ${m.startMs}-${m.endMs}")

        // Outside the clip the file carries on at 1x instead of pinning to an edge.
        check(moved.sourceAtExtended(9_000) == 2_000L, "a second before the clip mapped to ${moved.sourceAtExtended(9_000)}")
        check(moved.sourceAtExtended(17_000) == 10_000L, "a second after the clip mapped to ${moved.sourceAtExtended(17_000)}")
        check(moved.sourceAtExtended(16_000) == 9_000L, "the clip's end mapped to ${moved.sourceAtExtended(16_000)}")

        // Ramped: the mapping is monotonic and agrees with the clip's own at every frame.
        val ramped = clip(SpeedRamp.preset(RampShape.entries.first(), 10_000), srcIn = 0, srcOut = 10_000, start = 2_000)
        var last = Long.MIN_VALUE
        for (t in 0L..(ramped.timelineEndMs + 2_000) step 33) {
            val s = ramped.sourceAtExtended(t)
            check(s >= last, "the mapping went backwards at $t")
            if (t >= ramped.timelineStartMs && t <= ramped.timelineEndMs) {
                check(s == ramped.sourceAt(t), "inside the clip the mapping differs from sourceAt at $t")
            }
            last = s
        }
    }

    // --- A sound added at the playhead lands where it can be seen. --------------
    run {
        val lastMoment = 1_000L
        // Mid-edit: at the playhead, ending with the edit.
        val mid = EditRules.soundLanding(2_000, 8_200, 60_000, lastMoment)
        check(mid == EditRules.SoundLanding(2_000, 6_200), "a song added mid-edit landed at $mid")
        // The device's case: the playhead parked half a second from the end. A
        // song does not fit there, so it is backed up to end with the edit.
        val late = EditRules.soundLanding(7_670, 8_200, 60_000, lastMoment)
        check(late == EditRules.SoundLanding(0, 8_200), "a song added near the end landed at $late, not from the start")
        val atEnd = EditRules.soundLanding(8_200, 8_200, 60_000, lastMoment)
        check(atEnd == EditRules.SoundLanding(0, 8_200), "a song added at the very end landed at $atEnd")
        // A second from the end is still the playhead; a hair inside it is not.
        check(EditRules.soundLanding(7_200, 8_200, 60_000, lastMoment).timelineStartMs == 7_200L, "a second of room went to the start")
        check(EditRules.soundLanding(7_201, 8_200, 60_000, lastMoment).timelineStartMs == 0L, "under a second of room stayed at the playhead")
        // A short file plays whole; so does one added where there is no picture.
        check(EditRules.soundLanding(1_000, 8_200, 3_000, lastMoment) == EditRules.SoundLanding(1_000, 3_000), "a short file was cut")
        check(EditRules.soundLanding(0, 0, 3_000, lastMoment) == EditRules.SoundLanding(0, 3_000), "no picture: the file was cut")
        // An edit shorter than the last moment: from the start, ending with it.
        check(EditRules.soundLanding(300, 500, 3_000, lastMoment) == EditRules.SoundLanding(0, 500), "a tiny edit's song landed elsewhere")
        // A sound effect that fits the last second stays on the beat it was put on.
        val ding = EditRules.soundLanding(7_500, 8_200, 400, lastMoment)
        check(ding == EditRules.SoundLanding(7_500, 400), "a ding on the last beat was moved: $ding")
        check(EditRules.soundLanding(7_800, 8_200, 400, lastMoment) == EditRules.SoundLanding(7_800, 400), "a ding ending exactly with the edit was moved")
        // One that does not fit is backed up to end with the edit - near the
        // playhead, as a title is - not sent to the start.
        val backed = EditRules.soundLanding(7_500, 8_200, 900, lastMoment)
        check(backed == EditRules.SoundLanding(7_300, 900), "a sound too long for the last second landed at $backed")
        check(EditRules.soundLanding(8_200, 8_200, 400, lastMoment) == EditRules.SoundLanding(7_800, 400), "a short sound at the very end was not backed up")
        // Past the end - an old draft's playhead - there is nothing to end with.
        check(EditRules.soundLanding(20_000, 8_000, 60_000, lastMoment) == EditRules.SoundLanding(20_000, 60_000), "past the end, the song was moved")
        // Every landing ends by the edit's end or plays whole, and never starts before zero.
        for (playhead in 0L..9_000L step 137L) for (track in listOf(200L, 900L, 1_500L, 60_000L)) {
            val l = EditRules.soundLanding(playhead, 8_200, track, lastMoment)
            check(l.timelineStartMs >= 0L, "a landing before zero: $l")
            check(l.sourceOutMs in 1L..track, "a landing playing more than the file or nothing: $l")
            if (playhead <= 8_200L) check(l.timelineStartMs + l.sourceOutMs <= 8_200L || l.sourceOutMs == track, "a cut sound runs past the edit: $l")
        }
    }

    // A new line keeps clear of the lines showing with it.
    run {
        val r = com.squish.app.editor.TextPlacementRules
        if (r.freeY(emptyList()) != 0.5f) problems += "an empty picture did not get the middle"
        val y = r.freeY(listOf(0.5f))
        if (kotlin.math.abs(y - 0.5f) < 0.12f) problems += "a new line landed on a line in the middle: $y"
        val busy = listOf(0.45f, 0.5f, 0.56f, 0.62f)
        val z = r.freeY(busy)
        if (busy.any { kotlin.math.abs(it - z) < 0.12f } || z !in 0.1f..0.9f) problems += "a crowded middle gave $z"
        // Stickers: each of twenty lands clear of the others until the picture
        // is full, and never on top of another even then.
        val placed = mutableListOf<Pair<Float, Float>>()
        repeat(20) { i ->
            val s = r.freeSpot(placed)
            if (s.first !in 0.1f..0.9f || s.second !in 0.1f..0.9f) problems += "sticker $i off the picture at $s"
            if (placed.any { it == s }) problems += "sticker $i landed exactly on another at $s"
            if (i < 12 && placed.any { kotlin.math.abs(it.first - s.first) < 0.16f && kotlin.math.abs(it.second - s.second) < 0.16f })
                problems += "sticker $i overlaps with room to spare at $s"
            placed += s
        }
        if (r.freeSpot(emptyList()) != (0.5f to 0.5f)) problems += "the first sticker is not in the middle"
    }
    println("edit rules: shift, resize, place, split, beat markers, smooth, sync, reorder, captions, retime, sound landing")
    if (problems.isEmpty()) println("PASS - edits keep items whole, inside the picture, and on the frames they belong to")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
