package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.squish.app.media.effects.Curve
import com.squish.app.media.effects.CurvePoint
import com.squish.app.media.effects.ToneCurve
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors
import kotlin.math.abs
import kotlin.math.hypot

/** Which of the four curves is being drawn on. */
private enum class CurveChannel(val label: String, val tint: Color) {
    Master("All", SquishColors.TextPrimary),
    Red("Red", Color(0xFFFF5A5A)),
    Green("Green", Color(0xFF5AE08A)),
    Blue("Blue", Color(0xFF5AA8FF))
}

private fun ToneCurve.of(c: CurveChannel): Curve = when (c) {
    CurveChannel.Master -> master
    CurveChannel.Red -> red
    CurveChannel.Green -> green
    CurveChannel.Blue -> blue
}

private fun ToneCurve.with(c: CurveChannel, curve: Curve): ToneCurve = when (c) {
    CurveChannel.Master -> copy(master = curve)
    CurveChannel.Red -> copy(red = curve)
    CurveChannel.Green -> copy(green = curve)
    CurveChannel.Blue -> copy(blue = curve)
}

/**
 * The Curves tool: a square to draw in, one channel at a time.
 *
 * Drag a point to move it. Tap the empty part of the square to put one there.
 * Drag a point onto its neighbour to take it off again - which is how every
 * curve editor on a touch screen does it, because there is nowhere to put a
 * delete button that a finger is not already covering.
 *
 * The two ends cannot be removed: they are the black point and the white point,
 * and a curve without them has no answer at 0 or 1.
 */
@Composable
fun CurveEditor(
    curve: ToneCurve,
    onChange: (ToneCurve) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit
) {
    var channel by rememberSaveable { mutableStateOf(CurveChannel.Master) }
    val latest by rememberUpdatedState(curve)
    val finished by rememberUpdatedState(onFinished)
    val change by rememberUpdatedState(onChange)
    // Which point the finger took hold of when it landed. Held for the whole
    // drag: picking the nearest point on every event lets a fast drag jump to
    // its neighbour halfway through.
    var dragging by remember { mutableStateOf(-1) }
    val touchSlop = with(LocalDensity.current) { 24.dp.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CurveChannel.entries.forEach { c ->
                val touched = !curve.of(c).isIdentity
                SelectableChip(
                    label = if (touched) "${c.label} •" else c.label,
                    selected = channel == c,
                    accentColor = SquishColors.Blue,
                    onClick = { channel = c }
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(channel) {
                    detectTapGestures { at ->
                        val x = (at.x / size.width).coerceIn(0f, 1f)
                        val y = 1f - (at.y / size.height).coerceIn(0f, 1f)
                        val points = latest.of(channel).points
                        // Only on empty space: a tap on a point is how you
                        // choose it, not how you stack a second one on it.
                        val onTop = points.any {
                            hypot((it.x - x) * size.width, ((1f - it.y) - (1f - y)) * size.height) < touchSlop
                        }
                        if (!onTop) {
                            change(latest.with(channel, Curve((points + CurvePoint(x, y)).sortedBy { it.x })))
                            finished()
                        }
                    }
                }
                .pointerInput(channel) {
                    detectDragGestures(
                        onDragStart = { at ->
                            val x = at.x / size.width
                            val y = 1f - at.y / size.height
                            val points = latest.of(channel).points
                            dragging = points.indices.minByOrNull {
                                hypot(points[it].x - x, points[it].y - y)
                            }?.takeIf { i ->
                                hypot((points[i].x - x) * size.width, (points[i].y - y) * size.height) < touchSlop
                            } ?: -1
                        },
                        onDragEnd = { dragging = -1; finished() },
                        onDragCancel = { dragging = -1; finished() }
                    ) { _, drag ->
                        val i = dragging
                        if (i < 0) return@detectDragGestures
                        val points = latest.of(channel).points.toMutableList()
                        if (i >= points.size) return@detectDragGestures
                        val old = points[i]
                        // Held between its neighbours, so the sort below can
                        // never reorder the list under the gesture. [dragging]
                        // is an index into the sorted points, and a point
                        // carried past a neighbour in one event used to swap
                        // with it - from there the finger was dragging the
                        // other point, and the one it had hold of ran away.
                        // It can still reach a neighbour, which is what
                        // [tooClose] below reads as "take this one off".
                        val floor = if (i > 0) points[i - 1].x else 0f
                        val ceiling = if (i < points.lastIndex) points[i + 1].x else 1f
                        val x = (old.x + drag.x / size.width).coerceIn(0f, 1f).coerceIn(floor, ceiling)
                        val y = (old.y - drag.y / size.height).coerceIn(0f, 1f)
                        val ends = i == 0 || i == points.lastIndex
                        // An end keeps its level and only moves up and down; a
                        // black point that slides sideways is a curve that
                        // starts somewhere other than black.
                        points[i] = if (ends) CurvePoint(old.x, y) else CurvePoint(x, y)
                        val next = if (!ends && tooClose(points, i)) {
                            // Dragged onto a neighbour: taken off, which is how
                            // a point is deleted on a touch screen.
                            points.removeAt(i).let { dragging = -1; points }
                        } else points
                        change(latest.with(channel, Curve(next.sortedBy { it.x })))
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            // The grid: thirds, so the eye has something to place a point against.
            for (i in 1..3) {
                val at = i / 4f
                drawLine(SquishColors.Border, Offset(at * w, 0f), Offset(at * w, h), 1f)
                drawLine(SquishColors.Border, Offset(0f, at * h), Offset(w, at * h), 1f)
            }
            // The diagonal: where the curve would be if it did nothing.
            drawLine(SquishColors.Border, Offset(0f, h), Offset(w, 0f), 2f)

            // The other three channels behind, dimmed, so a red curve is not
            // drawn in a vacuum.
            CurveChannel.entries.filter { it != channel && !curve.of(it).isIdentity }.forEach { other ->
                drawCurve(curve.of(other), w, h, other.tint.copy(alpha = 0.35f), 2f)
            }

            val drawn = curve.of(channel)
            drawCurve(drawn, w, h, channel.tint, 3f)
            drawn.points.forEach { p ->
                val at = Offset(p.x * w, (1f - p.y) * h)
                drawCircle(SquishColors.Background, radius = 9f, center = at)
                drawCircle(channel.tint, radius = 9f, center = at, style = Stroke(width = 3f))
            }
        }

        Text(
            "Drag a point. Tap the square to add one. Drag a point onto its neighbour to take it off.",
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextMuted
        )
        if (!curve.isIdentity) {
            SquishOutlinedButton(text = "Straighten every channel", modifier = Modifier.fillMaxWidth(), onClick = onReset)
        }
    }
}

/** Two points closer than a level apart cannot both be read; see Curve.X_EPS. */
private fun tooClose(points: List<CurvePoint>, i: Int): Boolean =
    points.indices.any { it != i && abs(points[it].x - points[i].x) < Curve.X_EPS * 2f }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurve(
    curve: Curve,
    w: Float,
    h: Float,
    colour: Color,
    width: Float
) {
    // Walked at the width of the square in pixels, so the line drawn is the
    // table the shader reads rather than a spline of its own.
    val path = Path()
    val steps = w.toInt().coerceIn(32, 512)
    for (i in 0..steps) {
        val x = i.toFloat() / steps
        val y = curve.valueAt(x)
        val at = Offset(x * w, (1f - y) * h)
        if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
    }
    drawPath(path, colour, style = Stroke(width = width))
}
