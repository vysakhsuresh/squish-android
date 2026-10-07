import com.squish.app.editor.EffectKind
import com.squish.app.timeline.Clip
import com.squish.app.editor.fittedTo
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.EffectSpan
import com.squish.app.timeline.LaneItem
import android.net.Uri
import com.squish.app.timeline.MAX_FOOTAGE_LAYER
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineLanes
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.TimelineWindow
import com.squish.app.timeline.ZOOM_MAX
import com.squish.app.timeline.ZOOM_MIN
import com.squish.app.timeline.mainGaps
import com.squish.app.timeline.withClipPlaced
import com.squish.app.timeline.withClipReordered
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withGapClosed
import com.squish.app.timeline.withClipRetimed
import com.squish.app.timeline.withRowsCompacted
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

// The strip's own arithmetic, executed: rows for things that overlap, snapping,
// trim handles that follow the finger on retimed clips, where a lifted shot
// lands, gaps closed one at a time, and the fixed centre playhead's window.
// Each block is one claim from docs/ROADMAP.md batch B7.

private val failures = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) failures += msg }

private fun shot(id: String, span: Long, start: Long = 0, srcIn: Long = 0, layer: Int = 0, ramp: SpeedRamp = SpeedRamp(), file: Long = 60_000) =
    Clip(
        id = id, kind = ClipKind.Video, label = id, sourceInMs = srcIn, sourceOutMs = srcIn + span,
        timelineStartMs = start, sourceDurationMs = file, speedRamp = ramp, layer = layer
    )

private fun sound(id: String, span: Long, start: Long, row: Int = 0) = Clip(
    id = id, kind = ClipKind.Audio, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 120_000, layer = row
)

/** A line of words as the editor hands it to the strip (EditorModels.toTimeline). */
private fun words(id: String, start: Long, end: Long) = Clip(
    id = id, kind = ClipKind.Text, label = id, sourceInMs = 0, sourceOutMs = end - start,
    timelineStartMs = start, sourceDurationMs = 60_000, text = id
)

private fun TimelineState.byId(id: String) = clips.first { it.id == id }
private fun TimelineState.overlayRows() = overlayClips.map { it.id to it.layer }

private fun noOverlapOnARow(items: List<LaneItem>, rows: Map<String, Int>, tag: String) {
    for (a in items) for (b in items) {
        if (a.id >= b.id) continue
        if (rows[a.id] == rows[b.id] && a.startMs < maxOf(b.endMs, b.startMs + 1) && b.startMs < maxOf(a.endMs, a.startMs + 1)) {
            failures += "$tag: ${a.id} and ${b.id} overlap on row ${rows[a.id]}"
        }
    }
}

fun main() {
    rows()
    snapping()
    trimsFollowTheFinger()
    lifting()
    gaps()
    window()

    println("lane checks: rows, snapping, trims, lifts, gaps, the centred window")
    if (failures.isEmpty()) println("PASS - rows never share a moment, edges land where the finger and the snap say")
    else { println("FAIL (${failures.size})"); failures.take(40).forEach { println("  - $it") }; exitProcess(1) }
}

