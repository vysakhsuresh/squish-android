package com.squish.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * One line that shrinks until it fits rather than being cut: a tile's name or
 * sample at the phone's larger font sizes. Cut, "Disposable" read
 * "Disposabl" and "Day 1 in Goa" was clipped to "Day 1 in G". Never below
 * [minScale] of the style's size; past that it is cut after all.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Center,
    minScale: Float = 0.6f,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip
) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    // The width the layout before this one was given. A box that grows takes
    // the line back to full size; see TextFit.nextScale for why it was stuck.
    var fittedAt by remember(text, style) { mutableIntStateOf(0) }
    Text(
        text,
        modifier = modifier,
        style = style.copy(fontSize = style.fontSize * scale),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = overflow,
        textAlign = textAlign,
        onTextLayout = { result ->
            val room = result.layoutInput.constraints.maxWidth
            val overflowing = result.hasVisualOverflow ||
                (result.lineCount > 0 && result.isLineEllipsized(0))
            val next = TextFit.nextScale(scale, overflowing, room, fittedAt, minScale)
            fittedAt = room
            if (next != scale) scale = next
        }
    )
}
