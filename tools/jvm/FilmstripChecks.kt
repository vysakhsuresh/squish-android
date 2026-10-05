import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedRamp
import com.squish.app.media.video.BoundedCache
import com.squish.app.media.video.FilmstripPlan

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

fun main() {
    // A clip narrower than a thumbnail gets no thumbnails. This is the whole
    // defence against a hundred one-tile clips each opening a decoder.
    check("a hairline clip asks for nothing", FilmstripPlan.tileCount(4f) == 0)
    check("a clip just under the floor asks for nothing", FilmstripPlan.tileCount(11f) == 0)
    check("a clip at the floor asks for one", FilmstripPlan.tileCount(12f) == 1)
    check("a 3 s photo at a fitted zoom (about 30dp) shows its picture", FilmstripPlan.tileCount(30f) == 1)
    check("a 340dp clip asks for ten", FilmstripPlan.tileCount(340f) == 10)

    // The cap is what keeps a zoomed-in clip from asking without limit.
    check(
        "a mile-wide clip stops at the cap",
        FilmstripPlan.tileCount(40_000f) == FilmstripPlan.MAX_TILES
    )

    // Tile times land inside the clip's own source range, in order, and at tile
    // centres. A strip that samples outside the range shows frames the clip does
    // not contain, which is worse than showing none.
    val times = FilmstripPlan.tileTimes(sourceInMs = 10_000, sourceOutMs = 20_000, tiles = 5)
    check("one time per tile", times.size == 5)
    check("every time is inside the range", times.all { it in 10_000..20_000 })
    check("times run forwards", times == times.sorted())
    // Centres of five 2000ms tiles: 11000, 13000, 15000, 17000, 19000.
    check("times sit at tile centres", times == listOf(11_000L, 13_000L, 15_000L, 17_000L, 19_000L))

    // A clip trimmed to nothing must not divide by zero or sample backwards.
    val degenerate = FilmstripPlan.tileTimes(sourceInMs = 5_000, sourceOutMs = 5_000, tiles = 3)
    check("an empty range still yields one time per tile", degenerate.size == 3)
    check("an empty range samples its own instant", degenerate.all { it == 5_000L })
    check("no tiles means no times", FilmstripPlan.tileTimes(0, 1_000, 0).isEmpty())
    check("a reversed range does not go backwards", FilmstripPlan.tileTimes(9_000, 1_000, 4).all { it >= 0 })

    // Quantising is what makes the cache worth having: a clip nudged by a frame
    // asks for the tiles it already holds.
    val original = FilmstripPlan.tileTimes(10_000, 20_000, 5)
    val nudged = FilmstripPlan.tileTimes(10_030, 20_030, 5)
    check("a 30ms nudge changes nothing", original == nudged)
    check("every time is on the grid", original.all { it % FilmstripPlan.QUANTUM_MS == 0L })
    check("quantising never goes negative", FilmstripPlan.quantize(-500) == 0L)

    // A real trim must still move the sampling, or the strip would lie.
    val trimmed = FilmstripPlan.tileTimes(10_000, 15_000, 5)
    check("a real trim resamples", trimmed != original)

    // Keys separate sources. Two clips of different files at the same moment must
    // not share a thumbnail.
    check(
        "keys distinguish sources",
        FilmstripPlan.key("a", 200) != FilmstripPlan.key("b", 200)
    )
    check(
        "keys distinguish moments",
        FilmstripPlan.key("a", 200) != FilmstripPlan.key("a", 400)
    )

    // The cache holds its budget, evicts the least recently used, and never
    // exceeds the ceiling. This is the filmstrip's whole memory story.
    val cache = BoundedCache<Int>(budgetBytes = 300) { 100L }
    cache.put("a", 1)
    cache.put("b", 2)
    cache.put("c", 3)
    check("three of a hundred fit in three hundred", cache.size == 3)
    check("the bytes are counted", cache.bytes == 300L)

    cache.get("a")                 // touch a, so b is now the oldest
    cache.put("d", 4)
    check("the budget holds", cache.bytes == 300L && cache.size == 3)
    check("the least recently used went", cache.get("b") == null)
    check("the touched one stayed", cache.get("a") == 1)
    check("the newest is there", cache.get("d") == 4)

    // Re-putting a key must not double-count it.
    cache.put("d", 40)
    check("a replaced entry is counted once", cache.bytes == 300L && cache.size == 3)
    check("a replaced entry holds the new value", cache.get("d") == 40)

    // Something larger than the whole budget is refused rather than emptying it.
    val small = BoundedCache<Int>(budgetBytes = 100) { if (it == 99) 1_000L else 50L }
    small.put("x", 1)
    small.put("y", 99)
    check("an oversized entry is refused", small.get("y") == null)
    check("refusing it evicted nothing", small.get("x") == 1)

    cache.clear()
    check("clearing resets the count", cache.size == 0 && cache.bytes == 0L)

    rampedTileChecks()

    if (failures.isEmpty()) {
        println("PASS - filmstrips sample inside their clips, reuse tiles, and stay inside their budget")
    } else {
        failures.forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}

/**
 * A tile shows the moment it covers, on a retimed clip as on a flat one.
 *
 * The tiles divide the clip's *drawn* width equally - each is a weight(1f) of
 * the box - and the source times were taken by dividing the *source* span
 * equally, which is the same mapping only at a flat rate. On a curve every
 * tile showed a frame from the wrong moment, up to about a third of the clip
 * away: scrubbing to the start of a Bullet clip put a frame on the preview
 * that its first tile did not show, and the last tile was a second behind the
 * playhead.
 */
fun rampedTileChecks() {
    val tiles = 10

    // --- A flat rate must be unchanged: same answer as the old split. --------
    listOf(1f, 2f, 0.5f).forEach { rate ->
        val clip = shot(SpeedRamp.flat(rate))
        val onClock = FilmstripPlan.tileTimes(0L, clip.durationMs, tiles) { clip.sourceAt(it) }
        val oldWay = FilmstripPlan.tileTimes(clip.sourceInMs, clip.sourceOutMs, tiles)
        check("a flat ${rate}x clip's tiles moved: $onClock vs $oldWay", onClock == oldWay)
    }

    // --- A curve: each tile is the moment under its own centre. --------------
    val clip = shot(SpeedRamp.preset(RampShape.BulletTime, 8_000L))
    val played = clip.durationMs
    check("a Bullet curve did not stretch the clip (played $played)", played > 15_000L)
    val onClock = FilmstripPlan.tileTimes(0L, played, tiles) { clip.sourceAt(it) }
    onClock.forEachIndexed { i, at ->
        val centre = played * (2 * i + 1) / (2L * tiles)
        val wanted = FilmstripPlan.quantize(clip.sourceAt(centre))
        check("tile $i asked for $at where the moment under it is $wanted", at == wanted)
    }

    // --- And the old split really was far off, so this is not a tautology. ---
    val oldWay = FilmstripPlan.tileTimes(clip.sourceAt(0L), clip.sourceAt(played), tiles)
    val worst = (0 until tiles).maxOf { kotlin.math.abs(oldWay[it] - onClock[it]) }
    check("the equal-source split was only ${worst}ms out, so this case proves nothing", worst >= 800L)

    // --- Tiles run forwards and stay inside the clip's own window. -----------
    check("the tiles are not in order: $onClock", onClock == onClock.sorted())
    check(
        "a tile is outside the clip's window: $onClock",
        onClock.all { it >= clip.sourceInMs && it <= clip.sourceOutMs }
    )

    // --- A window onto part of the clip samples only that part. --------------
    //
    // The box is a window: the strip draws the stretch on screen, not the whole
    // clip, so a clip scrolled half off asks only about the half that shows.
    val half = FilmstripPlan.tileTimes(played / 2, played, tiles) { clip.sourceAt(it) }
    check(
        "a half window reached back before its start: $half",
        half.all { it >= FilmstripPlan.quantize(clip.sourceAt(played / 2)) - FilmstripPlan.QUANTUM_MS }
    )

    // --- Degenerate: no width, no tiles, a window of nothing. ----------------
    check("no tiles gave times", FilmstripPlan.tileTimes(0L, played, 0) { clip.sourceAt(it) }.isEmpty())
    val still = FilmstripPlan.tileTimes(1_000L, 1_000L, 3) { clip.sourceAt(it) }
    check("a window of nothing did not give one moment three times: $still", still.distinct().size == 1)
}

private fun shot(ramp: SpeedRamp) = Clip(
    kind = ClipKind.Video, label = "shot", uri = null,
    sourceInMs = 0, sourceOutMs = 8_000, timelineStartMs = 0, sourceDurationMs = 8_000,
    speedRamp = ramp
)