private fun rows() {
    // Nothing overlapping: one row, however many.
    val flat = listOf(LaneItem("a", 0, 1_000), LaneItem("b", 1_000, 2_000), LaneItem("c", 5_000, 9_000))
    val flatRows = TimelineLanes.rows(flat)
    check(flatRows.values.all { it == 0 } && TimelineLanes.rowCount(flatRows) == 1, "butted items took more than one row: $flatRows")

    // Two songs overlapping: two rows, the earlier on top of the list (row 0).
    val two = listOf(LaneItem("late", 3_000, 9_000), LaneItem("early", 0, 5_000))
    val twoRows = TimelineLanes.rows(two)
    check(twoRows["early"] == 0 && twoRows["late"] == 1, "overlap rows: $twoRows")

    // A preference is kept where free, and a row nothing is on is closed up.
    val prefer = listOf(LaneItem("a", 0, 1_000, preferredRow = 5), LaneItem("b", 0, 1_000))
    val preferRows = TimelineLanes.rows(prefer)
    check(preferRows["b"] == 0 && preferRows["a"] == 1, "preference with an empty row between: $preferRows")
    val alone = TimelineLanes.rows(listOf(LaneItem("a", 0, 1_000, preferredRow = 3)))
    check(alone["a"] == 0, "a lone item on a far row was not closed up: $alone")

    // A silly preference does not allocate a silly number of rows.
    val silly = TimelineLanes.rows(listOf(LaneItem("a", 0, 1, preferredRow = Int.MAX_VALUE), LaneItem("b", 0, 1)))
    check(TimelineLanes.rowCount(silly) == 2, "huge preference: $silly")

    // Zero-length items still take their moment.
    val zero = TimelineLanes.rows(listOf(LaneItem("a", 500, 500), LaneItem("b", 0, 1_000)))
    check(zero["a"] != zero["b"], "a zero-length item was put under another: $zero")

    // Random piles: never two overlapping on a row, and the answer does not
    // depend on the order the list arrives in.
    val random = Random(7)
    repeat(400) { round ->
        val n = random.nextInt(1, 14)
        val items = (0 until n).map {
            val start = random.nextLong(0, 20_000)
            LaneItem("i$it", start, start + random.nextLong(0, 6_000), preferredRow = random.nextInt(0, 4))
        }
        val r = TimelineLanes.rows(items)
        noOverlapOnARow(items, r, "random $round")
        check(r.size == n, "random $round: ${r.size} of $n placed")
        check(r == TimelineLanes.rows(items.shuffled(random)), "random $round: order changed the rows")
        val count = TimelineLanes.rowCount(r)
        check((0 until count).all { row -> r.values.contains(row) }, "random $round: an empty row was left")
        check(count <= n, "random $round: $count rows for $n items")
    }

    // Moving one item: nothing else changes row.
    val three = listOf(LaneItem("a", 0, 4_000), LaneItem("b", 2_000, 6_000), LaneItem("c", 8_000, 9_000))
    val before = TimelineLanes.rows(three)
    val prefs = TimelineLanes.preferencesAfterMove(three, "c", 1, 8_000)
    val after = TimelineLanes.rows(three.map { it.copy(preferredRow = prefs.getValue(it.id)) })
    check(after["a"] == before["a"] && after["b"] == before["b"] && after["c"] == 1, "move to row 1: $before -> $after")

    // Dropped onto a taken row, just before the one already there: it goes to the
    // nearest free row, and the one there stays - the finger moves one thing.
    val bed = listOf(LaneItem("song", 2_000, 10_000), LaneItem("fx", 12_000, 13_000))
    val bedPrefs = TimelineLanes.preferencesAfterMove(bed, "fx", 0, 1_500)
    check(bedPrefs["song"] == 0 && bedPrefs["fx"] == 1, "a drop onto a taken row displaced what was there: $bedPrefs")
    check(TimelineLanes.placedRow(bed, "fx", 0, 1_500) == 1, "placed row onto a taken row")
    val bedAfter = TimelineLanes.rows(
        bed.map { if (it.id == "fx") it.copy(startMs = 1_500, endMs = 2_500) else it }
            .map { it.copy(preferredRow = bedPrefs.getValue(it.id)) }
    )
    check(bedAfter == mapOf("song" to 0, "fx" to 1), "rows after a drop onto a taken row: $bedAfter")
    // Free where it is dropped: it stays on the row asked for.
    check(TimelineLanes.placedRow(bed, "fx", 0, 10_000) == 0, "a free spot on the row asked for was refused")
    // Far below: one new row, not many.
    check(TimelineLanes.placedRow(bed, "fx", 9, 12_000) == 1, "a drop far below: ${TimelineLanes.placedRow(bed, "fx", 9, 12_000)}")
    // Three rows, the middle one taken at the time: below it before above it.
    val stack = listOf(LaneItem("x", 0, 10_000), LaneItem("y", 0, 10_000, 1), LaneItem("z", 0, 10_000, 2), LaneItem("m", 20_000, 21_000))
    check(TimelineLanes.placedRow(stack, "m", 1, 5_000) == 3, "nearest free below a full stack: ${TimelineLanes.placedRow(stack, "m", 1, 5_000)}")
    // Random drops: whatever is asked, the moved item never lands on anything,
    // and nothing else changes row.
    repeat(300) { round ->
        val n = random.nextInt(2, 10)
        val items = (0 until n).map {
            val start = random.nextLong(0, 20_000)
            LaneItem("i$it", start, start + random.nextLong(1, 6_000), preferredRow = random.nextInt(0, 3))
        }
        val shown = TimelineLanes.rows(items)
        val movedId = "i${random.nextInt(n)}"
        val to = random.nextLong(0, 20_000)
        val p = TimelineLanes.preferencesAfterMove(items, movedId, random.nextInt(0, 5), to)
        val placed = items.map { if (it.id == movedId) it.copy(startMs = to, endMs = to + (it.endMs - it.startMs)) else it }
            .map { it.copy(preferredRow = p.getValue(it.id)) }
        val raw = placed.associate { it.id to p.getValue(it.id) }
        noOverlapOnARow(placed, raw, "random drop $round")
        check(items.all { it.id == movedId || p[it.id] == shown[it.id] }, "random drop $round: another item changed row")
        // The rows the strip then draws put everything where the drop said, closed up.
        val drawn = TimelineLanes.rows(placed)
        val order = raw.values.distinct().sorted()
        check(drawn.all { (id, row) -> order.indexOf(raw.getValue(id)) == row }, "random drop $round: drawn $drawn, placed $raw")
    }

    // Through the model: a sound dropped on the row below lands there and stays; the other stays too.
    val state = TimelineState(clips = listOf(shot("m", 20_000), sound("s1", 8_000, 0), sound("s2", 3_000, 10_000)))
    val moved = state.withClipPlaced("s2", 2_000, 1)
    val movedRows = TimelineLanes.rows(moved.audioClips.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) })
    check(moved.byId("s2").timelineStartMs == 2_000L && movedRows["s2"] == 1 && movedRows["s1"] == 0, "sound drop: $movedRows")
    val back = moved.withClipPlaced("s2", 12_000, 0)
    val backRows = TimelineLanes.rows(back.audioClips.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) })
    check(backRows["s2"] == 0 && backRows["s1"] == 0, "sound back to row 0: $backRows")
    check(state.withClipPlaced("s1", 0, 0) == state, "a drop where it already was changed the state")
    check(moved.byId("m") == state.byId("m"), "a sound drop moved the picture")

    // An overlay: onto a free layer, not onto a taken one (it moves along its own
    // row instead), and never onto the main track.
    val pips = TimelineState(clips = listOf(shot("m", 20_000), shot("p", 2_000, start = 1_000, layer = 1), shot("q", 4_000, start = 6_000, layer = 2)))
    val up = pips.withClipPlaced("p", 3_000, 2)
    check(up.byId("p").layer == 2 && up.byId("p").timelineStartMs == 3_000L, "overlay to a free layer: ${up.byId("p")}")
    val blocked = pips.withClipPlaced("p", 6_500, 2)
    check(blocked.byId("p").layer == 1, "overlay dropped onto a taken layer went there")
    check(blocked.byId("p").timelineStartMs == 6_500L, "overlay onto a taken layer did not move along its own: ${blocked.byId("p").timelineStartMs}")
    val down = pips.withClipPlaced("p", 1_000, 0)
    check(down.byId("p").layer == 1 && down.baseVideoClips.size == 1, "overlay dropped low joined the main track")
    // Footage stops at its own top row (a decoder each); a photo may use them all.
    val high = pips.withClipPlaced("p", 1_000, MAX_LAYER + 4)
    check(high.byId("p").layer == MAX_FOOTAGE_LAYER, "footage past its top row: layer ${high.byId("p").layer}")
    val still = pips.copy(clips = pips.clips.map {
        if (it.id == "p") it.copy(uri = Uri.parse("file:///data/user/0/com.squish.app/files/stills/overlay_p.png")) else it
    })
    val highStill = still.withClipPlaced("p", 1_000, MAX_LAYER + 4)
    check(highStill.byId("p").layer == MAX_LAYER, "photo past the top row: layer ${highStill.byId("p").layer}")
    // The drop leaves the rows as the strip drew them; closing up after it (as
    // placeClip does) takes out the row the overlay left and keeps the order.
    val left = pips.withClipPlaced("p", 12_000, 3)
    check(left.byId("p").layer == 3 && left.byId("q").layer == 2, "overlay to a new top row: ${left.overlayRows()}")
    val closed = left.withRowsCompacted()
    check(closed.byId("q").layer == 1 && closed.byId("p").layer == 2, "rows after the drop: ${closed.overlayRows()}")
    check(pips.withClipPlaced("p", -500, 1).byId("p").timelineStartMs == 0L, "overlay dropped before zero")
}

