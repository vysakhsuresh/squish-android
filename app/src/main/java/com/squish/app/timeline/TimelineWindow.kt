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

    /** The furthest the view may be scrolled, as a moment. */
    fun maxScrollMs(durationMs: Long, tailDp: Float): Double {
        if (pxPerMs <= 0.0) return 0.0
        val tailMs = tailDp * density / pxPerMs
        return (durationMs + tailMs - viewportPx / pxPerMs).coerceAtLeast(0.0)
    }

    /** The same window, scrolled somewhere else. */
    fun scrolledTo(ms: Double, durationMs: Long, tailDp: Float): TimelineWindow =
        copy(scrollMs = ms.coerceIn(0.0, maxScrollMs(durationMs, tailDp)))

    /**
     * The same window at a new zoom, with [anchorMs] left where it was on screen.
     *
     * A zoom that keeps the left edge still throws whatever you were looking at
     * off the screen, the further along the edit the worse. Holding a chosen
     * moment in place is what makes a pinch feel like zooming rather than like
     * being thrown.
     */
    fun zoomedTo(
        newPixelsPerSecond: Float,
        anchorMs: Long,
        durationMs: Long,
        tailDp: Float
    ): TimelineWindow {
        val anchorPx = xPx(anchorMs)
        val zoomed = copy(pixelsPerSecond = newPixelsPerSecond)
        if (zoomed.pxPerMs <= 0.0) return zoomed
        return zoomed.scrolledTo(anchorMs - anchorPx / zoomed.pxPerMs, durationMs, tailDp)
    }

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
         * Where the playhead line sits across the strip, as a fraction of its
         * width. The strip moves under it; it never moves itself.
         *
         * **A quarter in, not the middle, and that is the whole of this
         * number's story.** The line has to be at a fixed place or the gesture
         * means two different things: when the strip can scroll, a finger moves
         * the film and time runs against the finger; when it cannot, a finger
         * can only move the line and time runs with it. Mixing the two is what
         * made dragging right move the playhead left near 0:00 - the first
         * thing a user ever reported here - so the line is fixed and the film
         * always follows the finger.
         *
         * Fixed in the *middle*, though, the scroll at 0:00 is half a screen
         * before the start: the edit begins half way across the strip with
         * nothing to its left, and a fit-on-open has only half the width to
         * draw the whole edit in. That is what CapCut and InShot do and it was
         * still wrong here - said plainly by the person who uses this: "it is
         * starting from mid screen not the left end which is terrible".
         *
         * A quarter costs a quarter of the strip to what has already played -
         * about two thumb-widths on a phone, enough to see and grab the cut
         * just passed - and gives the other three quarters to the edit. An
         * opened video is drawn half as big again as it was and the strip
         * scrolls half as fast again under the line, which is the other half of
         * the same complaint: at a fit-the-whole-edit zoom the strip crept
         * about six pixels a second and read as frozen.
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
        fun slideFor(live: TimelineWindow, lineMs: Double): Slide {
            val pad = live.viewportPx / 2
            val step = live.msForPx(live.viewportPx / 4f).coerceAtLeast(1.0)
            val drawnCentre = Math.round(lineMs / step) * step
            // At the *same screen place* as the live window's line, plus the pad
            // the drawn strip is laid out to the left by. Not a fraction of the
            // drawn window's own width: the drawn window is twice as wide, so a
            // quarter of it is nowhere near a quarter of the strip, and the two
            // would part by an eighth of a screen.
            val drawn = scrolledSoThat(
                drawnCentre, live.linePx + pad,
                live.pixelsPerSecond, live.density, live.viewportPx + 2 * pad, marginOverridePx = 0f
            )
            val msPerPx = live.msForPx(1f)
            // Whole pixels: a fractional slide blurred the marks inside the rows
            // and set them up to half a pixel off the lines drawn outside them.
            val shift = if (msPerPx > 0.0) Math.round((drawnCentre - lineMs) / msPerPx).toFloat() else 0f
            return Slide(drawn, pad, shift)
        }

        /** The strip's window: [atMs] under the playhead line. See [PLAYHEAD_FRACTION]. */
        fun linedOn(atMs: Double, pixelsPerSecond: Float, density: Float, viewportPx: Int): TimelineWindow =
            scrolledSoThat(atMs, viewportPx * PLAYHEAD_FRACTION, pixelsPerSecond, density, viewportPx)
    }
}

/** The strip as drawn: see [TimelineWindow.slideFor]. */
data class Slide(val drawn: TimelineWindow, val padPx: Int, val shiftPx: Float)
