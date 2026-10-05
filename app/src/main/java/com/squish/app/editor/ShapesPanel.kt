package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.theme.SquishColors

/*
 * Shapes and arrows: the one annotation every reviewer of a product video asks
 * for, and the thing all three competitors have.
 *
 * A shape is a caption with no words - the same track, the same box on the
 * picture, the same motions, the same export - so nothing here is a second
 * system to keep in step with the first. What it is drawn from, points in a
 * box, lives in Annotation.kt, and both this picker and the renderer read it,
 * so the tile on the sheet is the shape that lands.
 */

/**
 * The shapes on offer, above the stickers: tap one and it lands at the
 * playhead, in the middle of the picture, outlined in red.
 */
@Composable
fun ShapesCard(viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading(
            "Shapes and arrows",
            "Tap one to put it on at the playhead · outlined, so what it marks still shows",
            icon = Icons.Filled.Rectangle,
            accent = SquishColors.Amber
        )
        AnnotationShape.offered.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { shape ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SquishColors.Background)
                            .clickable(onClickLabel = shape.label) { viewModel.text.addShape(shape) }
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ShapeGlyph(
                            shape = shape,
                            color = Color(SHAPE_TILE_COLOR),
                            filled = false,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.5f)
                        )
                    }
                }
                repeat(4 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One shape drawn, from the same points the renderer paints - so a tile is the
 * shape it adds rather than an icon that resembles it.
 */
@Composable
fun ShapeGlyph(shape: AnnotationShape, color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        // Laid out at the shape's own proportions inside whatever room there is,
        // so a long arrow is drawn long and a star stays square.
        val aspect = shape.defaultAspect
        val boxWidth: Float
        val boxHeight: Float
        if (size.width / size.height > aspect) {
            boxHeight = size.height
            boxWidth = boxHeight * aspect
        } else {
            boxWidth = size.width
            boxHeight = boxWidth / aspect
        }
        val thickness = ShapeGeometry.thicknessPx(boxWidth, boxHeight, ShapeGeometry.DEFAULT_THICKNESS)
        val inset = thickness / 2f
        val w = (boxWidth - thickness).coerceAtLeast(1f)
        val h = (boxHeight - thickness).coerceAtLeast(1f)
        val left = (size.width - boxWidth) / 2f + inset
        val top = (size.height - boxHeight) / 2f + inset
        drawShape(shape, w, h, left, top, color, filled, thickness)
    }
}

/**
 * The shape's points laid into a Compose canvas, at ([left], [top]) in a box
 * [w] by [h]. The body is filled or stroked; an arrow's head is always solid,
 * because a hollow head reads as a chevron.
 */
private fun DrawScope.drawShape(
    shape: AnnotationShape,
    w: Float,
    h: Float,
    left: Float,
    top: Float,
    color: Color,
    filled: Boolean,
    thickness: Float
) {
    val polys = ShapeGeometry.polys(shape, w, h)
    val body = Path()
    val head = Path()
    polys.forEach { poly ->
        val into = if (poly.role == ShapeRole.Head) head else body
        poly.points.forEachIndexed { i, point ->
            val x = left + point.x
            val y = top + point.y
            if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
        }
        if (poly.closed) into.close()
    }
    val solid = filled && shape.hasInside
    drawPath(
        path = body,
        color = color,
        style = if (solid) Fill else Stroke(width = thickness, join = StrokeJoin.Round)
    )
    if (!head.isEmpty) drawPath(path = head, color = color, style = Fill)
}

/**
 * A shape selected: which shape it is, whether it is solid, how thick its line
 * is, how wide it stands, its colour, and where it sits.
 *
 * It takes the sticker's Placement slot rather than a sheet of its own - a
 * shape is placed the same way a sticker is, and the four things above it are
 * all a shape has that a sticker does not.
 */
