package com.squish.app.timeline

/**
 * Split screen in one tap: an overlay made to fill one half of the frame, cut
 * at the seam by a hard-edged rectangle mask, so the shot under it shows in the
 * other half - the side-by-side CapCut's layouts give, built from the overlay,
 * placement and mask the editor already draws and exports.
 */
enum class SplitSide(val label: String) {
    Left("Left"), Right("Right"), Top("Top"), Bottom("Bottom");

    private val across: Boolean get() = this == Left || this == Right
    private val sign: Float get() = if (this == Left || this == Top) -1f else 1f

    /**
     * How an overlay of [pictureAspect] fills this half of a frame of
     * [frameAspect] (both wide over tall): scaled to cover the half, centred
     * in it, and masked at the seam.
     *
     * The overlay used to stay full frame with its own half masked off. That
     * is right only for a picture the frame's shape: seen on the phone, a
     * portrait clip over a square frame showed a strip a quarter wide beside
     * black, since the half of the picture was not the half of the frame. The
     * mask is in the picture's own coordinates (-1..1 edge to edge), so it is
     * sized to the part of the scaled picture that lands in the half; what
     * hangs past the frame's outer edges the frame cuts anyway.
     */
    fun layout(frameAspect: Float = 1f, pictureAspect: Float = frameAspect): SplitLayout {
        val fa = frameAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val pa = pictureAspect.takeIf { it.isFinite() && it > 0f } ?: fa
        // The picture as the frame fits it, in fractions of the frame.
        val pw = if (pa >= fa) 1f else pa / fa
        val ph = if (pa >= fa) fa / pa else 1f
        val halfW = if (across) 0.5f else 1f
        val halfH = if (across) 1f else 0.5f
        // Within the placement slider's reach; a sliver of a picture then leaves a band uncovered rather than a number the sheet cannot show.
        val scale = maxOf(halfW / pw, halfH / ph).coerceAtMost(TransformLimits.SCALE_MAX)
        val mask = Mask(
            shape = MaskShape.Rectangle,
            widthFraction = if (across) (halfW / (scale * pw)).coerceAtMost(1f) else OUTER,
            heightFraction = if (across) OUTER else (halfH / (scale * ph)).coerceAtMost(1f),
            feather = SEAM
        )
        return SplitLayout(
            scale = scale,
            offsetX = if (across) sign * 0.5f else 0f,
            offsetY = if (across) 0f else sign * 0.5f,
            mask = mask
        )
    }

    private companion object {
        /** A seam, not a blend: the two halves meet on a line. */
        const val SEAM = 0.002f
        /** Past the picture's edges, on the sides the frame cuts. */
        const val OUTER = 1.05f
    }
}

/** Where a [SplitSide] puts an overlay: its placement and the mask cutting it at the seam. */
data class SplitLayout(val scale: Float, val offsetX: Float, val offsetY: Float, val mask: Mask) {
    /** Whether [clip] is laid out this way, give or take the rounding a saved draft brings back. */
    fun matches(clip: Clip): Boolean {
        val m = clip.mask ?: return false
        fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < 1e-3f
        return clip.keyframes.isEmpty() && near(clip.scale, scale) && near(clip.offsetXFraction, offsetX) &&
            near(clip.offsetYFraction, offsetY) && m.shape == mask.shape && near(m.centerXFraction, mask.centerXFraction) &&
            near(m.centerYFraction, mask.centerYFraction) && near(m.widthFraction, mask.widthFraction) &&
            near(m.heightFraction, mask.heightFraction) && !m.inverted
    }
}

/** [clipId] as one half of a split screen, laid out by [at] (SplitSide.layout): still, unturned, masked at the seam. */
fun TimelineState.withSplitScreen(clipId: String, at: SplitLayout): TimelineState = copy(
    clips = clips.map {
        if (it.id != clipId || !it.isOverlay) it
        else it.copy(
            scale = at.scale, offsetXFraction = at.offsetX, offsetYFraction = at.offsetY, rotation = 0f,
            // Upright, unflipped and uncropped, so the picture laid out is the picture seen.
            mirrored = false, quarterTurns = 0, crop = null,
            arrival = ClipArrival.None, leaving = ClipLeaving.None, loop = ClipLoop.None,
            keyframes = emptyList(), mask = at.mask
        )
    }
)

/**
 * A 2x2 grid collage: a picture shrunk into one quarter of the frame, whole
 * (not cut). [x] and [y] are the quarter's centre in fractions of half the
 * frame, so ±0.5 is a quarter line.
 */
enum class GridTile(val label: String, val x: Float, val y: Float) {
    TopLeft("↖", -0.5f, -0.5f), TopRight("↗", 0.5f, -0.5f), BottomLeft("↙", -0.5f, 0.5f), BottomRight("↘", 0.5f, 0.5f);

    /**
     * The scale and offsets that put a picture whole in this quarter of the
     * frame, in the units a placement is in: fractions of half the canvas the
     * picture is composed on.
     *
     * An overlay is composed on the frame itself, so it is 0.5 and ±0.5. A
     * base shot is composed on the picture's canvas and the frame is cut from
     * that afterwards - a 1:1 frame over portrait footage is the middle of a
     * 9:16 canvas - so ±0.5 of the canvas put the shot half off the frame
     * (seen on the phone: the top-left tile sat on the frame's top edge). The
     * quarter is measured on the kept [frameLeft]..[frameTop] window instead,
     * and a picture of another shape than the canvas ([pictureAspect] against
     * [canvasAspect], both wide over tall) is fitted inside it.
     */
    fun placement(
        canvasAspect: Float = 1f,
        pictureAspect: Float = canvasAspect,
        frameLeft: Float = 0f,
        frameTop: Float = 0f,
        frameWidth: Float = 1f,
        frameHeight: Float = 1f
    ): GridPlacement {
        val ca = canvasAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val pa = pictureAspect.takeIf { it.isFinite() && it > 0f } ?: ca
        val fw = frameWidth.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: 1f
        val fh = frameHeight.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: 1f
        // The picture as the canvas fits it, in fractions of the canvas.
        val pw = if (pa >= ca) 1f else pa / ca
        val ph = if (pa >= ca) ca / pa else 1f
        val scale = minOf(fw / 2f / pw, fh / 2f / ph)
        return GridPlacement(
            scale = scale,
            offsetX = 2f * (frameLeft + fw / 2f - 0.5f) + x * fw,
            offsetY = 2f * (frameTop + fh / 2f - 0.5f) + y * fh
        )
    }
}

/** Where a [GridTile] puts a picture: its placement's scale and offsets. */
data class GridPlacement(val scale: Float, val offsetX: Float, val offsetY: Float) {
    /** Whether a clip already sits here, give or take the rounding a saved draft brings back. */
    fun matches(scale: Float, offsetX: Float, offsetY: Float): Boolean =
        kotlin.math.abs(scale - this.scale) < 1e-3f && kotlin.math.abs(offsetX - this.offsetX) < 1e-3f &&
            kotlin.math.abs(offsetY - this.offsetY) < 1e-3f
}

/** [clipId] - a shot or an overlay - placed still at [at], a quarter of a 2x2 grid (GridTile.placement). */
fun TimelineState.withGridTile(clipId: String, at: GridPlacement): TimelineState = copy(
    clips = clips.map {
        if (it.id != clipId || it.kind != ClipKind.Video) it
        else it.copy(scale = at.scale, offsetXFraction = at.offsetX, offsetYFraction = at.offsetY, rotation = 0f, keyframes = emptyList(), mask = null)
    }
)
