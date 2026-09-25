package com.squish.app.timeline

/**
 * How often to put a mark on the ruler.
 *
 * Once held the width budget that kept a long timeline from overflowing Compose's
 * layout limits. That budget is gone: the strip is windowed now, so only a
 * screenful is ever laid out and no length of video can overflow anything. What
 * remains is the one thing that still scales with the length of the edit rather
 * than the size of the screen - how many ticks a ruler asks for.
 */
object TimelineSpan {

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
     * can show without labels colliding; the count is what fits.
     *
     * [visibleMs] is how much footage is on screen, not how long the video is.
     * Those were the same thing when the whole strip was laid out at once; they
     * are not now, and using the length would give a three-hour video a two-hour
     * tick step even zoomed in to a single frame.
     */
    fun rulerStepMs(visibleMs: Long, readableStepMs: Long): Long {
        val total = visibleMs.coerceAtLeast(1L)
        val affordable = (total + MAX_TICKS - 1) / MAX_TICKS
        val wanted = maxOf(readableStepMs, affordable)
        return STEPS_MS.firstOrNull { it >= wanted } ?: STEPS_MS.last()
    }

    /** How many ticks [rulerStepMs] will actually produce over a visible span. */
    fun tickCount(visibleMs: Long, stepMs: Long): Int {
        if (stepMs <= 0L) return 0
        return (visibleMs.coerceAtLeast(0L) / stepMs).toInt() + 1
    }
}