@Composable
fun ShapePlacementPanel(
    item: TextOverlayItem,
    viewModel: EditorViewModel,
    onEyedropper: ((Int) -> Unit) -> Unit
) {
    // Keyed to the shape, as the text sheets' pickers are. The colour picker's
    // two gestures are keyed pointerInput(Unit), so they hold the onChange they
    // were created with; left open across a change of selection - and Placement
    // survives one - the hue strip and the shade square went on restyling the
    // shape that was selected when it opened, while the heading and the hex
    // readout had already moved to the new one.
    var pickerOpen by remember(item.id) { mutableStateOf(false) }
    val onDrag = { change: (TextOverlayItem) -> TextOverlayItem ->
        viewModel.text.restyleCaption(item.id, change, dragging = true)
    }
    PanelSurface(accent = SquishColors.Amber) {
        PanelHeading(
            item.shape.label,
            "${Timecode.format(item.startMs)} → ${Timecode.format(item.endMs)} · or drag it on the picture",
            icon = Icons.Filled.Rectangle,
            accent = SquishColors.Amber
        )

        AnnotationShape.offered.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { shape ->
                    val selected = shape == item.shape
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) SquishColors.Amber.copy(alpha = 0.18f) else SquishColors.Background)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) SquishColors.Amber else SquishColors.Border,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable(onClickLabel = shape.label) { viewModel.text.setShape(item.id, shape) }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ShapeGlyph(
                            shape = shape,
                            color = Color(item.colorArgb),
                            filled = item.shapeFilled,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.5f)
                        )
                    }
                }
                repeat(4 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }

        if (item.shape.hasInside) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SelectableChip(label = "Outline", selected = !item.shapeFilled, accentColor = SquishColors.Amber) {
                    viewModel.text.setShapeFilled(item.id, false)
                }
                SelectableChip(label = "Solid", selected = item.shapeFilled, accentColor = SquishColors.Amber) {
                    viewModel.text.setShapeFilled(item.id, true)
                }
            }
        }

        // The thickness lives on the stroke, which is where a line of words
        // keeps its edge: one field, one slider, one thing in the draft.
        if (!item.shapeFilled || !item.shape.hasInside) {
            LabeledSlider(
                "Line",
                item.stroke.width.takeIf { it > 0f } ?: ShapeGeometry.DEFAULT_THICKNESS,
                ShapeGeometry.MIN_THICKNESS..ShapeGeometry.MAX_THICKNESS,
                onFinished = viewModel::endGesture
            ) { v ->
                onDrag { it.copy(stroke = it.stroke.copy(width = v)) }
            }
        }

        LabeledSlider(
            "Width",
            item.shapeAspect,
            ShapeGeometry.MIN_ASPECT..ShapeGeometry.MAX_ASPECT,
            readout = Readout.times,
            onFinished = viewModel::endGesture
        ) { v ->
            onDrag { it.copy(shapeAspect = v) }
        }

        ColourRow(
            title = "Colour",
            argb = item.colorArgb,
            open = pickerOpen,
            onOpen = { pickerOpen = it },
            onPick = { colour -> viewModel.text.restyleCaption(item.id, { it.copy(colorArgb = colour) }) },
            onDrag = { colour -> onDrag { it.copy(colorArgb = colour) } },
            onFinished = viewModel::endGesture,
            onEyedropper = onEyedropper
        )

        LabeledSlider("Across", item.xFraction * 2 - 1, -1f..1f, onFinished = viewModel::endGesture) { v ->
            onDrag { it.copy(xFraction = ((v + 1) / 2).coerceIn(0.02f, 0.98f)) }
        }
        LabeledSlider("Up / down", item.yFraction * 2 - 1, -1f..1f, onFinished = viewModel::endGesture) { v ->
            onDrag { it.copy(yFraction = ((v + 1) / 2).coerceIn(0.02f, 0.98f)) }
        }
        LabeledSlider(
            "Size",
            item.sizeSp / ShapeGeometry.DEFAULT_SIZE_SP.toFloat(),
            0.2f..1.8f,
            readout = Readout.times,
            onFinished = viewModel::endGesture
        ) { v ->
            onDrag {
                it.copy(
                    sizeSp = (v * ShapeGeometry.DEFAULT_SIZE_SP).toInt()
                        .coerceIn(TextStyleSpec.MIN_SIZE_SP, TextStyleSpec.MAX_SIZE_SP)
                )
            }
        }
        LabeledSlider("Turn", item.rotationDegrees, -180f..180f, readout = Readout.degrees, onFinished = viewModel::endGesture) { v ->
            onDrag { it.copy(rotationDegrees = v) }
        }
        OptionToggle(label = if (item.flipped) "Facing the other way" else "Flip to face the other way", active = item.flipped) {
            viewModel.text.flip(item.id)
        }
        Text(
            "The box on the picture moves and sizes it too — and its corners delete, copy, open this and resize.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}

/** What a shape tile is drawn in on the picker, before one has a colour of its own. */
private const val SHAPE_TILE_COLOR = 0xFFFF3B30.toInt()
