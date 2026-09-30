package com.squish.app.timeline

/**
 * Split screen in one tap: an overlay laid over the whole frame and cut to one
 * half by a hard-edged rectangle mask, so the shot under it fills the other
 * half. Both pictures stay full-frame and fitted, so each half shows its own
 * shot's matching half - the side-by-side CapCut's layouts give, built from the
 * overlay, placement and mask the editor already draws and exports.
 */
enum class SplitSide(val label: String) {
    Left("Left"), Right("Right"), Top("Top"), Bottom("Bottom");

    /** The mask that keeps this half of the overlay's own frame. Centres run -1..1 edge to edge. */
    val mask: Mask
        get() = when (this) {
            Left -> Mask(shape = MaskShape.Rectangle, centerXFraction = -0.5f, widthFraction = 0.5f, heightFraction = 1.05f, feather = SEAM)
            Right -> Mask(shape = MaskShape.Rectangle, centerXFraction = 0.5f, widthFraction = 0.5f, heightFraction = 1.05f, feather = SEAM)
            Top -> Mask(shape = MaskShape.Rectangle, centerYFraction = -0.5f, widthFraction = 1.05f, heightFraction = 0.5f, feather = SEAM)
            Bottom -> Mask(shape = MaskShape.Rectangle, centerYFraction = 0.5f, widthFraction = 1.05f, heightFraction = 0.5f, feather = SEAM)
        }

    private companion object {
        /** A seam, not a blend: the two halves meet on a line. */
        const val SEAM = 0.002f
    }
}

/** [clipId] as the [side] half of a split screen: full frame, still, unturned, masked to that half. */
fun TimelineState.withSplitScreen(clipId: String, side: SplitSide): TimelineState = copy(
    clips = clips.map {
        if (it.id != clipId || !it.isOverlay) it
        else it.copy(
            scale = 1f, offsetXFraction = 0f, offsetYFraction = 0f, rotation = 0f,
            // Upright, unflipped and uncropped, so the half masked is the half seen.
            mirrored = false, quarterTurns = 0, crop = null,
            arrival = ClipArrival.None, leaving = ClipLeaving.None, loop = ClipLoop.None,
            keyframes = emptyList(), mask = side.mask
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