private fun snapping() {
    val targets = listOf(0L, 4_000L, 10_000L)
    val none = TimelineLanes.snapSpan(6_000, 1_000, targets, 200)
    check(none.startMs == 6_000L && none.lineMs == null, "snapped with nothing near: $none")
    val headSnap = TimelineLanes.snapSpan(4_150, 1_000, targets, 200)
    check(headSnap.startMs == 4_000L && headSnap.lineMs == 4_000L, "head snap: $headSnap")
    val tailSnap = TimelineLanes.snapSpan(8_880, 1_000, targets, 200)
    check(tailSnap.startMs == 9_000L && tailSnap.lineMs == 10_000L, "tail snap: $tailSnap")
    // The nearer end wins.
    val both = TimelineLanes.snapSpan(3_930, 6_100, targets, 200)
    check(both.startMs == 3_900L && both.lineMs == 10_000L, "nearer end: $both")
    // Never before zero.
    val early = TimelineLanes.snapSpan(-300, 1_000, listOf(600L), 500)
    check(early.startMs >= 0L, "snapped before zero: $early")
    val negative = TimelineLanes.snapSpan(100, 1_000, listOf(900L), 250)
    check(negative.startMs == 100L && negative.lineMs == null, "a tail snap that needed a negative start: $negative")
    // Exactly at the edge of the threshold still snaps; one past does not.
    check(TimelineLanes.nearest(4_200, targets, 200) == 4_000L, "threshold edge")
    check(TimelineLanes.nearest(4_201, targets, 200) == null, "threshold past")

    // Targets: the dragged thing and whatever moves with it are not among them.
    val state = TimelineState(
        clips = listOf(shot("a", 3_000), shot("b", 2_000, start = 3_000), shot("c", 4_000, start = 5_000), sound("s", 1_000, 7_777)),
        playheadMs = 1_234,
        effects = listOf(EffectSpan("e", "fx", 8_100, 8_900, EffectKind.entries.first()))
    )
    val moving = TimelineLanes.movingWithTail(state, "b")
    check(moving == setOf("b", "c"), "moving with b's tail: $moving")
    val t = TimelineLanes.snapTargets(state, listOf(2_500L), moving)
    check(t.containsAll(listOf(0L, 1_234L, 2_500L, 3_000L, 7_777L, 8_777L, 8_100L, 8_900L)), "targets missing: ${t.sorted()}")
    check(9_000L !in t && 5_000L !in t, "c is a target while it moves with b")
    check(TimelineLanes.movingWithTail(state, "s") == setOf("s"), "a sound's tail moves other things")

    // Scrubbing the strip: the cuts, the ends, markers, sounds and effects - never
    // the playhead itself, which is what moves.
    val scrub = TimelineLanes.scrubTargets(state, listOf(2_500L))
    check(scrub.containsAll(listOf(0L, 2_500L, 3_000L, 5_000L, 9_000L, 7_777L, 8_777L, 8_100L, 8_900L)), "scrub targets missing: ${scrub.sorted()}")
    check(1_234L !in scrub, "the playhead is a scrub target")
    check(state.durationMs in scrub, "the end of the edit is not a scrub target")
    // A drag 100 ms short of a cut, at a snap distance of 150, is held on it; the
    // strip is drawn there and the playhead sent there - the same moment.
    check(TimelineLanes.nearest(4_900, scrub, 150) == 5_000L, "scrub near a cut")
    check(TimelineLanes.nearest(4_700, scrub, 150) == null, "scrub away from everything snapped")
}

