import com.squish.app.timeline.TimelineSpan

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

/** Every density Android ships, plus the two the cheap phones actually use. */
private val densities = floatArrayOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)

/** Ten seconds to eight hours. The three-hour import is the one that crashed. */
private val durations = longArrayOf(
    10_000, 60_000, 10 * 60_000, 30 * 60_000, 60 * 60_000,
    3 * 60 * 60_000, 8 * 60 * 60_000
)

private const val TAIL_DP = 240f
private const val MIN_PPS = 0.05f
private const val MAX_PPS = 400f

fun main() {
    // The whole point: whatever is asked for, at whatever length, on whatever
    // screen, the laid-out width is something Compose will accept. One failure
    // here is one crash on import.
    var worst = 0
    for (density in densities) {
        for (durationMs in durations) {
            for (requested in floatArrayOf(MIN_PPS, 0.5f, 2f, 12f, 42f, 120f, MAX_PPS)) {
                val pps = TimelineSpan.safePixelsPerSecond(
                    requested, durationMs, density, TAIL_DP, MIN_PPS
                )
                val widthDp = TimelineSpan.contentWidthDp(durationMs, pps, density, TAIL_DP)
                val px = (widthDp * density).toInt()
                worst = maxOf(worst, px)
                check(
                    "%.2fx %dms @%.0f -> %d px".format(density, durationMs, requested, px),
                    px < TimelineSpan.HARD_LIMIT_PX
                )
            }
        }
    }

    // The unclamped arithmetic really does overflow, or the guard is guarding
    // nothing. Three hours, default zoom, an ordinary screen.
    val threeHours = 3L * 60 * 60_000
    val naivePx = ((threeHours / 1000f) * 42f + TAIL_DP) * 2f
    check("three hours at the default zoom would have overflowed", naivePx > TimelineSpan.HARD_LIMIT_PX)

    // Nothing is taken away from a length that always fitted. A ten-minute clip
    // at the default zoom is nowhere near the budget and must be untouched.
    check(
        "a ten-minute clip keeps the zoom it asked for",
        TimelineSpan.safePixelsPerSecond(42f, 10 * 60_000, 2f, TAIL_DP, MIN_PPS) == 42f
    )
    check(
        "a ten-second clip keeps the deepest zoom",
        TimelineSpan.safePixelsPerSecond(MAX_PPS, 10_000, 3f, TAIL_DP, MIN_PPS) == MAX_PPS
    )

    // A long one gives up zoom rather than correctness, and still gets some.
    val longPps = TimelineSpan.safePixelsPerSecond(MAX_PPS, threeHours, 2f, TAIL_DP, MIN_PPS)
    check("three hours is capped below what was asked", longPps < MAX_PPS)
    check("three hours still gets a usable zoom", longPps >= MIN_PPS)

    // The floor wins over the budget when they disagree, and the width clamp is
    // what keeps that safe. An eight-hour file on a 4x screen is that case.
    val absurd = TimelineSpan.safePixelsPerSecond(MAX_PPS, 8 * 60 * 60_000, 4f, TAIL_DP, MIN_PPS)
    check("the floor is never breached", absurd >= MIN_PPS)
    val absurdPx = TimelineSpan.contentWidthDp(8 * 60 * 60_000, absurd, 4f, TAIL_DP) * 4f
    check("and the width is clamped anyway", absurdPx < TimelineSpan.HARD_LIMIT_PX)

    // Degenerate inputs must not produce a NaN or a negative width, either of
    // which reaches Compose as something far more confusing than a crash.
    check("a zero-length timeline has a finite ceiling", !TimelineSpan.maxPixelsPerSecond(0, 2f, TAIL_DP).isNaN())
    val zero = TimelineSpan.contentWidthDp(0, 42f, 2f, TAIL_DP)
    check("a zero-length timeline still has its tail", zero == TAIL_DP)
    check("a negative length does not go negative", TimelineSpan.contentWidthDp(-5000, 42f, 2f, TAIL_DP) >= 0f)

    // The ruler composes one column per tick whether or not it is on screen, so
    // the count has to be bounded by the timeline's length, not only by the zoom.
    var worstTicks = 0
    for (durationMs in durations) {
        for (readable in longArrayOf(1_000, 2_000, 5_000, 10_000)) {
            val step = TimelineSpan.rulerStepMs(durationMs, readable)
            val ticks = TimelineSpan.tickCount(durationMs, step)
            worstTicks = maxOf(worstTicks, ticks)
            check(
                "$durationMs ms stepped every $step ms -> $ticks ticks",
                ticks <= TimelineSpan.MAX_TICKS
            )
            check("the step never goes below what the zoom can read", step >= readable)
        }
    }
    // Three hours used to be a thousand and eighty label composables at the
    // coarsest step, and ten thousand eight hundred at the finest.
    check(
        "three hours no longer emits a thousand labels",
        TimelineSpan.tickCount(threeHours, TimelineSpan.rulerStepMs(threeHours, 1_000)) <= TimelineSpan.MAX_TICKS
    )
    // A short clip is still stepped by what the eye can read, not by the cap.
    check(
        "a one-minute clip still ticks every second",
        TimelineSpan.rulerStepMs(60_000, 1_000) == 1_000L
    )

    println("widest laid-out strip across the sweep: $worst px (ceiling ${TimelineSpan.HARD_LIMIT_PX})")
    println("most ruler ticks across the sweep: $worstTicks (cap ${TimelineSpan.MAX_TICKS})")
    if (failures.isEmpty()) {
        println("PASS - the strip always lays out at a width Compose accepts")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
