package com.squish.app.timeline

/**
 * How wide the strip is allowed to get, and what zoom that leaves.
 *
 * Compose lays out in pixels held in a packed long, and any dimension at or above
 * 262,143 is refused outright - `Constraints` throws rather than clamping. The
 * timeline is one very wide row, so its width is seconds times zoom times screen
 * density, and nothing in that product knew about the ceiling.
 *
 * A three-hour import at the default forty-two pixels a second on a two-times
 * screen asks for nine hundred thousand pixels. That is not a slow layout or a
 * memory problem - it is an immediate exception on the first frame the editor
 * composes, which is why the app died on import however much memory the device
 * had and whatever the earlier frame-decoding fixes did.
 *
 * Kept here, apart from the composable, because it is arithmetic and arithmetic
 * can be executed. The check suite runs the real numbers for everything from a
 * ten-second clip to an eight-hour one across the densities Android ships.
 */
object TimelineSpan {

    /** The value Compose refuses at. Taken from `Constraints`, not guessed. */
    const val HARD_LIMIT_PX = 262_143

    /**
     * What the strip may actually use.
     *
     * Short of the ceiling on purpose. The ceiling is where it throws; a budget
     * that sat exactly on it would depend on every future caller rounding the
     * same way this one does.
     */
    const val WIDTH_BUDGET_PX = 240_000

    /**
     * The most zoom this timeline can carry without overflowing the budget.
     *
     * [tailDp] is the empty run past the end of the edit, which is part of the
     * laid-out width and so part of what has to fit.
     */
    fun maxPixelsPerSecond(durationMs: Long, density: Float, tailDp: Float): Float {
        if (density <= 0f) return Float.MAX_VALUE
        val seconds = (durationMs / 1000f)
        if (seconds <= 0f) return Float.MAX_VALUE
        val budgetDp = WIDTH_BUDGET_PX / density - tailDp
        if (budgetDp <= 0f) return 0f
        return budgetDp / seconds
    }

    /**
     * The zoom to actually lay out at: what was asked for, or as much of it as
     * fits, but never below [floor] - a strip zoomed to nothing is no more use
     * than one that crashes, and at that point the tail is what to give up.
     */
    fun safePixelsPerSecond(
        requested: Float,
        durationMs: Long,
        density: Float,
        tailDp: Float,
        floor: Float
    ): Float {
        val ceiling = maxPixelsPerSecond(durationMs, density, tailDp)
        if (requested <= ceiling) return requested
        return maxOf(ceiling, floor)
    }

    /**
     * The width the strip will be laid out at, in dp, clamped so it can never be
     * refused - including when [floor] above had to win.
     */
    fun contentWidthDp(durationMs: Long, pixelsPerSecond: Float, density: Float, tailDp: Float): Float {
        val seconds = (durationMs / 1000f).coerceAtLeast(0f)
        val wanted = seconds * pixelsPerSecond + tailDp
        if (density <= 0f) return wanted
        return wanted.coerceAtMost(WIDTH_BUDGET_PX / density)
    }

    /**
     * The most ruler ticks worth laying out.
     *
     * Every tick is a column with a line and a piece of text in it, composed
     * whether or not it is on screen. A three-hour timeline stepped every ten
     * seconds is a thousand of them; stepped every second it is ten thousand, and
     * that is enough to make an import feel like a hang and enough, on a modest
     * phone, to be the thing that finally gets the app killed.
     */
    const val MAX_TICKS = 140

    /**
     * Steps a ruler is allowed to use, in milliseconds.
     *
     * Only intervals a person reads without arithmetic: seconds, then the
     * half-minute and minute marks, then quarter-hours and hours. A step of
     * "every 37 seconds" would fit the width perfectly and be useless.
     */
    private val STEPS_MS = longArrayOf(
        1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
        60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
        60 * 60_000, 2 * 60 * 60_000, 6 * 60 * 60_000
    )

    /**
     * How often to put a mark on the ruler.
     *
     * Two demands at once, and the coarser wins. [readableStepMs] is what the zoom
     * can show without labels colliding; the count is what the timeline's length
     * can afford. A short clip is governed by the first, a long one by the second.
     */
    fun rulerStepMs(durationMs: Long, readableStepMs: Long): Long {
        val total = durationMs.coerceAtLeast(1L)
        val affordable = (total + MAX_TICKS - 1) / MAX_TICKS
        val wanted = maxOf(readableStepMs, affordable)
        return STEPS_MS.firstOrNull { it >= wanted } ?: STEPS_MS.last()
    }

    /** How many ticks [rulerStepMs] will actually produce. */
    fun tickCount(durationMs: Long, stepMs: Long): Int {
        if (stepMs <= 0L) return 0
        return (durationMs.coerceAtLeast(0L) / stepMs).toInt() + 1
    }
}
