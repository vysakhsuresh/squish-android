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
            keyframes = emptyList(), mask = side.mask
        )
    }
)

/**
 * A 2x2 grid collage: a picture shrunk to half size into one quarter of the
 * frame, whole (not cut). A picture the frame's own shape fills its tile
 * exactly; another shape sits letterboxed inside it. Offsets are fractions of
 * half the frame, so ±0.5 puts the centre on a quarter line.
 */
enum class GridTile(val label: String, val x: Float, val y: Float) {
    TopLeft("↖", -0.5f, -0.5f), TopRight("↗", 0.5f, -0.5f), BottomLeft("↙", -0.5f, 0.5f), BottomRight("↘", 0.5f, 0.5f)
}

/** [clipId] - a shot or an overlay - placed still in [tile] of a 2x2 grid. */
fun TimelineState.withGridTile(clipId: String, tile: GridTile): TimelineState = copy(
    clips = clips.map {
        if (it.id != clipId || it.kind != ClipKind.Video) it
        else it.copy(scale = 0.5f, offsetXFraction = tile.x, offsetYFraction = tile.y, rotation = 0f, keyframes = emptyList(), mask = null)
    }
)
