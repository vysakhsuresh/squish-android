package com.squish.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

/**
 * The Squish mark: a coral rounded square with a forward arrow/chevron glyph.
 * Pure vector drawing (no bitmap assets) so it scales crisply at any size and
 * can be driven by Compose animations (see splash/SplashScreen.kt).
 */
@Composable
fun SquishLogoMark(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp
) {
    Box(
        modifier = modifier.background(SquishColors.Coral, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val strokeWidth = w * 0.11f
            val midY = h / 2f
            val path = Path().apply {
                moveTo(w * 0.24f, midY)
                lineTo(w * 0.58f, midY)
                moveTo(w * 0.46f, h * 0.30f)
                lineTo(w * 0.78f, midY)
                lineTo(w * 0.46f, h * 0.70f)
            }
            drawPath(
                path = path,
                color = SquishColors.Background,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
