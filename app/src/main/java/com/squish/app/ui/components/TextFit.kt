package com.squish.app.ui.components

/**
 * The one decision inside [FitText], kept out of the composable so it can be
 * executed on the JVM (tools/jvm/TextFitChecks.kt).
 *
 * Shrinking a line until it fits is a loop: lay it out, see that it overflowed,
 * come down a step, lay it out again. The part that was wrong is the other
 * direction. The scale was reset only when the words or the style changed, so a
 * line that had shrunk in a narrow box kept the smaller size when the box grew
 * - the phone turned into two panes, a sheet widened, a tile row went from
 * three across to two - and sat there small with room to spare for as long as
 * the words did not change.
 */
object TextFit {

    /** One step down. Small enough that the last step is not a visible jump. */
    const val STEP = 0.06f

    /**
     * How much wider the box has to get before the line starts again from full
     * size. A few pixels of slack, because a box that wobbles - a scrollbar
     * arriving, a badge landing, a rounding difference between two layout
     * passes - would otherwise reset and re-shrink on every wobble, which is a
     * visible flicker and a layout pass that never settles.
     */
    const val GROWTH_SLACK = 4

    /**
     * The scale to lay the line out at next, given [scale] now.
     *
     * [room] is the width this layout was given and [fittedAt] the width the
     * one before it was given. Grown by more than [GROWTH_SLACK] and the line
     * starts again from full size, because the steps that took it down were
     * decided against a narrower box and may no longer be needed. Otherwise it
     * comes down a step while it still overflows.
     *
     * Returns [scale] unchanged when there is nothing to do, so the caller can
     * leave the state alone and not recompose.
     */
    fun nextScale(
        scale: Float,
        overflowing: Boolean,
        room: Int,
        fittedAt: Int,
        minScale: Float
    ): Float = when {
        room > fittedAt + GROWTH_SLACK && scale < 1f -> 1f
        overflowing && scale > minScale -> (scale - STEP).coerceAtLeast(minScale)
        else -> scale
    }
}
