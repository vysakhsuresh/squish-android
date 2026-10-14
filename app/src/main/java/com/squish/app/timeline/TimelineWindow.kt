package com.squish.app.timeline

/**
 * The slice of the timeline that is actually on screen.
 *
 * The strip used to be laid out whole: one row as wide as the entire edit, inside
 * a scroll container. That is the obvious way to build it and it does not scale.
 * Compose refuses any dimension of 262,143 pixels or more, so a long enough video
 * at a deep enough zoom simply could not be laid out - a three-hour import asked
 * for nine hundred thousand and threw on the first frame. Budgeting the zoom kept
 * it alive but bought that safety with precision: three hours could only be shown
 * at about eleven pixels a second, which is no use for finding a frame.
 *
 * So nothing is laid out that is not on screen. This holds where the view is and
 * how big it is, and everything that draws asks it where a moment sits and which
 * moments are worth drawing at all. Layout cost stops depending on the length of
 * the edit, and the zoom ceiling goes away: a three-hour clip zooms to the frame
 * exactly like a three-second one, because in both cases the strip is one screen
 * wide.
 *
 * Pure, and checked. Scroll offsets and time-to-pixel conversions are the kind of
 * arithmetic that is wrong by one somewhere and produces a timeline that is
 * subtly, maddeningly off - so the round trip is executed rather than trusted.
 */