/**
 * Drives a handle the way the strip does - one event at a time, each asking for
 * the edge where the finger is now - through the model, and checks the edge ends
 * up under the finger.
 */
private fun trimsFollowTheFinger() {
    fun drive(start: TimelineState, id: String, head: Boolean, fingerFrom: Long, steps: List<Long>): TimelineState {
        var s = start
        var finger = fingerFrom
        for (d in steps) {
            finger += d
            val clip = s.byId(id)
            val (a, b) = TimelineLanes.edgeTrim(clip, head, finger)
            s = s.withClipTrimmed(id, a, b)
        }
        return s
    }
    val stream = List(40) { 25L } + List(15) { -12L }

    for (speed in listOf(1f, 2f, 0.5f)) {
        val ramp = if (speed == 1f) SpeedRamp() else SpeedRamp.flat(speed)
        // An overlay, free on its row: both edges land within a millisecond or two of the finger.
        val base = TimelineState(clips = listOf(shot("m", 30_000), shot("p", 6_000, start = 4_000, srcIn = 5_000, layer = 1, ramp = ramp)))
        val p = base.byId("p")
        val tail = drive(base, "p", head = false, fingerFrom = p.timelineEndMs, steps = stream)
        val tailWanted = p.timelineEndMs + stream.sum()
        check(abs(tail.byId("p").timelineEndMs - tailWanted) <= 2, "tail at ${speed}x: ${tail.byId("p").timelineEndMs} vs $tailWanted")
        val head = drive(base, "p", head = true, fingerFrom = p.timelineStartMs, steps = stream)
        val headWanted = p.timelineStartMs + stream.sum()
        check(abs(head.byId("p").timelineStartMs - headWanted) <= 2, "head at ${speed}x: ${head.byId("p").timelineStartMs} vs $headWanted")
        check(abs(head.byId("p").timelineEndMs - p.timelineEndMs) <= 2, "head trim at ${speed}x moved the tail")
        // Outwards: revealing footage before the head follows the finger too.
        val outwards = drive(base, "p", head = true, fingerFrom = p.timelineStartMs, steps = List(20) { -50L })
        check(abs(outwards.byId("p").timelineStartMs - (p.timelineStartMs - 1_000)) <= 2,
            "head outwards at ${speed}x: ${outwards.byId("p").timelineStartMs}")
    }

    // A ramped overlay: the edge still follows the finger.
    val curve = SpeedRamp(listOf(SpeedPoint(0, 0.5f), SpeedPoint(3_000, 2f), SpeedPoint(6_000, 1f)))
    val rampState = TimelineState(clips = listOf(shot("m", 30_000), shot("r", 6_000, start = 2_000, srcIn = 1_000, layer = 1, ramp = curve)))
    val r = rampState.byId("r")
    val rampTail = drive(rampState, "r", head = false, fingerFrom = r.timelineEndMs, steps = List(30) { -40L })
    check(abs(rampTail.byId("r").timelineEndMs - (r.timelineEndMs - 1_200)) <= 3, "ramped tail: ${rampTail.byId("r").timelineEndMs} vs ${r.timelineEndMs - 1_200}")

    // Words: edges are plain timeline time.
    val w = TimelineState(clips = listOf(shot("m", 30_000), words("t", 2_000, 5_000))).byId("t")
    val (hs, he) = TimelineLanes.edgeTrim(w, head = true, edgeMs = 2_600)
    check(hs == 600L && he == 0L, "words head: $hs, $he")
    val (ts, te) = TimelineLanes.edgeTrim(w, head = false, edgeMs = 4_000)
    check(ts == 0L && te == -1_000L, "words tail: $ts, $te")

    // The main track's tail: the followers come along and the edge is at the finger.
    val main = TimelineState(clips = listOf(shot("a", 5_000, srcIn = 2_000, ramp = SpeedRamp.flat(2f)), shot("b", 4_000, start = 2_500)))
    val a = main.byId("a")
    val mainTail = drive(main, "a", head = false, fingerFrom = a.timelineEndMs, steps = List(20) { -30L })
    check(abs(mainTail.byId("a").timelineEndMs - (a.timelineEndMs - 600)) <= 2, "main tail: ${mainTail.byId("a").timelineEndMs}")
    check(mainTail.byId("b").timelineStartMs == mainTail.byId("a").timelineEndMs, "main tail left a gap")

    // The main track's head: the edge stays, the footage moves under it by the
    // played time the finger went, measured from where the drag began.
    var s = main
    var travel = 0L
    repeat(25) {
        travel += 40L
        val now = s.byId("a")
        val wantIn = TimelineLanes.anchoredHeadIn(a, travel)
        s = s.withClipTrimmed("a", wantIn - now.sourceInMs, 0L)
    }
    check(s.byId("a").timelineStartMs == 0L, "main head trim moved the head")
    check(abs(s.byId("a").durationMs - (a.durationMs - 1_000)) <= 2, "main head trim: ${s.byId("a").durationMs} vs ${a.durationMs - 1_000}")
    check(s.byId("a").sourceInMs == a.sourceInMs + 2_000, "main head trim at 2x: in ${s.byId("a").sourceInMs}")
    check(s.byId("b").timelineStartMs == s.byId("a").timelineEndMs, "main head trim left a gap")
    // While it is dragged the strip draws the shot and every one after it moved
    // on by what it has lost (mainShown): the head lands under the finger, and
    // the tail and the next shot stay where they were until the finger lifts.
    val shift = a.durationMs - s.byId("a").durationMs
    val drawnMoving = TimelineLanes.movingWithTail(s, "a")
    check(drawnMoving == setOf("a", "b"), "moving with a's head: $drawnMoving")
    check(abs(s.byId("a").timelineStartMs + shift - (a.timelineStartMs + travel)) <= 2, "drawn head ${s.byId("a").timelineStartMs + shift} vs finger ${a.timelineStartMs + travel}")
    check(abs(s.byId("a").timelineEndMs + shift - a.timelineEndMs) <= 2, "drawn tail moved: ${s.byId("a").timelineEndMs + shift} vs ${a.timelineEndMs}")
    check(abs(s.byId("b").timelineStartMs + shift - main.byId("b").timelineStartMs) <= 2, "drawn next shot moved")
    // Held against the minimum, it does not run away when the finger comes back.
    var held = main
    travel = 0L
    repeat(60) {
        travel += 200L
        val now = held.byId("a")
        held = held.withClipTrimmed("a", TimelineLanes.anchoredHeadIn(a, travel) - now.sourceInMs, 0L)
    }
    check(held.byId("a").sourceSpanMs == MIN_CLIP_MS, "main head past the minimum: ${held.byId("a").sourceSpanMs}")
    repeat(10) {
        travel -= 200L
        val now = held.byId("a")
        held = held.withClipTrimmed("a", TimelineLanes.anchoredHeadIn(a, travel) - now.sourceInMs, 0L)
    }
    check(held.byId("a").sourceSpanMs == MIN_CLIP_MS, "main head came back before the finger reached the minimum again")

    // Unused footage either side, in played time.
    val grey = shot("g", 4_000, srcIn = 3_000, ramp = SpeedRamp.flat(2f), file = 20_000)
    check(TimelineLanes.unusedBeyond(grey, head = true) == 1_500L, "unused before: ${TimelineLanes.unusedBeyond(grey, true)}")
    check(TimelineLanes.unusedBeyond(grey, head = false) == 6_500L, "unused after: ${TimelineLanes.unusedBeyond(grey, false)}")
    check(TimelineLanes.unusedBeyond(words("t", 0, 1_000), head = false) == 0L, "words have unused footage")
    check(TimelineLanes.unusedBeyond(shot("u", 4_000, file = 0), head = false) == 0L, "unknown length has unused footage")
}

