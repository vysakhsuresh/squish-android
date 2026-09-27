package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.squish.app.ui.theme.SquishColors

/**
 * Thirds guides over the framed picture, and a dim over anything that is not it.
 *
 * The preview itself now crops, so the guides are the working part: this is where
 * you put a face or a horizon, and a crop chosen without them is a crop chosen by
 * feel. The dim covers the letterbox around the framed rectangle, which is the
 * container's leftover space rather than discarded footage.
 */
@Composable
fun CropOverlay(
    aspect: CropAspect,
    /** Where auto-reframe has the crop centred right now; the middle when null. */
    focus: Pair<Float, Float>? = null,
    modifier: Modifier = Modifier
) {
    val ratio = aspect.ratio ?: return

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Largest frame of the target ratio that fits the preview.
        val frameW: Float
        val frameH: Float
        if (w / h > ratio) {
            frameH = h
            frameW = h * ratio
        } else {
            frameW = w
            frameH = w / ratio
        }
        val (fx, fy) = focus ?: (0.5f to 0.5f)
        val left = (fx * w - frameW / 2f).coerceIn(0f, (w - frameW).coerceAtLeast(0f))
        val top = (fy * h - frameH / 2f).coerceIn(0f, (h - frameH).coerceAtLeast(0f))
        val dim = Color.Black.copy(alpha = 0.58f)

        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(w, top))
        drawRect(dim, topLeft = Offset(0f, top + frameH), size = Size(w, (h - top - frameH).coerceAtLeast(0f)))
        drawRect(dim, topLeft = Offset(0f, top), size = Size(left, frameH))
        drawRect(dim, topLeft = Offset(left + frameW, top), size = Size((w - left - frameW).coerceAtLeast(0f), frameH))

        drawRect(
            color = SquishColors.Violet,
            topLeft = Offset(left, top),
            size = Size(frameW, frameH),
            style = Stroke(width = 2f)
        )

        val guide = SquishColors.TextPrimary.copy(alpha = 0.22f)
        for (i in 1..2) {
            val x = left + frameW * i / 3f
            val y = top + frameH * i / 3f
            drawLine(guide, Offset(x, top), Offset(x, top + frameH), strokeWidth = 1f)
            drawLine(guide, Offset(left, y), Offset(left + frameW, y), strokeWidth = 1f)
        }
    }
}