data class TimelineWindow(
    /** Zoom, in screen pixels per second of footage. */
    val pixelsPerSecond: Float,
    /**
     * Where the left edge of the view is, as a moment in the edit.
     *
     * A time, not a pixel offset, and a double rather than a float. Both of those
     * were learned from the check suite. Holding the scroll in pixels means the
     * number grows with zoom and length together - eight hours at a deep zoom is
     * three hundred million pixels, and a float has about seven digits, so the
     * position could only be expressed to the nearest thirty pixels. Everything
     * would have landed near where it was asked to and nothing exactly, which is
     * the sort of fault that reads as "the editor feels loose" and never gets
     * pinned down. In time, the number is bounded by the length of the video
     * whatever the zoom, and a double has digits to spare.
     */
    val scrollMs: Double,
    /** Screen density, because the drawing side speaks dp and gestures speak pixels. */
    val density: Float,
    /** How wide the strip is on screen, in pixels. */
    val viewportPx: Int,
    /**
     * How far past the view to build, when not the default half screen: the
     * strip's drawing window (TimelineEditor's drawWindow) is already half a
     * screen wider each side than the strip, and building another half beyond
     * that stretched a long clip's filmstrip over twice the width.
     */
    val marginOverridePx: Float? = null
) {

    /**
     * How far off screen to keep drawing.
     *
     * Not zero. A clip whose edge is one pixel outside the view still has to be
     * there the moment a finger moves, and a strip that built its contents exactly
     * at the boundary would pop at every edge. Half a screen each way is cheap and
     * nothing is ever seen arriving.
     */
    private val marginPx: Float get() = marginOverridePx ?: (viewportPx / 2f).coerceAtLeast(240f)

    private val pxPerMs: Double get() = pixelsPerSecond.toDouble() * density / 1000.0

    /** Where a moment sits on screen, in pixels from the left edge of the strip. */
    fun xPx(atMs: Long): Float = ((atMs - scrollMs) * pxPerMs).toFloat()

    /** Where a moment sits on screen, in dp, which is what layout offsets take. */
    fun xDp(atMs: Long): Float = if (density <= 0f) 0f else xPx(atMs) / density

    /** How wide a span of time is, in dp. Independent of where the view is. */
    fun widthDp(durationMs: Long): Float =
        if (density <= 0f) 0f else (durationMs * pxPerMs / density).toFloat()

    /** How much footage a distance on screen is worth. */
    fun msForPx(px: Float): Double = if (pxPerMs <= 0.0) 0.0 else px / pxPerMs

    /** The same, for a distance measured in dp. */
    fun msForDp(dp: Float): Double = msForPx(dp * density)

    /** How far apart on screen two moments that far apart would be. */
    fun pxForMs(ms: Double): Float = (ms * pxPerMs).toFloat()

    /** Which moment is under a point on screen. The inverse of [xPx]. */
    fun msAt(xPx: Float): Long {
        if (pxPerMs <= 0.0) return 0L
        return (scrollMs + xPx / pxPerMs).toLong().coerceAtLeast(0L)
    }

    /** The same, without the floor at zero - for measuring, not for seeking. */
    private fun rawMsAt(xPx: Float): Long {
        if (pxPerMs <= 0.0) return 0L
        return (scrollMs + xPx / pxPerMs).toLong()
    }

    /** The first moment worth drawing, which is a little before the one on screen. */
    val firstDrawnMs: Long get() = rawMsAt(-marginPx)

    /** The last moment worth drawing, a little after the right-hand edge. */
    val lastDrawnMs: Long get() = rawMsAt(viewportPx + marginPx)

    /** Whether any part of a span is close enough to the view to be worth drawing. */
    fun intersects(startMs: Long, endMs: Long): Boolean =
        endMs >= firstDrawnMs && startMs <= lastDrawnMs

    /**
     * The part of a span that is worth drawing, or null if none of it is.
     *
     * This is what keeps a single long clip from overflowing the layout on its
     * own. A three-hour clip at a deep zoom is millions of pixels wide; what is
     * drawn is the screenful of it you can see, positioned so it lines up with
     * where the whole clip would have been.
     */
    fun clampToView(startMs: Long, endMs: Long): LongRange? {
        if (endMs < startMs) return null
        if (!intersects(startMs, endMs)) return null
        val from = maxOf(startMs, firstDrawnMs)
        val to = minOf(endMs, lastDrawnMs)
        if (to < from) return null
        return from..to
    }

    /** How many milliseconds of footage the screen shows at this zoom. */
    val viewportMs: Long get() = if (pxPerMs <= 0.0) 0L else (viewportPx / pxPerMs).toLong()

    /*
     * There used to be three more here - maxScrollMs, scrolledTo and zoomedTo -
     * and nothing in the app called any of them. They are from before the
     * playhead was fixed, when the strip held a scroll of its own and clamped
     * it; now the window is built from the playhead's moment ([linedOn]) and
     * the playhead is what is clamped, to the edit, by the scrubber.
     *
     * Only WindowChecks exercised them, which is the worst shape a check can
     * have: it reads as cover for the strip's scrolling and covers nothing, and
     * their presence told whoever read this file that the scroll is bounded
     * here. It is not. A pinch holds its anchor for the same reason - the
     * anchor is the playhead, and the playhead does not move.
     */

    /**
     * Which moment is under a point on screen, unrounded and without the floor at
     * zero - for following a finger that may be left of the start of the edit.
     */
    fun exactMsAt(xPx: Float): Double = scrollMs + msForPx(xPx)

    /** The moment under the playhead line; see [PLAYHEAD_FRACTION]. */
    val lineMs: Double get() = scrollMs + msForPx(viewportPx * PLAYHEAD_FRACTION)

    /** Where the playhead line is drawn, in pixels from the strip's left edge. */
    val linePx: Float get() = viewportPx * PLAYHEAD_FRACTION

    companion object {
        /**
         * Where the playhead line sits, as a fraction of the strip's width:
         * **the middle, at every moment, 0:00 included.**
         *
         * This is the fourth setting of this number and the model behind it is
         * the one every phone editor uses - InShot, CapCut, VN, KineMaster: the
         * line is fixed in the centre, the film moves under it, and at the start
         * of the edit the left half of the strip is empty, with the first clip
         * beginning at the line. The three earlier settings and what each one
         * earned, so nobody tries them again:
         *
         *  - **a quarter, fixed**, with the strip scrolled before 0:00 at the
         *    start: "the new video added is starting after wasting space at
         *    start" and "the play head is still not movable";
         *  - **a quarter, walking** - the window clamped at 0:00 so the strip was
         *    left-aligned and the playhead walked across the first quarter before
         *    holding: the strip stood still under the first quarter of every
         *    drag and then moved for the rest of it, two gestures for one thing;
         *  - and with that, **time running with the finger** (TimelineEditor's
         *    drag *added* the travel): the playhead walking right under a finger
         *    going right was consistent with itself, but once the line held the
         *    *film* ran against the finger - "when I swipe to the left, video
         *    strip go right and vice versa", reported after the model had been
         *    reported three times already.
         *
         * What holds now, and what WindowChecks executes: the line is at the
         * centre at every moment and zoom; the film moves exactly with the
         * finger (the drag *subtracts* its travel from the time, see
         * TimelineEditor's `scrollable`) and keeps its momentum; and the start
         * of the edit is drawn at the line when the playhead is at 0:00. The
         * empty half-screen before 0:00 is not waste: it is what lets the first
         * frame sit under the playhead like every other frame.
         */
        const val PLAYHEAD_FRACTION = 0.5f

        /**
         * The window with [atMs] at [atPx] pixels from the strip's left edge.
         *
         * The one builder, because the strip is drawn from a second, wider
         * window and the two have to agree to the pixel - see [slideFor], which
         * needs its line at a place no fraction of its own width would give.
         */
        fun scrolledSoThat(
            atMs: Double,
            atPx: Float,
            pixelsPerSecond: Float,
            density: Float,
            viewportPx: Int,
            marginOverridePx: Float? = null
        ): TimelineWindow {
            val at = TimelineWindow(pixelsPerSecond, 0.0, density, viewportPx, marginOverridePx)
            return at.copy(scrollMs = atMs - at.msForPx(atPx))
        }

        /**
         * How the strip is drawn under the live window [live], centred on
         * [centreMs]: from [Slide.drawn] - the same scale, a centre that moves
         * in quarter-screen steps, half a screen wider each side and built no
         * further than that - laid [Slide.padPx] to the left and slid
         * [Slide.shiftPx] whole pixels. Between steps only the slide changes,
         * so the rows are moved on the GPU rather than rebuilt every frame.
         * A moment drawn this way lands within half a pixel of where [live]
         * puts it, which is where a finger finds it (checked in WindowChecks).
         */
        fun slideFor(live: TimelineWindow): Slide {
            val pad = live.viewportPx / 2
            val step = live.msForPx(live.viewportPx / 4f).coerceAtLeast(1.0)
            // From the live window's **own scroll**, quantised - not from a
            // moment under an assumed line position.
            //
            // It used to take the moment at `live.linePx` and place that at
            // `live.linePx + pad` in the drawn window, which is the same thing
            // only while the playhead really is at that fraction. Once [linedOn]
            // was clamped so the strip sits against the left edge at the start,
            // it is not - and the drawn strip parted from the live one by the
            // whole of the clamp, a quarter of a screen, which is every clip,
            // tile and wave drawn a quarter-screen from where a finger finds it.
            // Phrased on the scroll it is true either way, because the scroll is
            // what both windows actually mean.
            val drawnScroll = Math.round(live.scrollMs / step) * step
            val drawn = TimelineWindow(
                live.pixelsPerSecond,
                drawnScroll - live.msForPx(pad.toFloat()),
                live.density,
                live.viewportPx + 2 * pad,
                marginOverridePx = 0f
            )
            // Whole pixels: a fractional slide blurred the marks inside the rows
            // and set them up to half a pixel off the lines drawn outside them.
            val shift = Math.round(live.pxForMs(drawnScroll - live.scrollMs)).toFloat()
            return Slide(drawn, pad, shift)
        }

        /**
         * The strip's window: [atMs] under the playhead line, always.
         *
         * Not clamped at the start of the edit. It was, for a while (see
         * [PLAYHEAD_FRACTION] for what that cost), and the clamp meant the first
         * quarter of every drag moved the playhead instead of the film. With the
         * scroll free to go negative, the strip at 0:00 is drawn from half a
         * screen before the edit, the first clip starts at the line, and one
         * finger movement means one thing from the first frame to the last.
         * Nothing before zero is ever *sought* - [msAt] floors at zero, and the
         * scrubber clamps the playhead to the edit - so the empty half is only
         * ever looked at.
         */
        fun linedOn(atMs: Double, pixelsPerSecond: Float, density: Float, viewportPx: Int): TimelineWindow =
            scrolledSoThat(atMs, viewportPx * PLAYHEAD_FRACTION, pixelsPerSecond, density, viewportPx)
    }
}

/** The strip as drawn: see [TimelineWindow.slideFor]. */
data class Slide(val drawn: TimelineWindow, val padPx: Int, val shiftPx: Float)
