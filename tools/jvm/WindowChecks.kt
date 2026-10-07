import com.squish.app.timeline.TimelineWindow
import kotlin.math.abs

private val failures = mutableListOf<String>()

private fun check(what: String, ok: Boolean) {
    if (!ok) failures.add(what)
}

private val densities = floatArrayOf(1f, 2f, 2.75f, 3f)
private val zooms = floatArrayOf(0.05f, 2f, 42f, 400f, 4_000f)
private val viewports = intArrayOf(720, 1080, 1440)

/** Ten seconds to eight hours, in milliseconds. */
private val durations = longArrayOf(10_000, 60_000, 30 * 60_000, 3 * 60 * 60_000, 8 * 60 * 60_000)


/** Compose refuses any dimension at or above this. Nothing drawn may reach it. */
private const val HARD_LIMIT_PX = 262_143

fun main() {
    var widest = 0f

    for (density in densities) {
        for (pps in zooms) {
            for (viewportPx in viewports) {
                for (durationMs in durations) {
                    // The scroll the strip is actually at when the playhead is
                    // at the end of the edit: the window is built from the
                    // playhead's moment, so this is the far end of the range it
                    // ever takes. (It used to come from maxScrollMs, which
                    // nothing in the app called - see below.)
                    val maxScroll = TimelineWindow
                        .linedOn(durationMs.toDouble(), pps, density, viewportPx)
                        .scrollMs
                        .coerceAtLeast(0.0)

                    // Three places in the scroll that behave differently: the very
                    // start, the far end, and somewhere in the middle.
                    for (scrollMs in doubleArrayOf(0.0, maxScroll / 2.0, maxScroll)) {
                        val w = TimelineWindow(pps, scrollMs, density, viewportPx)
                        val tag = "%.2fx %.2fpps %dpx %dms @%.0fms".format(density, pps, viewportPx, durationMs, scrollMs)

                        // A point on screen maps to a moment and back to the same
                        // point. Everything the strip draws rests on this, and an
                        // error of one here is a playhead that lands beside your
                        // finger rather than under it.
                        for (probe in floatArrayOf(0f, viewportPx / 3f, viewportPx.toFloat())) {
                            val ms = w.msAt(probe)
                            val back = w.xPx(ms)
                            // One millisecond of slack: msAt truncates to whole
                            // milliseconds, so the round trip is only exact to
                            // whatever a millisecond is worth in pixels.
                            val slackPx = (pps * density / 1000f).coerceAtLeast(0.001f) + 0.5f
                            check("$tag round trips at $probe", abs(back - probe) <= slackPx || ms == 0L)
                        }

                        // The whole edit, clamped to what is on screen, is never
                        // wider than a screen and its margins. This is the property
                        // that removes the zoom ceiling: what gets laid out does
                        // not depend on how long the video is.
                        val span = w.clampToView(0L, durationMs)
                        if (span != null) {
                            val widthPx = w.widthDp(span.last - span.first) * density
                            widest = maxOf(widest, widthPx)
                            check("$tag draws a laying-out-able width", widthPx < HARD_LIMIT_PX)
                            check("$tag draws no more than two screens", widthPx <= viewportPx * 2.5f + 1f)
                        }

                        // Whatever is on screen is inside what is drawn. If this
                        // fails, something visible was skipped - a clip that
                        // vanishes as you scroll to it.
                        check("$tag draws at least what is visible", w.firstDrawnMs <= w.msAt(0f))
                        check("$tag draws past the right edge", w.lastDrawnMs >= w.msAt(viewportPx.toFloat()))
                    }
                }
            }
        }
    }

    // Visibility agrees with the arithmetic: a span is drawn exactly when part of
    // it lies in the drawn range.
    val w = TimelineWindow(42f, 1_000.0, 2f, 1080)
    check("a span before the view is skipped", !w.intersects(0L, w.firstDrawnMs - 1))
    check("a span after the view is skipped", !w.intersects(w.lastDrawnMs + 1, w.lastDrawnMs + 5_000))
    check("a span straddling the left edge is drawn", w.intersects(w.firstDrawnMs - 1_000, w.firstDrawnMs + 1_000))
    check("a span inside the view is drawn", w.intersects(w.msAt(10f), w.msAt(100f)))
    check("a backwards span is nothing", w.clampToView(5_000, 4_000) == null)

    // Clamping never invents time the span did not contain.
    val clipStart = 60_000L
    val clipEnd = 120_000L
    for (scrollMs in doubleArrayOf(0.0, 50_000.0, 200_000.0, 2_000_000.0)) {
        val v = TimelineWindow(42f, scrollMs, 2f, 1080)
        val span = v.clampToView(clipStart, clipEnd) ?: continue
        check(
            "clamping at $scrollMs stays inside the clip",
            span.first >= clipStart && span.last <= clipEnd
        )
        // And where it is drawn is where the unclamped clip would have been.
        check(
            "clamping at $scrollMs does not move the clip",
            abs(v.xPx(span.first) - ((span.first - scrollMs) * 42.0 * 2.0 / 1000.0).toFloat()) < 0.5f
        )
    }

    // What used to be here: three assertions about maxScrollMs, scrolledTo and
    // zoomedTo. Nothing in the app called any of them - they are from before
    // the playhead was fixed, when the strip held a scroll of its own - so the
    // assertions read as cover for the strip's scrolling and covered nothing.
    // The scroll is bounded now by the *playhead* being clamped to the edit,
    // which is the scrubber's business, and a pinch holds its anchor because
    // the anchor is the playhead and the playhead does not move. Both are
    // asserted below, on linedOn, which is what the strip actually builds.

    // Degenerate input must not produce a NaN position.
    val dead = TimelineWindow(0f, 0.0, 0f, 0)
    check("a zero window has a finite position", dead.xPx(1_000).isFinite())
    check("a zero window maps to the start", dead.msAt(100f) == 0L)

    println("widest thing drawn across the sweep: %.0f px (ceiling %d)".format(widest, HARD_LIMIT_PX))

    // A pinch leaves the moment under the playhead where it was, at any zoom.
    // Not an anchor the pinch chooses: the strip rebuilds its window from the
    // playhead at the new scale, so the moment that is held is the one under
    // the line - which is the whole of what a fixed playhead buys here.
    for (pps in floatArrayOf(2f, 42f, 400f, 4_000f)) {
        for (factor in floatArrayOf(0.25f, 0.5f, 2f, 8f)) {
            val at = 900_000.0
            val before = TimelineWindow.linedOn(at, pps, 2.75f, 1080)
            val after = TimelineWindow.linedOn(at, pps * factor, 2.75f, 1080)
            val moved = abs(after.xPx(at.toLong()) - before.xPx(at.toLong()))
            check("a pinch from $pps by $factor holds the playhead (moved $moved px)", moved < 1f)
        }
    }

    // ---- Time to screen runs one way, and the edit starts at the left edge. --
    //
    // This replaces a check that asserted the playhead is at PLAYHEAD_FRACTION
    // at *every* moment. That was true, and it was the bug: placing the line by
    // a constant means the window at 0:00 is scrolled a quarter of a screen
    // before the start of the edit, so the strip opens a quarter empty with the
    // clip pushed off the left edge, and the playhead cannot move because its
    // position does not depend on the time. Both were reported, twice:
    // "the new video added is starting after wasting space at start" and
    // "the play head is still not movable".
    //
    // What actually has to hold - and what the inversion bug was really about -
    // is that **a later moment is never drawn further left than an earlier one**.
    // That makes a drag mean one thing whether the strip can scroll or not, and
    // it is true of both halves of the clamped window: the playhead walking
    // across a left-aligned strip, and the strip scrolling under a held
    // playhead.
    for (pps in floatArrayOf(2f, 42f, 400f, 4_000f)) {
        for (viewport in intArrayOf(1080, 1079, 2400)) {
            val line = viewport * TimelineWindow.PLAYHEAD_FRACTION
            var t = 0.0
            var lastX = Float.NEGATIVE_INFINITY
            var walked = false
            var held = false
            while (t < 30_000.0) {
                val w = TimelineWindow.linedOn(t, pps, 2.75f, viewport)
                val x = w.xPx(t.toLong())

                // The one that matters: forward in time is never leftward on screen.
                check(
                    "the playhead went backwards at $t ms (pps $pps, viewport $viewport): $x after $lastX",
                    x >= lastX - 0.01f
                )
                lastX = x

                // Never past the line, and never off the left edge.
                check("the playhead ran past the line at $t ms: $x > $line", x <= line + 1f)
                check("the playhead went off the left edge at $t ms: $x", x >= -0.01f)
                // And nothing is ever drawn before the start of the edit - which
                // is the wasted space, stated as arithmetic.
                check("the strip is scrolled before 0:00 at $t ms: ${w.scrollMs}", w.scrollMs >= -1e-6)
                check("the start of the edit is off the left edge at $t ms", w.xPx(0L) <= 0.01f)

                if (x < line - 1f) walked = true else held = true
                t += if (t < 2_000.0) 10.0 else 500.0
            }
            // Both halves are real: the playhead walks at the start, and holds
            // once it has got that far - which at the shallowest zoom takes
            // longer than the 30 s swept here (at 2 px/s the line is 270 px, or
            // 49 seconds in), so the second is only asked where it is reachable.
            check("the playhead never walked (pps $pps, viewport $viewport)", walked)
            val reachableMs = TimelineWindow(pps, 0.0, 2.75f, viewport).msForPx(line)
            if (reachableMs < 29_000.0) {
                check("the playhead never reached the line (pps $pps, viewport $viewport)", held)
            }
        }
    }

    // At the very start the edit is flush with the left edge - no gap at all.
    for (pps in floatArrayOf(2f, 42f, 400f, 4_000f)) {
        for (viewport in intArrayOf(1080, 1079, 2400)) {
            val w = TimelineWindow.linedOn(0.0, pps, 2.75f, viewport)
            check("0:00 is not at the left edge (pps $pps, viewport $viewport): ${w.xPx(0L)}", abs(w.xPx(0L)) < 0.01f)
            check("the playhead is not at the left edge at 0:00", abs(w.xPx(0L)) < 0.01f)
        }
    }

    // And the line is left of the middle, leaving most of the strip to the edit.
    // The number is a judgement, so what is checked is the two things that make
    // it one: some of the strip behind the playhead, and most of it ahead.
    check(
        "the playhead is between an eighth and a third of the way across (${TimelineWindow.PLAYHEAD_FRACTION})",
        TimelineWindow.PLAYHEAD_FRACTION in 0.125f..0.34f
    )

    // The strip as drawn (slideFor): every moment within a pixel of where the
    // live window - and so a finger - puts it, at any zoom, length and centre;
    // the slide never more than an eighth of a screen and always whole pixels;
    // and the drawn window the same between steps, so the rows are not rebuilt.
    for (pps in floatArrayOf(2f, 42f, 400f, 4_000f)) {
        for (viewport in intArrayOf(1080, 1079, 2400)) {
            var last: TimelineWindow? = null
            var rebuilds = 0
            var frames = 0
            var centre = 0.0
            val live0 = TimelineWindow.linedOn(0.0, pps, 2.75f, viewport)
            val stepMs = live0.msForPx(3f)
            while (centre < 3 * 60 * 60_000.0 && frames < 4_000) {
                val live = TimelineWindow.linedOn(centre, pps, 2.75f, viewport)
                val s = TimelineWindow.slideFor(live)
                for (t in longArrayOf(centre.toLong(), centre.toLong() + 777, centre.toLong() - 4_321, 0L)) {
                    val drawnAt = Math.round(s.drawn.xPx(t)) - s.padPx + s.shiftPx
                    val liveAt = live.xPx(t)
                    if (abs(liveAt) < 3 * viewport) check("pps $pps vp $viewport at $centre: $t drawn at $drawnAt, live $liveAt", abs(drawnAt - liveAt) <= 1f)
                }
                check("the slide is whole pixels", s.shiftPx == Math.round(s.shiftPx).toFloat())
                check("the slide stays within an eighth of a screen (${s.shiftPx})", abs(s.shiftPx) <= viewport / 8f + 1f)
                if (last != s.drawn) rebuilds++
                last = s.drawn
                frames++
                centre += stepMs
            }
            check("pps $pps vp $viewport: the rows were rebuilt $rebuilds times in $frames frames", rebuilds <= frames / 60 + 2)
        }
    }

    println("three hours at 400 px/s now scrolls %.0f ms of content, laid out one screen at a time".format(
        TimelineWindow.linedOn(3.0 * 60 * 60_000, 400f, 3f, 1080).scrollMs
    ))
    if (failures.isEmpty()) {
        println("PASS - the window maps time to screen both ways and never lays out more than a screenful")
    } else {
        failures.take(12).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
