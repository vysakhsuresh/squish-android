package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

/**
 * The crop you draw yourself, over the picture.
 *
 * Eight places to take hold of: four corners, four edges, and the middle to move
 * the whole rectangle without resizing it. That is what every editor does and it
 * is what hands expect - a corner changes two edges, an edge changes one, and the
 * inside slides.
 *
 * Every gesture goes through [CropRect.of], so a handle dragged past its opposite
 * number, off the edge of the frame, or onto itself lands somewhere the renderer
 * will accept. Handles get thrown around constantly and every one of those has to
 * end somewhere sane, not at an exception during an export twenty minutes later.
 */
@Composable
fun CustomCropOverlay(
    rect: CropRect,
    onChange: (CropRect) -> Unit,
    onCommit: () -> Unit,
    /** Off, it is only drawn - the part cropped away dimmed, and the edge - and every touch goes past it. */
    editable: Boolean = true,
    /**
     * A shape the window is held to - the kept picture's width over height -
     * with [frameAspect] the picture's own, so the hold knows what a square is
     * in fractions of this frame. Null lets every edge go (CropRules.draggedHeld).
     */
    ratio: Float? = null,
    frameAspect: Float = 1f,
    modifier: Modifier = Modifier
) {
    val latestChange by rememberUpdatedState(onChange)
    val latestCommit by rememberUpdatedState(onCommit)
    val latestRect by rememberUpdatedState(rect)
    val latestRatio by rememberUpdatedState(ratio)
    val latestAspect by rememberUpdatedState(frameAspect)

    // Which handle the current gesture grabbed, decided once when the finger lands
    // rather than per event: re-deciding as the rectangle moves under the finger
    // would let a drag hop from one handle to another mid-gesture.
    var grip by remember { mutableStateOf(Grip.None) }
    // Where the rectangle was when the finger landed: a held shape is worked
    // out from the finger's whole drag, so a fast drag and a slow one agree.
    var start by remember { mutableStateOf(rect) }
    var dragged by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(editable) {
                if (!editable) return@pointerInput
                detectDragGestures(
                    onDragStart = { at ->
                        start = latestRect
                        dragged = Offset.Zero
                        grip = gripAt(
                            x = at.x,
                            y = at.y,
                            rect = latestRect,
                            width = size.width.toFloat().coerceAtLeast(1f),
                            height = size.height.toFloat().coerceAtLeast(1f),
                            reachPx = GRAB_REACH.toPx()
                        )
                    },
                    onDragEnd = {
                        grip = Grip.None
                        latestCommit()
                    },
                    // A cancelled drag - a system gesture took the pointer - still
                    // moved the window, so its step is closed like any other;
                    // left open, the next drag joined it as one undo.
                    onDragCancel = {
                        grip = Grip.None
                        latestCommit()
                    }
                ) { change, drag ->
                    change.consume()
                    val held = latestRatio
                    if (held == null) {
                        val dx = drag.x / size.width.coerceAtLeast(1)
                        val dy = drag.y / size.height.coerceAtLeast(1)
                        latestChange(grip.applied(latestRect, dx, dy))
                    } else {
                        dragged += drag
                        val dx = dragged.x / size.width.coerceAtLeast(1)
                        val dy = dragged.y / size.height.coerceAtLeast(1)
                        val free = grip.applied(start, dx, dy)
                        latestChange(
                            if (grip == Grip.Move || grip == Grip.None) free
                            else CropRules.draggedHeld(
                                free, start, held, latestAspect,
                                grip.movesLeft, grip.movesRight, grip.movesTop, grip.movesBottom
                            )
                        )
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val left = rect.left * w
        val top = rect.top * h
        val right = rect.right * w
        val bottom = rect.bottom * h

        // What is being thrown away, dimmed. The point of drawing the whole frame
        // is that you can see what the crop costs while you choose it.
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset(0f, 0f), Size(w, top))
        drawRect(dim, Offset(0f, bottom), Size(w, (h - bottom).coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, top), Size(left, bottom - top))
        drawRect(dim, Offset(right, top), Size((w - right).coerceAtLeast(0f), bottom - top))

        drawRect(
            color = SquishColors.Violet,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 2f)
        )

        // The rest are the handles, for taking hold of it.
        if (!editable) return@Canvas

        // Thirds, because framing is judged against them.
        val guide = SquishColors.TextPrimary.copy(alpha = 0.22f)
        for (i in 1..2) {
            val x = left + (right - left) * i / 3f
            val y = top + (bottom - top) * i / 3f
            drawLine(guide, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
            drawLine(guide, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        }

        // Corner grips, drawn as brackets rather than dots: a bracket says which
        // two edges it moves, a dot says nothing.
        val arm = minOf(w, h) * 0.08f
        val thickness = 3f
        listOf(
            Triple(left, top, 1f to 1f),
            Triple(right, top, -1f to 1f),
            Triple(left, bottom, 1f to -1f),
            Triple(right, bottom, -1f to -1f)
        ).forEach { (x, y, dir) ->
            val (sx, sy) = dir
            drawLine(SquishColors.Violet, Offset(x, y), Offset(x + arm * sx, y), strokeWidth = thickness)
            drawLine(SquishColors.Violet, Offset(x, y), Offset(x, y + arm * sy), strokeWidth = thickness)
        }

        // A grip in the middle of every edge. With only the corners marked, the
        // top and bottom looked fixed - nothing said they could be taken hold of.
        val pill = GRIP_PILL.toPx()
        val midX = (left + right) / 2f
        val midY = (top + bottom) / 2f
        listOf(Offset(midX, top), Offset(midX, bottom)).forEach { at ->
            drawLine(SquishColors.Violet, at.copy(x = at.x - pill / 2f), at.copy(x = at.x + pill / 2f), strokeWidth = 6f)
        }
        listOf(Offset(left, midY), Offset(right, midY)).forEach { at ->
            drawLine(SquishColors.Violet, at.copy(y = at.y - pill / 2f), at.copy(y = at.y + pill / 2f), strokeWidth = 6f)
        }
    }
}

/** How far from an edge a finger still takes hold of it - finger-sized, not frame-relative. */
private val GRAB_REACH = 28.dp

/** The length of the mid-edge grips. */
private val GRIP_PILL = 22.dp

/** Which part of the rectangle a finger landed on. */
private enum class Grip {
    None, Move,
    Left, Right, Top, Bottom,
    TopLeft, TopRight, BottomLeft, BottomRight;

    val movesLeft: Boolean get() = this == Left || this == TopLeft || this == BottomLeft
    val movesRight: Boolean get() = this == Right || this == TopRight || this == BottomRight
    val movesTop: Boolean get() = this == Top || this == TopLeft || this == TopRight
    val movesBottom: Boolean get() = this == Bottom || this == BottomLeft || this == BottomRight

    /** The rectangle after this grip has been dragged by a fraction of the frame. */
    fun applied(rect: CropRect, dx: Float, dy: Float): CropRect = when (this) {
        None -> rect
        Move -> {
            // Sliding keeps the size: both edges move together, and the pair is
            // stopped at the frame's edge rather than squashed against it.
            val shiftX = dx.coerceIn(-rect.left, 1f - rect.right)
            val shiftY = dy.coerceIn(-rect.top, 1f - rect.bottom)
            CropRect.of(
                rect.left + shiftX, rect.top + shiftY,
                rect.right + shiftX, rect.bottom + shiftY
            )
        }
        // Every other grip moves the sides it names and pins the rest, which is
        // CropRules.draggedFree - executed on the JVM, and the same pinning the
        // held-ratio path does. Done here by hand, a left or top bracket dragged
        // past its opposite number pushed that edge along instead of stopping,
        // and the window ran away under the finger to the far side of the frame.
        else -> CropRules.draggedFree(rect, dx, dy, movesLeft, movesRight, movesTop, movesBottom)
    }
}

/**
 * Which grip a finger landed on, measured in pixels.
 *
 * The reach used to be 9% of the frame, which on a short landscape picture is a
 * sliver a finger cannot find - so the top and bottom edges seemed not to move at
 * all. It is now a finger's width, and a drag that starts in the dimmed part
 * outside the rectangle takes the nearest edge or corner rather than nothing.
 */
private fun gripAt(x: Float, y: Float, rect: CropRect, width: Float, height: Float, reachPx: Float): Grip {
    val left = rect.left * width
    val right = rect.right * width
    val top = rect.top * height
    val bottom = rect.bottom * height

    val nearLeft = kotlin.math.abs(x - left) < reachPx
    val nearRight = kotlin.math.abs(x - right) < reachPx
    val nearTop = kotlin.math.abs(y - top) < reachPx
    val nearBottom = kotlin.math.abs(y - bottom) < reachPx
    val inside = x > left && x < right && y > top && y < bottom

    // Corners before edges: at a corner both tests pass, and the corner is what a
    // finger that close to one meant. Inside a small rectangle every edge can be
    // "near", so the nearest one wins rather than whichever is tested first.
    //
    // By distance to the corner itself, and with no `!inside` on it. The corner
    // brackets are drawn *inward*, so every pixel of one is inside the
    // rectangle, and both ways into Crop open the window at the whole picture -
    // where there is no outside at all. A press on a bracket therefore fell
    // through to the edges below and moved one edge instead of two, which is
    // the opposite of what a corner is for, and the doc comment two lines up
    // had been promising "a corner changes two edges" the whole time.
    val corners = listOf(
        Grip.TopLeft to kotlin.math.hypot(x - left, y - top),
        Grip.TopRight to kotlin.math.hypot(x - right, y - top),
        Grip.BottomLeft to kotlin.math.hypot(x - left, y - bottom),
        Grip.BottomRight to kotlin.math.hypot(x - right, y - bottom)
    )
    val corner = corners.minBy { it.second }
    return when {
        corner.second < reachPx -> corner.first
        nearLeft || nearRight || nearTop || nearBottom -> {
            val distances = listOf(
                Grip.Left to kotlin.math.abs(x - left),
                Grip.Right to kotlin.math.abs(x - right),
                Grip.Top to kotlin.math.abs(y - top),
                Grip.Bottom to kotlin.math.abs(y - bottom)
            )
            val nearest = distances.minBy { it.second }
            // Well inside a large rectangle is a move, not the edge it happens to be
            // a finger's width from.
            if (inside && nearest.second > reachPx * 0.6f) Grip.Move else nearest.first
        }
        inside -> Grip.Move
        else -> {
            // Out in the dimmed part: take whatever edges lie between the finger
            // and the rectangle.
            val horizontal = when {
                x < left -> -1
                x > right -> 1
                else -> 0
            }
            val vertical = when {
                y < top -> -1
                y > bottom -> 1
                else -> 0
            }
            when {
                horizontal < 0 && vertical < 0 -> Grip.TopLeft
                horizontal > 0 && vertical < 0 -> Grip.TopRight
                horizontal < 0 && vertical > 0 -> Grip.BottomLeft
                horizontal > 0 && vertical > 0 -> Grip.BottomRight
                horizontal < 0 -> Grip.Left
                horizontal > 0 -> Grip.Right
                vertical < 0 -> Grip.Top
                vertical > 0 -> Grip.Bottom
                else -> Grip.None
            }
        }
    }
}