private fun lifting() {
    val main = TimelineState(clips = listOf(shot("a", 4_000), shot("b", 2_000, start = 4_000), shot("c", 6_000, start = 6_000)))
    val base = main.baseVideoClips
    // c lifted and held before a's middle: first.
    val first = TimelineLanes.reorderSlot(base, "c", 1_000)
    check(first.index == 0 && first.atMs == 0L, "slot before a: $first")
    val reordered = main.withClipReordered("c", first.index)
    check(reordered.baseVideoClips.map { it.id } == listOf("c", "a", "b"), "drop before a: ${reordered.baseVideoClips.map { it.id }}")
    check(reordered.baseVideoClips.zipWithNext().all { (x, y) -> x.timelineEndMs == y.timelineStartMs }, "reorder left the track unbutted")
    // Between a and b.
    val mid = TimelineLanes.reorderSlot(base, "c", 3_000)
    check(mid.index == 1 && mid.atMs == 4_000L, "slot between a and b: $mid")
    // Where it already is: no change.
    val same = TimelineLanes.reorderSlot(base, "c", 9_000)
    check(same.index == TimelineLanes.mainIndexOf(base, "c") - 0 && same.index == 2, "slot where c is: $same")
    check(main.withClipReordered("c", same.index) == main, "dropping where it was changed the edit")
    // a lifted and dropped past the end.
    val last = TimelineLanes.reorderSlot(base, "a", 50_000)
    check(last.index == 2 && main.withClipReordered("a", last.index).baseVideoClips.last().id == "a", "slot at the end: $last")
    // One shot alone.
    check(TimelineLanes.reorderSlot(listOf(shot("x", 1_000)), "x", 500) == com.squish.app.timeline.ReorderSlot(0, 0L), "one shot")

    // Edge scroll: still in the middle, faster the nearer the edge, left negative.
    check(TimelineLanes.edgeScrollMs(540f, 1080, 96f, 40.0) == 0.0, "edge scroll in the middle")
    check(TimelineLanes.edgeScrollMs(0f, 1080, 96f, 40.0) == -40.0, "edge scroll at the left")
    check(TimelineLanes.edgeScrollMs(1080f, 1080, 96f, 40.0) == 40.0, "edge scroll at the right")
    check(TimelineLanes.edgeScrollMs(1030f, 1080, 96f, 40.0) in 0.1..39.9, "edge scroll part way")
    check(TimelineLanes.edgeScrollMs(-200f, 1080, 96f, 40.0) == -40.0, "edge scroll past the edge")
}

