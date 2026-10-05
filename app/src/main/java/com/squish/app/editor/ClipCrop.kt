package com.squish.app.editor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shapes the Crop sheet holds its window to. Free lets any edge go; the
 * rest keep the window at that shape whichever handle is dragged.
 */
enum class CropRatio(val label: String, val value: Float?) {
    Free("Free", null),
    Square("1:1", 1f),
    Portrait("9:16", 9f / 16f),
    Landscape("16:9", 16f / 9f),
    FourThree("4:3", 4f / 3f),
    ThreeFour("3:4", 3f / 4f),
    FourFive("4:5", 4f / 5f),
    TwoOne("2:1", 2f),
    Cinema("2.35:1", 2.35f)
}

/**
 * One clip's own crop: the window kept of its picture, the picture turned a
 * little under it, and mirrored. Separate from the edit's frame (the ratio the
 * whole edit is posted at, EditorUiState.cropAspect): this cuts the shot, that
 * cuts the canvas. A cropped shot is then fitted into the canvas as it is, the
 * way a cropped clip sits in CapCut, and Placement makes it bigger if wanted.
 *
 * [straightenDegrees] is clockwise as seen. The picture is zoomed by
 * [CropRules.zoomToCover] as it turns, so the window never shows past its
 * corners - the way every straighten dial behaves.
 */
data class ClipCrop(
    val rect: CropRect = CropRect(),
    val straightenDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** The chip chosen on the sheet, so it reads back; the window itself is [rect]. */
    val ratio: CropRatio = CropRatio.Free
) {
    val isIdentity: Boolean
        get() = rect.isFull && abs(straightenDegrees) < 0.01f && !flipHorizontal && !flipVertical

    /** Whether the picture is turned or mirrored, apart from being cut. */
    val turnsPicture: Boolean get() = abs(straightenDegrees) >= 0.01f || flipHorizontal || flipVertical
}

/**
 * The arithmetic of a clip's crop, in one place for the preview's layers, the
 * export's shader (ClipCropEffect) and the box on the picture, so all three
 * agree about which part of the shot is kept. tools/jvm/ClipCropChecks.kt
 * executes it.
 *
 * Every point here is a fraction of a frame, top-down: (0, 0) is the top left
 * and (1, 1) the bottom right, the way the crop rectangle is drawn. "Isotropic"
 * means x has been stretched by the frame's aspect so a turn is a turn and not
 * a shear - the same trick the mask shader and ExportPlan.placementMatrix use.
 */
object CropRules {

    /** As far as the straighten dial goes either way. */
    const val MAX_STRAIGHTEN_DEGREES = 45f

