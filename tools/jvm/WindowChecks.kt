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

private const val TAIL_DP = 240f

/** Compose refuses any dimension at or above this. Nothing drawn may reach it. */
private const val HARD_LIMIT_PX = 262_143

fun main() {
    var widest = 0f

    for (density in densities) {
        for (pps in zooms) {
            for (viewportPx in viewports) {
                for (durationMs in durations) {
                    val maxScroll = TimelineWindow(pps, 0.0, density, viewportPx)
                        .maxScrollMs(durationMs, TAIL_DP)

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

    // Scrolling is bounded at both ends, whatever it is asked for.
    val long = TimelineWindow(400f, 0.0, 3f, 1080)
    val cap = long.maxScrollMs(3 * 60 * 60_000, TAIL_DP)
    check("scroll cannot go negative", long.scrolledTo(-5_000.0, 3 * 60 * 60_000, TAIL_DP).scrollMs == 0.0)
    check("scroll cannot pass the end", long.scrolledTo(cap * 10.0, 3 * 60 * 60_000, TAIL_DP).scrollMs == cap)
    check("a short edit does not scroll at all", long.maxScrollMs(100L, TAIL_DP) >= 0.0)

    // Degenerate input must not produce a NaN position.
    val dead = TimelineWindow(0f, 0.0, 0f, 0)
    check("a zero window has a finite position", dead.xPx(1_000).isFinite())
    check("a zero window maps to the start", dead.msAt(100f) == 0L)

    println("widest thing drawn across the sweep: %.0f px (ceiling %d)".format(widest, HARD_LIMIT_PX))
    // A pinch must leave the moment under it where it was, at any zoom or length.
    for (pps in floatArrayOf(2f, 42f, 400f, 4_000f)) {
        for (factor in floatArrayOf(0.25f, 0.5f, 2f, 8f)) {
            val before = TimelineWindow(pps, 900_000.0, 2.75f, 1080)
            val anchor = before.msAt(540f)
            val after = before.zoomedTo(pps * factor, anchor, 3 * 60 * 60_000, TAIL_DP)
            val moved = abs(after.xPx(anchor) - before.xPx(anchor))
            check("a pinch from $pps by $factor holds its anchor (moved $moved px)", moved < 2f)
        }
    }

    println("three hours at 400 px/s now scrolls %.0f ms of content, laid out one screen at a time".format(cap))
    if (failures.isEmpty()) {
        println("PASS - the window maps time to screen both ways and never lays out more than a screenful")
    } else {
        failures.take(12).forEach { println("FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
