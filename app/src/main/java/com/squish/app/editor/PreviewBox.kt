package com.squish.app.editor

/**
 * How much room the picture gets, and what shape it is shown in.
 *
 * The preview used to be a fixed 200dp-tall landscape box with the video made to
 * fill it, which is fine for the footage that happens to be that shape and wrong
 * for everything else. A phone-shot portrait clip is nine units wide to sixteen
 * tall; filling a box that is wider than it is tall means most of the frame is
 * outside it, so the editor was showing the middle third of the picture and
 * hiding the rest. You cannot place a caption, judge a crop, or even tell what
 * the shot is, against a preview that is not showing you the shot.
 *
 * So the box takes its height from the footage. It has a floor, because a
 * cinemascope clip would otherwise be a letterbox slot too short to see; and a
 * ceiling, because a tall clip given all the room it wants would push the
 * timeline off the bottom of the screen, and an editor with no timeline is not
 * an editor. Between those two the picture is shown whole, with the leftover
 * space as plain surround.
 */
object PreviewBox {

    /** Shorter than this and a wide clip is a slot rather than a picture. */
    const val MIN_HEIGHT_DP = 168f

    /**
     * Taller than this and the strip starts leaving the screen.
     *
     * A portrait clip does not get its full shape at this height - it gets
     * surround at the sides instead. That is the trade, and it is the right way
     * round: the whole frame visible and smaller beats part of the frame large.
     */
    const val MAX_HEIGHT_DP = 300f

    /** The shape to fall back to when nothing has been measured yet. */
    const val DEFAULT_ASPECT = 16f / 9f

    /**
     * The height to give the preview for footage of this shape in a box this wide.
     *
     * Note what this does *not* do: it never returns a height that would make the
     * picture fill the width for a tall clip. The height is clamped and the
     * picture is then fitted inside whatever came out, so the frame is always
     * whole. Cropping to fill is what lost the picture in the first place.
     */
    fun heightDp(aspect: Float, widthDp: Float): Float {
        if (widthDp <= 0f) return MIN_HEIGHT_DP
        val shape = if (aspect > 0f && aspect.isFinite()) aspect else DEFAULT_ASPECT
        return (widthDp / shape).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
    }

    /**
     * The picture's size inside a box of [boxWidthDp] by [boxHeightDp].
     *
     * The largest rectangle of the footage's shape that fits, which is the whole
     * frame and nothing but it. Returned rather than left to the layout so the
     * result can be checked: "the whole frame is visible" is a property with an
     * arithmetic definition, and it was quietly false for months.
     */
    fun fittedSizeDp(aspect: Float, boxWidthDp: Float, boxHeightDp: Float): Pair<Float, Float> {
        if (boxWidthDp <= 0f || boxHeightDp <= 0f) return 0f to 0f
        val shape = if (aspect > 0f && aspect.isFinite()) aspect else DEFAULT_ASPECT
        val byWidth = boxWidthDp to boxWidthDp / shape
        return if (byWidth.second <= boxHeightDp) byWidth else (boxHeightDp * shape) to boxHeightDp
    }
}
