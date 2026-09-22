package com.squish.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import com.squish.app.ui.theme.SquishColors

/**
 * The Squish mark: a play triangle cut clean through. Play for video, the cut
 * because this is an editor and not a player.
 *
 * Drawn rather than loaded, from the same 108-unit geometry as the launcher icon
 * and the launch animation, so the three cannot drift apart. Vector all the way
 * down, so it is crisp at any size and can be driven by Compose animation.
 */
@Composable
fun SquishLogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val k = size.minDimension / 108f

        val mark = Path().apply {
            moveTo(42.97f * k, 63.04f * k)
            cubicTo(41.47f * k, 63.81f * k, 40.24f * k, 65.81f * k, 40.24f * k, 67.5f * k)
            lineTo(40.24f * k, 69.54f * k)
            cubicTo(40.24f * k, 72.34f * k, 42.29f * k, 73.61f * k, 44.8f * k, 72.36f * k)
            lineTo(76.27f * k, 56.62f * k)
            cubicTo(79.16f * k, 55.18f * k, 79.16f * k, 52.82f * k, 76.27f * k, 51.38f * k)
            lineTo(73.28f * k, 49.88f * k)
            cubicTo(72.04f * k, 49.26f * k, 70.01f * k, 49.27f * k, 68.77f * k, 49.9f * k)
            lineTo(42.97f * k, 63.04f * k)
            close()
            moveTo(47.48f * k, 36.98f * k)
            cubicTo(43.5f * k, 34.99f * k, 40.24f * k, 37.0f * k, 40.24f * k, 41.45f * k)
            lineTo(40.24f * k, 49.43f * k)
            cubicTo(40.24f * k, 53.92f * k, 43.51f * k, 55.92f * k, 47.51f * k, 53.88f * k)
            lineTo(55.41f * k, 49.86f * k)
            cubicTo(60.27f * k, 47.38f * k, 60.25f * k, 43.37f * k, 55.38f * k, 40.93f * k)
            lineTo(47.48f * k, 36.98f * k)
            close()
        }

        drawPath(
            path = mark,
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to SquishColors.Cyan,
                    0.36f to SquishColors.Blue,
                    0.68f to SquishColors.Violet,
                    1.00f to SquishColors.Magenta
                ),
                start = Offset(18f * k, 18f * k),
                end = Offset(90f * k, 90f * k)
            )
        )
    }
}