private fun gaps() {
    // A draft from before the track was magnetic: a gap before b and one before c.
    val draft = TimelineState(clips = listOf(shot("a", 2_000, start = 500), shot("b", 2_000, start = 3_000), shot("c", 1_000, start = 7_000)))
    val found = draft.mainGaps()
    check(found.map { Triple(it.clipId, it.fromMs, it.toMs) } == listOf(
        Triple("a", 0L, 500L), Triple("b", 2_500L, 3_000L), Triple("c", 5_000L, 7_000L)
    ), "gaps: $found")
    val closed = draft.withGapClosed("b")
    check(closed.byId("a").timelineStartMs == 500L, "closing b's gap moved a")
    check(closed.byId("b").timelineStartMs == 2_500L, "b not closed up: ${closed.byId("b").timelineStartMs}")
    check(closed.byId("c").timelineStartMs == 6_500L, "c did not keep its own gap: ${closed.byId("c").timelineStartMs}")
    check(closed.mainGaps().size == 2, "closing one gap closed ${3 - closed.mainGaps().size}")
    check(draft.withGapClosed("a").byId("a").timelineStartMs == 0L, "the leading gap")
    val butted = TimelineState(clips = listOf(shot("a", 2_000), shot("b", 2_000, start = 2_000)))
    check(butted.mainGaps().isEmpty() && butted.withGapClosed("b") == butted, "a butted track has gaps")
    check(draft.withGapClosed("nobody") == draft, "closing an unknown gap changed the edit")

    // A speed change ripples the shots after it and keeps their joins: the
    // dissolve into c still overlaps b by its length.
    val dissolve = Transition(TransitionType.CrossFade, 400)
    val track = TimelineState(clips = listOf(shot("a", 4_000), shot("b", 4_000, start = 4_000), shot("c", 4_000, start = 7_600).copy(transitionIn = dissolve)))
    val faster = track.withClipRetimed("b", SpeedRamp.flat(2f))
    check(faster.byId("b").timelineStartMs == 4_000L && faster.byId("b").durationMs == 2_000L, "retimed b: ${faster.byId("b")}")
    check(faster.byId("c").timelineStartMs == faster.byId("b").timelineEndMs - 400, "c after a retime: ${faster.byId("c").timelineStartMs}")
    val slower = track.withClipRetimed("b", SpeedRamp.flat(0.5f))
    check(slower.byId("c").timelineStartMs == slower.byId("b").timelineEndMs - 400, "c after a slow-down: ${slower.byId("c").timelineStartMs}")
    // An old draft's gap before c is kept through it.
    val gapped = TimelineState(clips = listOf(shot("a", 4_000), shot("b", 4_000, start = 4_000), shot("c", 4_000, start = 9_000)))
    check(gapped.withClipRetimed("b", SpeedRamp.flat(2f)).byId("c").timelineStartMs == 7_000L, "a retime closed an old gap")
    // Off the main track, nothing else moves.
    val song = TimelineState(clips = listOf(shot("m", 10_000), sound("s", 4_000, 1_000), sound("t", 2_000, 5_000)))
    val songFaster = song.withClipRetimed("s", SpeedRamp.flat(2f))
    check(songFaster.byId("s").durationMs == 2_000L && songFaster.byId("t").timelineStartMs == 5_000L, "a sound retime moved another sound")

    // Off the main track, the editor carries what is butted after a retimed
    // sound (rippleAfterRetime) - whichever row it was moved to.
    fun retimed(clips: List<com.squish.app.timeline.Clip>, id: String, speed: Float) =
        clips.map { if (it.id == id) it.copy(speedRamp = SpeedRamp.flat(speed)) else it }
    val songA = sound("A", 10_000, 0)
    val sfxB = sound("B", 2_000, 10_000, row = 1)
    val fxC = sound("C", 1_000, 12_020, row = 0)
    val overMiddle = sound("D", 1_000, 3_000, row = 2)
    val later = sound("E", 1_000, 16_000)
    val beds = listOf(shot("m", 30_000), songA, sfxB, fxC, overMiddle, later)
    val slowA = TimelineLanes.rippleAfterRetime(retimed(beds, "A", 0.5f), songA, 40)
    fun List<com.squish.app.timeline.Clip>.at(id: String) = first { it.id == id }.timelineStartMs
    check(slowA.first { it.id == "A" }.timelineEndMs == 20_000L, "A at half speed: ${slowA.first { it.id == "A" }.timelineEndMs}")
    check(slowA.at("B") == 20_000L, "a follower on another row was left behind: B at ${slowA.at("B")}")
    check(slowA.at("C") == 22_020L, "the follower's follower: C at ${slowA.at("C")}")
    check(slowA.at("D") == 3_000L, "a sound over the middle of the retimed one moved: D at ${slowA.at("D")}")
    check(slowA.at("E") == 16_000L, "a sound after a gap moved: E at ${slowA.at("E")}")
    check(slowA.at("m") == 0L, "the picture moved")
    val fastA = TimelineLanes.rippleAfterRetime(retimed(beds, "A", 2f), songA, 40)
    check(fastA.at("B") == 5_000L && fastA.at("C") == 7_020L, "speed-up: B ${fastA.at("B")}, C ${fastA.at("C")}")
    // Overlays by their layer: one butted on another layer stays.
    val pipA = shot("p", 4_000, start = 1_000, layer = 1)
    val pipB = shot("q", 2_000, start = 5_000, layer = 1)
    val pipOther = shot("r", 2_000, start = 5_000, layer = 2)
    val pipsSlowed = TimelineLanes.rippleAfterRetime(retimed(listOf(shot("m", 30_000), pipA, pipB, pipOther), "p", 0.5f), pipA, 40)
    check(pipsSlowed.at("q") == 9_000L && pipsSlowed.at("r") == 5_000L, "overlay ripple: q ${pipsSlowed.at("q")}, r ${pipsSlowed.at("r")}")
    // The main track is the model's (withClipRetimed): untouched here.
    val mainOnly = listOf(shot("a", 4_000), shot("b", 4_000, start = 4_000))
    check(TimelineLanes.rippleAfterRetime(retimed(mainOnly, "a", 2f), mainOnly[0], 40) == retimed(mainOnly, "a", 2f), "rippled the main track")

    // A slow-down never leaves two overlays on one layer: the butted follower
    // is carried, and what it would then run into is pushed just clear of it.
    fun overlaps(clips: List<com.squish.app.timeline.Clip>): Boolean {
        val row = clips.filter { it.kind == ClipKind.Video && it.isOverlay }.groupBy { it.layer }
        return row.values.any { r -> r.sortedBy { it.timelineStartMs }.zipWithNext().any { (x, y) -> y.timelineStartMs < x.timelineEndMs } }
    }
    val o1 = shot("o1", 2_000, start = 0, layer = 1)
    val o2 = shot("o2", 2_000, start = 2_000, layer = 1)
    val o3 = shot("o3", 1_500, start = 4_500, layer = 1)
    val o4 = shot("o4", 1_000, start = 9_000, layer = 1)
    val row1 = listOf(shot("m", 30_000), o1, o2, o3, o4)
    val slowO1 = TimelineLanes.rippleAfterRetime(retimed(row1, "o1", 0.5f), o1, 40)
    check(!overlaps(slowO1), "a slowed overlay left two on one layer: ${slowO1.filter { it.layer == 1 }.map { it.id to it.timelineStartMs }}")
    check(slowO1.at("o2") == 4_000L, "the butted follower: o2 at ${slowO1.at("o2")}")
    check(slowO1.at("o3") == 6_000L, "o3 pushed just clear of o2: ${slowO1.at("o3")}")
    check(slowO1.at("o4") == 9_000L, "o4 had room and moved: ${slowO1.at("o4")}")
    // With nothing butted, the slowed clip itself would run into the next.
    val lone = listOf(shot("m", 30_000), o1, o3)
    val slowLone = TimelineLanes.rippleAfterRetime(retimed(lone, "o1", 0.25f), o1, 40)
    check(!overlaps(slowLone) && slowLone.at("o3") == 8_000L, "a lone slowed overlay ran into the next: o3 at ${slowLone.at("o3")}")
    // A speed-up moves nothing it was not carrying; sounds may overlap as they always could.
    check(TimelineLanes.rippleAfterRetime(retimed(row1, "o1", 2f), o1, 40).at("o3") == 4_500L, "a speed-up pushed o3")
    // An overlay before the retimed one is never touched.
    val before = shot("o0", 1_000, start = 0, layer = 1)
    val later1 = shot("o5", 2_000, start = 1_000, layer = 1)
    check(TimelineLanes.rippleAfterRetime(retimed(listOf(before, later1, shot("o6", 1_000, start = 3_500, layer = 1)), "o5", 0.5f), later1, 40).at("o0") == 0L, "an earlier overlay moved")

    // An effect is placed by the same end the edits fit it to.
    check(com.squish.app.editor.effectRoomMs(8_000, 20_000) == 8_000L, "an effect may be placed over a song's tail past the picture")
    check(com.squish.app.editor.effectRoomMs(0, 20_000) == 20_000L, "with no picture an effect has no room")
    val tail = listOf(com.squish.app.editor.TimedEffect("e", com.squish.app.editor.EffectKind.Glitch, 6_000, 8_000))
    check(tail.fittedTo(com.squish.app.editor.effectRoomMs(8_000, 20_000)) == tail, "an effect placed inside the room was dropped by the fit")
}

