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
     *
     * [maxHeightDp] is the cap, [MAX_HEIGHT_DP] by default. It is a parameter
     * because a screen with no strip under the picture can afford a taller one,
     * and the quick tools ask for that. They used to ask by clamping the answer
     * afterwards, which could only ever make the box *shorter*: the cap here
     * had already cut it to 300, so their 320 did nothing and the quick tools
     * have had the editor's height all along. Below [MIN_HEIGHT_DP] the floor
     * wins - and the floor has to be the lower of the two, or `coerceIn`
     * throws on an inverted range rather than returning anything.
     */
    fun heightDp(aspect: Float, widthDp: Float, maxHeightDp: Float = MAX_HEIGHT_DP): Float {
        if (widthDp <= 0f) return MIN_HEIGHT_DP
        val shape = if (aspect > 0f && aspect.isFinite()) aspect else DEFAULT_ASPECT
        val cap = if (maxHeightDp.isFinite()) maxOf(maxHeightDp, MIN_HEIGHT_DP) else MAX_HEIGHT_DP
        return (widthDp / shape).coerceIn(MIN_HEIGHT_DP, cap)
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

    /**
     * The size to lay the unrotated picture out at, inside a canvas of
     * [canvasWidth] by [canvasHeight] that already has the rotated shape, so that
     * once it is turned by [rotationDegrees] it covers the canvas exactly.
     *
     * The rotation is done on screen, by turning the view, rather than inside the
     * player's effect chain. Changing a chain under a loaded player means stopping
     * it and reloading; with a rotation that also changes the frame's shape, the
     * pipeline wedged, the playback thread stopped answering, and every surface
     * call on the main thread then blocked for two seconds until it timed out -
     * a frozen picture and an editor that ignored taps. Turning a view costs
     * nothing and cannot wedge anything.
     */
    fun unrotatedSize(canvasWidth: Float, canvasHeight: Float, rotationDegrees: Int): Pair<Float, Float> =
        if (isQuarterTurn(rotationDegrees)) canvasHeight to canvasWidth else canvasWidth to canvasHeight

    fun isQuarterTurn(rotationDegrees: Int): Boolean = ((rotationDegrees % 180) + 180) % 180 == 90

    /**
     * The on-screen turn that matches the export's rotation.
     *
     * Media3's ScaleAndRotateTransformation turns counterclockwise for positive
     * degrees; a view's rotationZ turns clockwise. Same number, opposite way,
     * so the preview negates it or every rotated edit previews upside down
     * relative to the file.
     */
    fun screenRotation(rotationDegrees: Int): Float = -rotationDegrees.toFloat()

    /**
     * The shape a decoded frame is actually shown in: its pixel shape corrected
     * for non-square pixels and turned by any rotation the decoder left for the
     * display to apply. Null when there is nothing sensible to say yet.
     */
    fun displayAspect(width: Int, height: Int, unappliedRotationDegrees: Int, pixelWidthHeightRatio: Float): Float? {
        if (width <= 0 || height <= 0) return null
        val ratio = if (pixelWidthHeightRatio > 0f && pixelWidthHeightRatio.isFinite()) pixelWidthHeightRatio else 1f
        val aspect = width * ratio / height
        return if (isQuarterTurn(unappliedRotationDegrees)) 1f / aspect else aspect
    }

    /** A rectangle as fractions of the canvas: 0 is the left or top edge, 1 the right or bottom. */
    data class Frame(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * The part of a canvas [canvasAspect] wide-to-tall that a crop of [ratio]
     * keeps, centred on [focus] (fractions, for auto-reframe) and slid back
     * inside the canvas. The whole canvas when there is no ratio.
     *
     * One definition, read by the picture's clip and by the captions, so the two
     * can never disagree about where the frame is - they did, when each worked it
     * out for itself.
     */
    fun cropFrame(canvasAspect: Float, ratio: Float?, focus: Pair<Float, Float>? = null): Frame {
        if (ratio == null || ratio <= 0f || !ratio.isFinite() || canvasAspect <= 0f || !canvasAspect.isFinite()) return Frame()
        val w = if (ratio < canvasAspect) ratio / canvasAspect else 1f
        val h = if (ratio < canvasAspect) 1f else canvasAspect / ratio
        val (fx, fy) = focus ?: (0.5f to 0.5f)
        val left = (fx - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
        val top = (fy - h / 2f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        return Frame(left, top, left + w, top + h)
    }
}
