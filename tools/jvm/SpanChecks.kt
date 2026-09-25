import com.squish.app.timeline.TimelineSpan

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}



fun main() {
    // The ruler composes one column per tick, so the count has to be bounded -
    // and bounded by what is on screen, since that is all that is built.
    var worstTicks = 0
    for (visibleMs in longArrayOf(200, 2_000, 10_000, 60_000, 10 * 60_000, 3 * 60 * 60_000)) {
        for (readable in longArrayOf(1_000, 2_000, 5_000, 10_000)) {
            val step = TimelineSpan.rulerStepMs(visibleMs, readable)
            val ticks = TimelineSpan.tickCount(visibleMs, step)
            worstTicks = maxOf(worstTicks, ticks)
            check("${visibleMs}ms visible, stepped every ${step}ms -> $ticks ticks", ticks <= TimelineSpan.MAX_TICKS)
            check("the step never goes below what the zoom can read", step >= readable)
        }
    }

    // A long video zoomed right in is marked as finely as a short one. This is
    // the thing that windowing bought and that the old length-based step lost:
    // three seconds on screen is three seconds on screen, whatever the file.
    check(
        "three seconds on screen ticks every second, whatever the video's length",
        TimelineSpan.rulerStepMs(3_000, 1_000) == 1_000L
    )
    check(
        "a whole hour on screen does not tick every second",
        TimelineSpan.rulerStepMs(60 * 60_000, 1_000) > 10_000L
    )

    // Only intervals a person reads without arithmetic.
    val readable = setOf(
        1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L,
        60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L,
        3_600_000L, 7_200_000L, 21_600_000L
    )
    for (visibleMs in longArrayOf(500, 7_000, 45_000, 400_000, 5_000_000)) {
        val step = TimelineSpan.rulerStepMs(visibleMs, 1_000)
        check("${visibleMs}ms visible gets a round step ($step)", step in readable)
    }

    check("a zero span does not divide by zero", TimelineSpan.tickCount(0, TimelineSpan.rulerStepMs(0, 1_000)) >= 0)
    check("a zero step yields no ticks", TimelineSpan.tickCount(10_000, 0) == 0)

    println("most ruler ticks across the sweep: $worstTicks (cap ${TimelineSpan.MAX_TICKS})")
    if (failures.isEmpty()) {
        println("PASS - the ruler is marked as finely as the zoom allows and never more often than it can draw")
    } else {
        failures.take(10).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