private fun window() {
    for (density in floatArrayOf(1f, 2.75f)) for (pps in floatArrayOf(ZOOM_MIN, 42f, ZOOM_MAX)) for (vp in intArrayOf(0, 720, 1080)) {
        for (at in doubleArrayOf(0.0, 1_234.5, 3_600_000.0)) {
            val w = TimelineWindow.linedOn(at, pps, density, vp)
            val line = vp * TimelineWindow.PLAYHEAD_FRACTION
            val tag = "lined $at at ${pps}pps ${vp}px x$density"
            // The window is clamped at the start of the edit now, so the
            // playhead is at the line only once it has got that far. Before
            // that the strip is laid out from the left edge and the playhead
            // walks across it - which is what stops a quarter-screen of the
            // strip being wasted, and what lets the playhead move at all.
            // These assertions used to say "at the line, always", which is the
            // bug stated as a requirement; see WindowChecks for the same lesson.
            check(w.scrollMs >= -1e-6, "$tag: scrolled before 0:00 (${w.scrollMs})")
            if (vp > 0) {
                val x = w.xPx(at.toLong())
                val slack = 1f + (at - at.toLong()).toFloat() * pps * density / 1000f
                check(x <= line + slack, "$tag: playhead past the line at $x")
                check(x >= -slack, "$tag: playhead off the left edge at $x")
                check(w.xPx(0L) <= slack, "$tag: the start of the edit is right of the left edge")
                // Where it is not clamped, the old equalities still hold exactly.
                if (w.scrollMs > 1e-6) {
                    check(abs(w.lineMs - at) < 1e-6, "$tag: line ${w.lineMs}")
                    check(abs(x - line) <= slack, "$tag: playhead at $x")
                    check(abs(w.exactMsAt(line) - at) < 1e-6, "$tag: exactMsAt ${w.exactMsAt(line)}")
                }
            }
            // Left of the start: nothing negative is ever sought.
            check(w.msAt(0f) >= 0L, "$tag: msAt below zero")
        }
    }
    // Fit: the whole edit lies across the whole strip, because with the window
    // clamped at 0:00 it is laid out from the left edge. It was fitted into
    // *half* the strip when the line sat in the middle, and three quarters when
    // the line moved to a quarter; both were working around a window that
    // scrolled before the start of the edit, which it no longer does.
    for (len in longArrayOf(1_000, 10_000, 30_000, 3 * 3_600_000L)) {
        val vpDp = 400f
        val after = vpDp
        val pps = TimelineLanes.fitZoom(len, vpDp)
        check(pps in ZOOM_MIN..ZOOM_MAX, "fit $len out of range")
        val drawn = len / 1000f * pps
        if (len <= (TimelineLanes.MAX_FIT_SECONDS * 1000).toLong() && pps < ZOOM_MAX) {
            check(drawn <= after, "fit $len: ${drawn}dp past the right edge ($after available)")
            // And it must *use* that room, or the fit is just a smaller zoom
            // with a different name: within the 12 dp the margin costs.
            check(drawn >= after - 13f, "fit $len: only ${drawn}dp of $after used")
        }
    }
    // Past MAX_FIT_SECONDS the fit stops zooming out, because a fit of the
    // whole thing is what made the strip creep invisibly under a playhead that
    // does not move. Checked as a *speed*, which is the thing that was wrong:
    // at a phone's density the strip has to move by something a person can see.
    run {
        val vpDp = 400f
        // The whole strip less the 12 dp margin, as fitZoom has it - the
        // playhead fraction is no longer subtracted, because the window is
        // clamped at 0:00 and the edit is laid out from the left edge.
        val usable = vpDp - 12f
        val floor = TimelineLanes.fitZoom(10 * 60_000L, vpDp)
        check(abs(floor - usable / TimelineLanes.MAX_FIT_SECONDS) < 1e-3f,
            "a ten-minute edit fits at $floor dp/s, not the ${usable / TimelineLanes.MAX_FIT_SECONDS} floor")
        // 2.5 is this phone's density; 15 real pixels a second is about the
        // least that reads as motion rather than as a frozen strip. The old
        // whole-edit fit of a 90-second clip was eight.
        check(floor * 2.5f >= 15f, "a long edit still only scrolls ${floor * 2.5f} pixels a second")
        // And a short edit is still fitted whole: the floor must not zoom *in*
        // on something that already fits.
        val short = TimelineLanes.fitZoom(5_000L, vpDp)
        check(abs(short - usable / 5f) < 0.2f, "a five-second edit no longer fits the strip: $short")
    }

    // ---- An emptied edit is empty. -----------------------------------------
    //
    // Seen on the phone on 8 October with both numbers on screen at once: the
    // header read "0 clips · 0:22.266" while the transport under it read
    // 0:00.000 / 0:00.000. The length fell back to the source file's own window
    // whenever nothing was laid down - which is a project still loading, and
    // also an edit whose clips have all been deleted.
    //
    // And `SquishError.preflight` refuses an export on `trimmedDurationMs <= 0`,
    // so at 22.266 the emptied edit sailed past that and would have rendered
    // twenty-two seconds of nothing.
    run {
        val window = 22_266L
        // Loaded and empty is empty - the header, and the export's refusal.
        check(TimelineLanes.timelineLength(0L, window, stillLoading = false) == 0L,
            "an emptied edit still reports the length of the file it was opened on")
        // Still loading and empty is the file's window - a project staged and
        // not yet read has no clips either, and the spinner is up.
        check(TimelineLanes.timelineLength(0L, window, stillLoading = true) == window,
            "a project still loading lost the length it was opened on")
        // Anything laid down wins, loading or not.
        check(TimelineLanes.timelineLength(9_000L, window, stillLoading = false) == 9_000L,
            "a laid-out edit did not report what is on its tracks")
        check(TimelineLanes.timelineLength(9_000L, window, stillLoading = true) == 9_000L,
            "a laid-out edit took the source's window while loading")
        // A sound past the last shot is laid down too, so it is the length.
        check(TimelineLanes.timelineLength(25_257L, window, stillLoading = false) == 25_257L,
            "a song past the last shot stopped counting")
        // Never negative, whatever the window reads.
        check(TimelineLanes.timelineLength(0L, -5L, stillLoading = true) == 0L,
            "a negative source window came back negative")
    }
    // The strip as drawn agrees with the strip a finger lands on - including at
    // 0:00, where the window is clamped against the left edge and the playhead
    // is *not* at its fraction. slideFor was written on that assumption and had
    // to be rephrased on the window's own scroll; this is the pair that catches
    // the two parting.
    for (pps in floatArrayOf(2f, 42f, 400f)) for (vp in intArrayOf(1080, 1079)) {
        for (at in doubleArrayOf(0.0, 7_777.0, 600_000.0)) {
            val live = TimelineWindow.linedOn(at, pps, 2.75f, vp)
            val s = TimelineWindow.slideFor(live)
            for (t in longArrayOf(at.toLong(), at.toLong() + 999, 0L)) {
                val drawnAt = Math.round(s.drawn.xPx(t)) - s.padPx + s.shiftPx
                val liveAt = live.xPx(t)
                if (abs(liveAt) < 3 * vp) {
                    check(abs(drawnAt - liveAt) <= 1f, "pps $pps vp $vp at $at: $t drawn at $drawnAt, live $liveAt")
                }
            }
        }
    }
}