    /**
     * The zoom that keeps a frame of [aspect] covering its own bounds after a
     * turn of [degrees]: the corner of the frame furthest from the axis has to
     * stay inside the turned picture. 1 at no turn; about 1.7 for a 16:9 frame
     * at 45 degrees.
     */
    fun zoomToCover(degrees: Float, aspect: Float): Float {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val radians = Math.toRadians(degrees.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        return c + s * maxOf(a, 1f / a)
    }

    /** The cropped picture's shape, from the source's [aspect] and the window kept of it. */
    fun croppedAspect(aspect: Float, crop: ClipCrop?): Float =
        if (crop == null || crop.rect.isFull) aspect else crop.rect.aspect(aspect)

    /**
     * The scale that fits a region [regionW] x [regionH] into a canvas
     * [canvasW] x [canvasH] whole: the export's Presentation fit, for the
     * preview's layer to make the same move.
     */
    fun fitScale(regionW: Float, regionH: Float, canvasW: Float, canvasH: Float): Float {
        if (regionW <= 0f || regionH <= 0f || canvasW <= 0f || canvasH <= 0f) return 1f
        return minOf(canvasW / regionW, canvasH / regionH)
    }

    /**
     * Where a point of the cropped picture ([ox], [oy] as fractions of the
     * window) reads its pixel from, as fractions of the source frame. The
     * window sits on the picture as turned and mirrored, so this undoes the
     * turn: the shader's own maths, which the preview inverts with layers.
     */
    fun sourcePoint(ox: Float, oy: Float, crop: ClipCrop, aspect: Float): Pair<Float, Float> {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val u = crop.rect.left + ox * crop.rect.width
        val v = crop.rect.top + oy * crop.rect.height
        val px = (u - 0.5f) * a
        val py = v - 0.5f
        val radians = Math.toRadians(crop.straightenDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val z = zoomToCover(crop.straightenDegrees, a)
        // Undoing a clockwise turn, in coordinates where y points down.
        var rx = (px * c + py * s) / z
        var ry = (-px * s + py * c) / z
        if (crop.flipHorizontal) rx = -rx
        if (crop.flipVertical) ry = -ry
        return (rx / a + 0.5f) to (ry + 0.5f)
    }

    /**
     * The other way: where a point of the source frame lands, as fractions of
     * the window, after the picture is mirrored, turned, zoomed and cut - the
     * move the preview's layers make. The inverse of [sourcePoint].
     */
    fun windowPoint(sx: Float, sy: Float, crop: ClipCrop, aspect: Float): Pair<Float, Float> {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        var px = (sx - 0.5f) * a
        var py = sy - 0.5f
        if (crop.flipHorizontal) px = -px
        if (crop.flipVertical) py = -py
        val radians = Math.toRadians(crop.straightenDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val z = zoomToCover(crop.straightenDegrees, a)
        val rx = (px * c - py * s) * z
        val ry = (px * s + py * c) * z
        val u = rx / a + 0.5f
        val v = ry + 0.5f
        return ((u - crop.rect.left) / crop.rect.width) to ((v - crop.rect.top) / crop.rect.height)
    }

    /**
     * The window held to [ratio] (the kept picture's width over height) on a
     * frame of [aspect], keeping its centre and as much of its area as fits:
     * what picking a ratio chip does to the rectangle already drawn.
     */
    fun heldToRatio(rect: CropRect, ratio: Float?, aspect: Float): CropRect {
        if (ratio == null || ratio <= 0f || aspect <= 0f) return rect
        // The window's width over its height in frame fractions, for this shape.
        val wanted = ratio / aspect
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        var w = rect.width
        var h = w / wanted
        if (h > rect.height) {
            h = rect.height
            w = h * wanted
        }
        // Never past the frame: shrink until it fits, then slide it inside.
        if (w > 1f) { w = 1f; h = w / wanted }
        if (h > 1f) { h = 1f; w = h * wanted }
        val left = (cx - w / 2f).coerceIn(0f, 1f - w)
        val top = (cy - h / 2f).coerceIn(0f, 1f - h)
        return CropRect.of(left, top, left + w, top + h)
    }

    /**
     * A handle dragged with no shape held: the sides the grip moves, moved,
     * and the sides it does not, pinned.
     *
     * Pinned is the point. CropRect.of clamps the low edges first and derives
     * the high ones from them (`right.coerceIn(l + MIN_SIDE, 1f)`), so an
     * over-run of *left* or *top* pushed its opposite edge along instead of
     * stopping - the window ran away under the finger, a step at a time, until
     * it parked as an eight-percent strip against the far side of the frame.
     * An over-run of right or bottom was stopped correctly, which is what made
     * the left and top brackets read as working. The held-ratio path below has
     * always pinned the still side, and says so: "the shape shrinks to fit from
     * the anchor rather than the anchor sliding to make room."
     */
    fun draggedFree(
        before: CropRect,
        dx: Float,
        dy: Float,
        movesLeft: Boolean,
        movesRight: Boolean,
        movesTop: Boolean,
        movesBottom: Boolean
    ): CropRect {
        val min = CropRect.MIN_SIDE
        // A moving low edge stops a minimum short of the high edge that stayed;
        // a moving high edge stops a minimum past the low one. Where both move
        // (nothing does today, but the signature allows it) the frame bounds it.
        val left = if (!movesLeft) before.left else
            (before.left + dx).coerceIn(0f, if (movesRight) 1f - min else (before.right - min).coerceAtLeast(0f))
        val right = if (!movesRight) before.right else
            (before.right + dx).coerceIn(if (movesLeft) min else (left + min).coerceAtMost(1f), 1f)
        val top = if (!movesTop) before.top else
            (before.top + dy).coerceIn(0f, if (movesBottom) 1f - min else (before.bottom - min).coerceAtLeast(0f))
        val bottom = if (!movesBottom) before.bottom else
            (before.bottom + dy).coerceIn(if (movesTop) min else (top + min).coerceAtMost(1f), 1f)
        return CropRect.of(left, top, right, bottom)
    }

    /**
     * A handle dragged with the window held to a shape: the rectangle it
     * would be without the hold, then the side the finger moved least
     * follows the other. [movedX] and [movedY] say which sides the grip
     * changes; a grip on one edge only ever changes that one.
     */
    fun draggedHeld(
        free: CropRect,
        before: CropRect,
        ratio: Float?,
        aspect: Float,
        movesLeft: Boolean,
        movesRight: Boolean,
        movesTop: Boolean,
        movesBottom: Boolean
    ): CropRect {
        if (ratio == null || ratio <= 0f || aspect <= 0f) return free
        val wanted = ratio / aspect
        val movesX = movesLeft || movesRight
        val movesY = movesTop || movesBottom
        // Which side leads: the one the finger changed, or with a corner the one
        // it changed more.
        val dx = abs(free.width - before.width)
        val dy = abs(free.height - before.height)
        val widthLeads = movesX && (!movesY || dx >= dy)
        var left = free.left
        var right = free.right
        var top = free.top
        var bottom = free.bottom
        if (widthLeads) {
            val h = (right - left) / wanted
            if (movesTop) top = bottom - h
            else if (movesBottom) bottom = top + h
            else {
                val cy = (before.top + before.bottom) / 2f
                top = cy - h / 2f
                bottom = cy + h / 2f
            }
        } else {
            val w = (bottom - top) * wanted
            if (movesLeft) left = right - w
            else if (movesRight) right = left + w
            else {
                val cx = (before.left + before.right) / 2f
                left = cx - w / 2f
                right = cx + w / 2f
            }
        }
        // The side that did not move stays put; the moving side stops at the
        // frame's edge, and the shape shrinks to fit from the anchor rather than
        // the anchor sliding to make room.
        val anchorRight = movesLeft && !movesRight
        val anchorBottom = movesTop && !movesBottom
        val maxW = when {
            anchorRight -> before.right
            movesRight && !movesLeft -> 1f - before.left
            else -> 1f
        }
        val maxH = when {
            anchorBottom -> before.bottom
            movesBottom && !movesTop -> 1f - before.top
            else -> 1f
        }
        var w = right - left
        var h = bottom - top
        if (w > maxW) { w = maxW; h = w / wanted }
        if (h > maxH) { h = maxH; w = h * wanted }
        if (w < CropRect.MIN_SIDE) { w = CropRect.MIN_SIDE; h = w / wanted }
        if (h < CropRect.MIN_SIDE) { h = CropRect.MIN_SIDE; w = h * wanted }
        val l = when {
            anchorRight -> before.right - w
            movesRight && !movesLeft -> before.left
            else -> left
        }.coerceIn(0f, 1f - w)
        val t = when {
            anchorBottom -> before.bottom - h
            movesBottom && !movesTop -> before.top
            else -> top
        }.coerceIn(0f, 1f - h)
        return CropRect.of(l, t, l + w, t + h)
    }
}
