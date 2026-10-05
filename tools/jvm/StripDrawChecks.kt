import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.StripDraw
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * The waveform and the beat dots on one sound clip, executed.
 *
 * The fault: the waveform's bars are laid evenly across the clip's *drawn*
 * width - timeline time, since the box is as wide as the clip plays - while the
 * peak each bar showed was picked evenly across the clip's *source* span. Those
 * are the same mapping only at a flat rate. The beat dots drawn on top of the
 * same wave, twelve lines below in the same composable, have always gone
 * through Clip.timelineAtSource, the true inverse of the curve - so on a song
 * with a speed curve the dots sat on the transients they were found on and the
 * wave under them did not.
 *
 * So the check is the agreement between the two, which is the thing the user
 * sees: the moment a bar draws, carried back onto the timeline, must land on
 * that bar.
 */

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun song(ramp: SpeedRamp, srcOut: Long = 20_000L) = Clip(
    kind = ClipKind.Audio, label = "song", uri = null,
    sourceInMs = 0, sourceOutMs = srcOut, timelineStartMs = 0, sourceDurationMs = srcOut,
    speedRamp = ramp
)

fun main() {
    val ramps = listOf(
        "flat 1x" to SpeedRamp.flat(1f),
        "flat 2x" to SpeedRamp.flat(2f),
        "flat 0.5x" to SpeedRamp.flat(0.5f),
        "a dip to 0.5x" to SpeedRamp(listOf(SpeedPoint(0L, 1f), SpeedPoint(10_000L, 0.5f), SpeedPoint(20_000L, 1f))),
        "Bullet" to SpeedRamp.preset(RampShape.BulletTime, 20_000L),
        "Hero" to SpeedRamp.preset(RampShape.Hero, 20_000L)
    )

    // ---- A bar's moment, carried back, lands on that bar. ------------------
    //
    // 60 bars is a 180 dp clip at 3 dp a bar - an ordinary sound on the strip.
    ramps.forEach { (name, ramp) ->
        val clip = song(ramp)
        val played = clip.durationMs
        val bars = 60
        for (b in 0 until bars) {
            val at = StripDraw.barSourceMs(b, bars, 0L, played) { clip.sourceAt(it) }
            // Where a beat at that moment would be drawn - the dots' own path.
            val back = clip.timelineAtSource(at)
            val barFrom = played * b / bars
            val barTo = played * (b + 1) / bars
            check(
                back >= barFrom - 1 && back <= barTo + 1,
                "$name: bar $b draws source $at, which the beat dots would put at $back - outside $barFrom..$barTo"
            )
        }
    }

    // ---- And the old, even-in-source split really was far out. -------------
    //
    // Otherwise the case above proves nothing: a flat clip passes either way.
    run {
        val clip = song(SpeedRamp(listOf(SpeedPoint(0L, 1f), SpeedPoint(10_000L, 0.5f), SpeedPoint(20_000L, 1f))))
        val played = clip.durationMs
        check(played in 27_000L..28_500L, "the dipped song plays $played, not about 27.7 s")
        val bars = 60
        val from = clip.sourceAt(0L)
        val to = clip.sourceAt(played)
        var worst = 0L
        var worstBar = -1
        for (b in 0 until bars) {
            val oldWay = (from + (to - from) * (2L * b + 1) / (2L * bars))
            val now = StripDraw.barSourceMs(b, bars, 0L, played) { clip.sourceAt(it) }
            if (abs(oldWay - now) > worst) { worst = abs(oldWay - now); worstBar = b }
        }
        check(worst >= 800L, "the even-in-source split was only ${worst}ms out at bar $worstBar - no case here")
    }

    // ---- A flat clip is untouched: the two splits agree exactly. -----------
    listOf(1f, 2f, 0.5f, 4f).forEach { rate ->
        val clip = song(SpeedRamp.flat(rate))
        val played = clip.durationMs
        val bars = 37
        val from = clip.sourceAt(0L)
        val to = clip.sourceAt(played)
        for (b in 0 until bars) {
            val oldWay = from + (to - from) * (2L * b + 1) / (2L * bars)
            val now = StripDraw.barSourceMs(b, bars, 0L, played) { clip.sourceAt(it) }
            // Within the integer rounding: the new path rounds the timeline
            // position and then multiplies by the rate, so the two can part by
            // about `rate` milliseconds and no more.
            val slack = rate.toLong() + 1L
            check(abs(oldWay - now) <= slack, "a flat ${rate}x clip's bar $b moved from $oldWay to $now")
        }
    }

    // ---- A window onto part of the clip reads only that part. --------------
    run {
        val clip = song(SpeedRamp.preset(RampShape.BulletTime, 20_000L))
        val played = clip.durationMs
        val first = StripDraw.barSourceMs(0, 40, played / 2, played) { clip.sourceAt(it) }
        check(first >= clip.sourceAt(played / 2), "a half window reached back before its start: $first")
        val last = StripDraw.barSourceMs(39, 40, played / 2, played) { clip.sourceAt(it) }
        check(last <= clip.sourceOutMs, "a half window ran past the clip's out-point: $last")
        check(first < last, "the bars of a half window are not in order")
    }

    // ---- The peak index stays inside the list. -----------------------------
    run {
        check(StripDraw.peakIndex(0L, 20_000L, 400) == 0, "the first moment is not the first peak")
        check(StripDraw.peakIndex(20_000L, 20_000L, 400) == 399, "the last moment ran past the last peak")
        check(StripDraw.peakIndex(25_000L, 20_000L, 400) == 399, "a moment past the end ran off the list")
        check(StripDraw.peakIndex(-5L, 20_000L, 400) == 0, "a moment before the start ran off the list")
        check(StripDraw.peakIndex(10_000L, 20_000L, 400) == 200, "the middle is not the middle")
        check(StripDraw.peakIndex(1_000L, 0L, 400) == 0, "a file of no length gave an index")
        check(StripDraw.peakIndex(1_000L, 20_000L, 0) == 0, "an empty waveform gave an index")
    }

    // ---- Degenerate bars. --------------------------------------------------
    run {
        val clip = song(SpeedRamp.flat(1f))
        check(
            StripDraw.barSourceMs(0, 0, 500L, 9_000L) { clip.sourceAt(it) } == clip.sourceAt(500L),
            "no bars did not fall back to the window's start"
        )
        check(
            StripDraw.barSourceMs(0, 4, 500L, 500L) { clip.sourceAt(it) } == clip.sourceAt(500L),
            "a window of nothing did not read its one moment"
        )
    }

    if (problems.isEmpty()) {
        println("StripDrawChecks: the wave and the beat dots agree on where a moment is, on any curve")
    } else {
        println("FAIL (${problems.size})")
        problems.take(20).forEach { println("  - $it") }
        exitProcess(1)
    }
}
