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
    modifier: Modifier = Modifier
) {
    val latestChange by rememberUpdatedState(onChange)
    val latestCommit by rememberUpdatedState(onCommit)
    val latestRect by rememberUpdatedState(rect)

    // Which handle the current gesture grabbed, decided once when the finger lands
    // rather than per event: re-deciding as the rectangle moves under the finger
    // would let a drag hop from one handle to another mid-gesture.
    var grip by remember { mutableStateOf(Grip.None) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { at ->
                        grip = gripAt(
                            at.x / size.width.coerceAtLeast(1),
                            at.y / size.height.coerceAtLeast(1),
                            latestRect
                        )
                    },
                    onDragEnd = {
                        grip = Grip.None
                        latestCommit()
                    },
                    onDragCancel = { grip = Grip.None }
                ) { change, drag ->
                    change.consume()
                    val dx = drag.x / size.width.coerceAtLeast(1)
                    val dy = drag.y / size.height.coerceAtLeast(1)
                    latestChange(grip.applied(latestRect, dx, dy))
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
    }
}

/** Which part of the rectangle a finger landed on. */
private enum class Grip {
    None, Move,
    Left, Right, Top, Bottom,
    TopLeft, TopRight, BottomLeft, BottomRight;

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
        Left -> CropRect.of(rect.left + dx, rect.top, rect.right, rect.bottom)
        Right -> CropRect.of(rect.left, rect.top, rect.right + dx, rect.bottom)
        Top -> CropRect.of(rect.left, rect.top + dy, rect.right, rect.bottom)
        Bottom -> CropRect.of(rect.left, rect.top, rect.right, rect.bottom + dy)
        TopLeft -> CropRect.of(rect.left + dx, rect.top + dy, rect.right, rect.bottom)
        TopRight -> CropRect.of(rect.left, rect.top + dy, rect.right + dx, rect.bottom)
        BottomLeft -> CropRect.of(rect.left + dx, rect.top, rect.right, rect.bottom + dy)
        BottomRight -> CropRect.of(rect.left, rect.top, rect.right + dx, rect.bottom + dy)
    }
}

/** How close to an edge counts as grabbing it, as a fraction of the frame. */
private const val GRAB = 0.09f

private fun gripAt(x: Float, y: Float, rect: CropRect): Grip {
    val nearLeft = kotlin.math.abs(x - rect.left) < GRAB
    val nearRight = kotlin.math.abs(x - rect.right) < GRAB
    val nearTop = kotlin.math.abs(y - rect.top) < GRAB
    val nearBottom = kotlin.math.abs(y - rect.bottom) < GRAB

    // Corners before edges: at a corner both tests pass, and the corner is what a
    // finger that close to one meant.
    return when {
        nearLeft && nearTop -> Grip.TopLeft
        nearRight && nearTop -> Grip.TopRight
        nearLeft && nearBottom -> Grip.BottomLeft
        nearRight && nearBottom -> Grip.BottomRight
        nearLeft -> Grip.Left
        nearRight -> Grip.Right
        nearTop -> Grip.Top
        nearBottom -> Grip.Bottom
        x > rect.left && x < rect.right && y > rect.top && y < rect.bottom -> Grip.Move
        else -> Grip.None
    }
}
