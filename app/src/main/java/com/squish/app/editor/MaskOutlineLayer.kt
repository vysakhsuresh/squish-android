package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Mask
import com.squish.app.ui.theme.SquishColors

/**
 * The mask's edge drawn over the clip's picture, with a dot at its centre,
 * and one finger to move it - laid over the plain, unplaced picture the
 * preview shows while the Mask tool is open (TimelinePreview's picture tool).
 * The sliders on the sheet do the rest. A tracked mask is drawn where the
 * track has it and is not moved by hand: its centre is the track's. The edge
 * itself is MaskOutline's, found on the shader's own distance field.
 */
@Composable
fun MaskOutlineLayer(
    mask: Mask,
    /** The shape's centre now, in the shader's fractions: the track's, or the mask's own. */
    centre: Pair<Float, Float>,
    onMove: (dxFraction: Float, dyFraction: Float) -> Unit,
    onMoveEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestMove by rememberUpdatedState(onMove)
    val latestEnd by rememberUpdatedState(onMoveEnd)
    val movable = mask.track == null
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(movable) {
                if (!movable) return@pointerInput
                detectDragGestures(onDragEnd = { latestEnd() }, onDragCancel = { latestEnd() }) { change, drag ->
                    change.consume()
                    val (fx, fy) = MaskOutline.dragged(drag.x, drag.y, size.width.toFloat(), size.height.toFloat())
                    latestMove(fx, fy)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val lines = MaskOutline.outline(mask, centre, w, h)
        val closed = mask.shape.hasBox
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        lines.forEach { points ->
            if (points.isEmpty()) return@forEach
            val path = Path().apply {
                moveTo(points[0].first, points[0].second)
                points.drop(1).forEach { (x, y) -> lineTo(x, y) }
                if (closed) close()
            }
            // Dark under light, so the edge reads on a white shirt and a night sky alike.
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(width = 4.dp.toPx()))
            drawPath(path, SquishColors.Magenta, style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
        }
        val cx = (centre.first + 1f) / 2f * w
        val cy = (1f - (centre.second + 1f) / 2f) * h
        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 7.dp.toPx(), center = Offset(cx, cy))
        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(cx, cy))
    }
}
