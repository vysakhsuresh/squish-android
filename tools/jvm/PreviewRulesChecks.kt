import com.squish.app.editor.PreviewRules
import com.squish.app.editor.ScrubDetector
import com.squish.app.editor.StallWatch

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

/** Frames of 30 fps footage, in microseconds, the way a decoder stamps them. */
private fun framePtsUs(i: Int): Long = i * 1_000_000L / 30

/** What an exact seek to [ms] shows: the first frame at or after it. */
private fun frameShownBySeek(ms: Long): Int {
    var i = 0
    while (framePtsUs(i) < ms * 1_000L) i++
    return i
}

fun main() {
    // ---- Parking on the right frame ------------------------------------------
    // A clip trimmed to end exactly on frame 90 (3000 ms): frames 0..89 are its own.
    val sourceIn = 1_000L
    val sourceOut = 3_000L
    val outFrame = frameShownBySeek(sourceOut)
    check("the out point sits on a frame", framePtsUs(outFrame) == sourceOut * 1_000L)
    // The old end-of-edit seek, sourceOut - 1: shows the first trimmed-away frame.
    check("the old seek to out-1 showed the hidden frame", frameShownBySeek(sourceOut - 1) == outFrame)
    val parked = PreviewRules.seekTarget(sourceOut - 1, sourceIn, sourceOut)
    check("the end parks inside the trim", frameShownBySeek(parked) < outFrame)
    check("the end parks on one of the last frames", outFrame - frameShownBySeek(parked) <= 2)
    // 24 fps and 60 fps: still inside, never outside.
    for (fps in intArrayOf(24, 25, 30, 50, 60)) {
        val out = 4_000L
        val target = PreviewRules.seekTarget(out, 0L, out)
        val firstAtOrAfter = (target * fps + 999) / 1_000   // frame index of first pts >= target
        check("$fps fps: the end frame is inside the trim", firstAtOrAfter * 1_000 / fps < out)
    }
    check("a seek is never before the in point", PreviewRules.seekTarget(0L, sourceIn, sourceOut) == sourceIn)
    check("a seek inside the clip is untouched", PreviewRules.seekTarget(2_000L, sourceIn, sourceOut) == 2_000L)
    check("a clip shorter than the backoff parks on its in point",
        PreviewRules.seekTarget(1_030L, sourceIn, 1_040L) == sourceIn)

    // ---- Redraw never drifts ---------------------------------------------------
    // The old redraw: currentPosition - 1, every time. 120 redraws in a drag.
    var old = 2_000L
    repeat(120) { old -= 1 }
    check("the old redraw walked 120 ms", old == 1_880L)
    // The new one, from the true target, with the player wherever the last left it.
    var position = 2_000L
    val target = 2_000L
    repeat(120) {
        position = PreviewRules.redrawTarget(target, position, sourceIn, sourceOut)
        check("a redraw stays within a millisecond of the target", kotlin.math.abs(position - target) <= 1)
    }
    // Every redraw is a real seek: never to where the player already is.
    var p = target
    repeat(10) {
        val next = PreviewRules.redrawTarget(target, p, sourceIn, sourceOut)
        check("a redraw is never a no-op seek", next != p)
        p = next
    }
    // Parked on the in point: the nudge goes forward, never before the trim.
    check("a redraw on the in point does not go before it",
        PreviewRules.redrawTarget(sourceIn, sourceIn, sourceIn, sourceOut) >= sourceIn)
    // Parked on the last millisecond: the nudge goes back, never past the out point.
    check("a redraw at the end stays inside",
        PreviewRules.redrawTarget(sourceOut - 1, sourceOut - 1, sourceIn, sourceOut) < sourceOut)

    // ---- Speed pushes through a ramp -------------------------------------------
    // 1x to 0.25x across 2 s of output, ticked at 33 ms.
    var applied: Float? = null
    var lastPush = -10_000L
    var pushes = 0
    var clip = "a"
    var t = 0L
    while (t <= 2_000L) {
        val wanted = 1f - 0.75f * (t / 2_000f)
        if (PreviewRules.shouldPushSpeed(applied, wanted, t - lastPush, clipChanged = applied == null)) {
            applied = wanted
            lastPush = t
            pushes++
        }
        t += 33
    }
    // Settle: the ramp has stopped at 0.25; the last push must reach it.
    while (t <= 3_000L) {
        if (PreviewRules.shouldPushSpeed(applied, 0.25f, t - lastPush, clipChanged = false)) {
            applied = 0.25f
            lastPush = t
            pushes++
        }
        t += 33
    }
    println("a 2 s ramp from 1x to 0.25x: $pushes rate changes (the old guard made ${2_000 / 33 + 1})")
    check("a ramp pushes at most ~7 times a second", pushes <= 16)
    check("a ramp still follows the curve", pushes >= 5)
    check("the ramp arrives at its final rate", applied == 0.25f)
    check("a new clip is pushed at once", PreviewRules.shouldPushSpeed(1f, 1f, 0L, clipChanged = true))
    check("a flat rate is not pushed again", !PreviewRules.shouldPushSpeed(1f, 1f, 10_000L, clipChanged = false))
    clip = "b"
    check("a hard cut to 4x is not delayed", PreviewRules.shouldPushSpeed(1f, 4f, 1L, clipChanged = clip == "b"))

    // ---- Lookahead ------------------------------------------------------------
    val starts = listOf(0L, 5_200L, 9_000L)
    check("nothing parked 2 s out", PreviewRules.upcoming(starts, 3_000L, 1_500L) == -1)
    check("the photo at 5.2 s is parked 1 s out", PreviewRules.upcoming(starts, 4_200L, 1_500L) == 1)
    check("the clip under the playhead is not 'upcoming'", PreviewRules.upcoming(starts, 5_200L, 1_500L) == -1)
    check("the one after next is found in its turn", PreviewRules.upcoming(starts, 8_000L, 1_500L) == 2)
    check("an empty roll has nothing coming", PreviewRules.upcoming(emptyList(), 0L, 1_500L) == -1)

    // ---- Stalls ---------------------------------------------------------------
    // A slow exact seek that needs 3 s and keeps loading for the first 1.5 s: the
    // old rule reloaded it at 2 s and every 2 s after, forever.
    val watch = StallWatch()
    var reloads = 0
    var buffered = 0L
    var now = 1_000L
    var sinceReload = 0L
    var landed = false
    while (now < 60_000L && !landed) {
        if (sinceReload < 1_500L) buffered += 33
        if (sinceReload >= 3_000L) {
            landed = true
            watch.healthy()
        } else if (watch.stuck(now, buffered)) {
            reloads++
            sinceReload = 0L
            continue
        }
        now += 33
        sinceReload += 33
    }
    println("a 3 s seek: lands after $reloads reload(s)")
    check("a slow seek is given the time it needs", landed)
    check("a slow seek is reloaded at most once", reloads <= 1)
    // A player that is truly stuck is reloaded, with growing waits.
    val dead = StallWatch()
    now = 0L
    val reloadTimes = mutableListOf<Long>()
    while (now < 40_000L) {
        if (dead.stuck(now, 500L)) reloadTimes.add(now)
        now += 33
    }
    check("a dead player is reloaded", reloadTimes.isNotEmpty())
    check("the first reload comes within about 2 s", reloadTimes.first() in 1_900L..2_200L)
    val gaps = reloadTimes.zipWithNext { a, b -> b - a }
    check("each wait is longer than the last until the cap", gaps.zipWithNext().all { (a, b) -> b >= a })
    check("no reload storm: fewer than 6 reloads in 40 s", reloadTimes.size < 6)

    // ---- Scrubs ---------------------------------------------------------------
    val scrub = ScrubDetector()
    check("a single jump is not a scrub", !scrub.onSeek(10_000L))
    check("and needs no settling", !scrub.settled(10_500L))
    var inScrub = false
    for (i in 1..20) inScrub = scrub.onSeek(20_000L + i * 16L)
    check("a stream of seeks is a scrub", inScrub)
    check("a scrub is not settled while moving", !scrub.settled(20_000L + 20 * 16L + 50L))
    check("a scrub settles once it goes quiet", scrub.settled(20_000L + 20 * 16L + 250L))
    check("and only once", !scrub.settled(20_000L + 20 * 16L + 400L))
    scrub.onSeek(30_000L); scrub.onSeek(30_010L)
    scrub.end()
    check("a lifted finger settles at once", scrub.settled(30_011L))

    if (failures.isEmpty()) {
        println("PASS - the preview parks inside the trim, redraws without drifting, rattles no ramp, and never reloads a slow seek forever")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
