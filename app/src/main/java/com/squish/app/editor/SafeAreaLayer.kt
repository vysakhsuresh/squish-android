package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

/**
 * The platform's own furniture, drawn over the picture.
 *
 * Dimmed where the app's buttons and caption will sit, with a dashed line round
 * what is left. Nothing here reaches the file - it is a guide for putting your
 * words somewhere they will still be readable after posting, which is a thing
 * you otherwise find out from the comments.
 */
@Composable
fun SafeAreaLayer(area: SafeArea, modifier: Modifier = Modifier) {
    val insets = area.insets
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val top = insets.top * h
            val bottom = insets.bottom * h
            val left = insets.left * w
            val right = insets.right * w
            val veil = Color.Black.copy(alpha = 0.32f)

            // The four covered bands. Drawn as bands rather than as a hole in a
            // full veil so the corners are not dimmed twice and darker.
            drawRect(veil, Offset(0f, 0f), Size(w, top))
            drawRect(veil, Offset(0f, h - bottom), Size(w, bottom))
            drawRect(veil, Offset(0f, top), Size(left, h - top - bottom))
            drawRect(veil, Offset(w - right, top), Size(right, h - top - bottom))

            drawRect(
                color = SquishColors.Teal.copy(alpha = 0.9f),
                topLeft = Offset(left, top),
                size = Size(w - left - right, h - top - bottom),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                )
            )
        }
        // Inside the clear rectangle, just under its top edge, so the label is
        // never itself sitting in the part it is warning about.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "${area.label} · the clear part",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Teal,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = maxHeight * insets.top + 6.dp)
            )
        }
    }
}
