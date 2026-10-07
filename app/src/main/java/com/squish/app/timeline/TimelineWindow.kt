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
         * How far across the strip the playhead is allowed to get before the
         * film starts moving under it instead.
         *
         * **Read that again, because it is not what this number used to mean.**
         * It was "where the line sits, always", and the window was built so that
         * the playhead was at a quarter at *every* moment - including 0:00,
         * where that means scrolling a quarter of a screen *before the start of
         * the edit*. Two things followed, and the person who uses this reported
         * both, twice:
         *
         *  - "the new video added is starting after wasting space at start" -
         *    a quarter of the strip empty, every time, with the clip pushed off
         *    the left edge;
         *  - "the play head is still not movable" - of course it was not. It was
         *    nailed to a quarter by construction. Dragging moved the film and
         *    the line stayed put, which is not what anyone means by moving the
         *    playhead.
         *
         * So the window is clamped at zero now ([linedOn]). Before the playhead
         * reaches this fraction the strip is **left-aligned** - the edit starts
         * at the left edge, nothing is wasted - and the playhead walks right
         * across it. From there on the playhead holds at this fraction and the
         * film scrolls under it, which is what keeps the rest of the edit in
         * front of you on a long timeline.
         *
         * The inversion that started all of this - drag right, playhead goes
         * left - is *not* fixed by nailing the line down. It was the drag's
         * sign: the strip subtracted the finger's travel from the time, so the
         * film followed the finger and time ran backwards against it. It adds
         * now (see TimelineEditor's `scrollable`), so a finger to the right is
         * always time forward, whether the strip can scroll or not. One meaning,
         * everywhere, which is what the old comment here was reaching for and
         * got at the cost of both complaints above.
         */
        const val PLAYHEAD_FRACTION = 0.25f

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
         * The strip's window: [atMs] under the playhead line, **or the start of
         * the edit against the left edge, whichever leaves nothing wasted**.
         *
         * The clamp is the whole fix for "the new video added is starting after
         * wasting space at start". Without it the scroll at 0:00 is a quarter of
         * a screen *before* zero, so the strip opens with a quarter of itself
         * empty and the clip pushed off the left edge - and the playhead can
         * never move, because it is placed by construction at a fraction that
         * does not depend on the time.
         *
         * With it, the first quarter-screen of an edit is drawn from the left
         * edge and the playhead walks across it; after that the playhead holds
         * and the film moves. Both halves map time to x monotonically, which is
         * the property that makes a drag mean one thing (WindowChecks executes
         * it over every zoom, viewport and moment).
         */
        fun linedOn(atMs: Double, pixelsPerSecond: Float, density: Float, viewportPx: Int): TimelineWindow {
            val lined = scrolledSoThat(atMs, viewportPx * PLAYHEAD_FRACTION, pixelsPerSecond, density, viewportPx)
            return if (lined.scrollMs > 0.0) lined else lined.copy(scrollMs = 0.0)
        }
    }
}

/** The strip as drawn: see [TimelineWindow.slideFor]. */
data class Slide(val drawn: TimelineWindow, val padPx: Int, val shiftPx: Float)
