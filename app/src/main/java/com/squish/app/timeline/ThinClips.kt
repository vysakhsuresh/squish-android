package com.squish.app.timeline

/**
 * Clips too narrow to show a frame, drawn as plain bars in their lane's one
 * canvas instead of each as a ClipView (28 Oct). Zoomed out on a 200-shot edit,
 * about 60 half-second shots are on screen at once, each a few dp wide, and a
 * scrub brought dozens a second into view - each built from nothing, a Box with
 * its filmstrip, border and gestures: 15-18 ms of the app's frame on the moto's
 * release build. A bar is read at draw time, so a scrub builds nothing for it.
 *
 * Anything that needs its own view keeps it however narrow: the selected clip
 * (its handles), one being carried or trimmed, and one whose file is missing
 * (its placeholder). A tap on a bar selects that clip, through the lane.
 * tools/jvm/ThinClipsChecks.kt runs both decisions.
 */
object ThinClips {

    /** At or under this a clip shows less than a sliver of a frame: a bar says as much. */
    const val MAX_DP = 8f

    fun drawnThin(widthDp: Float, selected: Boolean, lifted: Boolean, trimmed: Boolean, missing: Boolean): Boolean =
        widthDp <= MAX_DP && !selected && !lifted && !trimmed && !missing

    /** One clip drawn as a bar: its id and where it is on the timeline. */
    data class Bar(val id: String, val startMs: Long, val endMs: Long)

    /**
     * The bar a tap at [atMs] lands on: the one it is inside (the later one on a
     * join, as a ClipView laid after its neighbour would take it), else the
     * nearest within [slopMs] - a bar a few dp wide is narrower than a finger.
     */
    fun at(bars: List<Bar>, atMs: Long, slopMs: Long): String? {
        bars.lastOrNull { atMs >= it.startMs && atMs < it.endMs }?.let { return it.id }
        var best: Bar? = null
        var bestFar = Long.MAX_VALUE
        for (b in bars) {
            val far = if (atMs < b.startMs) b.startMs - atMs else atMs - b.endMs
            if (far <= slopMs && far < bestFar) { best = b; bestFar = far }
        }
        return best?.id
    }
}
