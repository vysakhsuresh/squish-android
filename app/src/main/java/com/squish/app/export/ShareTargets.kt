package com.squish.app.export

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Glyphs for the share row: line drawings with rounded ends, the way current
 * app icons and share sheets draw them - not the solid silhouettes of a few
 * years ago. Each sits on its own gradient tile, see [ShareStyle].
 *
 * Simplified marks, recognisable at 26dp, drawn here because Material ships no
 * WhatsApp or Instagram glyph.
 */
object ShareGlyphs {

    private const val LINE = 1.9f

    /** A round speech bubble with its tail, and a handset inside. */
    val WhatsApp: ImageVector by lazy {
        builder("WhatsApp").apply {
            stroke {
                moveTo(4.6f, 19.4f)
                lineTo(5.5f, 15.9f)
                curveTo(4.8f, 14.7f, 4.4f, 13.4f, 4.4f, 12f)
                curveTo(4.4f, 7.8f, 7.8f, 4.4f, 12f, 4.4f)
                curveTo(16.2f, 4.4f, 19.6f, 7.8f, 19.6f, 12f)
                curveTo(19.6f, 16.2f, 16.2f, 19.6f, 12f, 19.6f)
                curveTo(10.6f, 19.6f, 9.3f, 19.2f, 8.1f, 18.5f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(9.6f, 8.6f)
                curveTo(9.4f, 8.2f, 9.2f, 8.2f, 9.0f, 8.2f)
                lineTo(8.6f, 8.2f)
                curveTo(8.4f, 8.2f, 8.1f, 8.3f, 8.1f, 8.8f)
                curveTo(8.1f, 9.7f, 8.7f, 10.6f, 8.8f, 10.8f)
                curveTo(8.9f, 10.9f, 10.1f, 12.8f, 12f, 13.6f)
                curveTo(13.6f, 14.3f, 13.9f, 14.1f, 14.2f, 14.1f)
                curveTo(14.6f, 14f, 15.3f, 13.7f, 15.4f, 13.3f)
                curveTo(15.6f, 12.9f, 15.6f, 12.6f, 15.5f, 12.5f)
                lineTo(14.2f, 11.8f)
                curveTo(14f, 11.7f, 13.8f, 11.7f, 13.7f, 11.9f)
                lineTo(13.2f, 12.5f)
                curveTo(13.1f, 12.6f, 12.9f, 12.6f, 12.8f, 12.6f)
                curveTo(12.5f, 12.5f, 11.8f, 12.2f, 11.2f, 11.6f)
                curveTo(10.7f, 11.1f, 10.4f, 10.6f, 10.3f, 10.4f)
                curveTo(10.2f, 10.3f, 10.3f, 10.2f, 10.4f, 10.1f)
                lineTo(10.7f, 9.7f)
                curveTo(10.8f, 9.6f, 10.8f, 9.5f, 10.8f, 9.3f)
                close()
            }
        }.build()
    }

    /** Camera outline: rounded square, lens, flash dot. */
    val Instagram: ImageVector by lazy {
        builder("Instagram").apply {
            stroke {
                moveTo(8f, 3.8f)
                lineTo(16f, 3.8f)
                curveTo(18.3f, 3.8f, 20.2f, 5.7f, 20.2f, 8f)
                lineTo(20.2f, 16f)
                curveTo(20.2f, 18.3f, 18.3f, 20.2f, 16f, 20.2f)
                lineTo(8f, 20.2f)
                curveTo(5.7f, 20.2f, 3.8f, 18.3f, 3.8f, 16f)
                lineTo(3.8f, 8f)
                curveTo(3.8f, 5.7f, 5.7f, 3.8f, 8f, 3.8f)
                close()
            }
            stroke {
                moveTo(12f, 8.2f)
                curveTo(14.1f, 8.2f, 15.8f, 9.9f, 15.8f, 12f)
                curveTo(15.8f, 14.1f, 14.1f, 15.8f, 12f, 15.8f)
                curveTo(9.9f, 15.8f, 8.2f, 14.1f, 8.2f, 12f)
                curveTo(8.2f, 9.9f, 9.9f, 8.2f, 12f, 8.2f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(16.9f, 6f)
                curveTo(17.5f, 6f, 18f, 6.5f, 18f, 7.1f)
                curveTo(18f, 7.7f, 17.5f, 8.2f, 16.9f, 8.2f)
                curveTo(16.3f, 8.2f, 15.8f, 7.7f, 15.8f, 7.1f)
                curveTo(15.8f, 6.5f, 16.3f, 6f, 16.9f, 6f)
                close()
            }
        }.build()
    }

    /** Envelope with its flap. */
    val Mail: ImageVector by lazy {
        builder("Mail").apply {
            stroke {
                moveTo(6f, 5.5f)
                lineTo(18f, 5.5f)
                curveTo(19.4f, 5.5f, 20.5f, 6.6f, 20.5f, 8f)
                lineTo(20.5f, 16f)
                curveTo(20.5f, 17.4f, 19.4f, 18.5f, 18f, 18.5f)
                lineTo(6f, 18.5f)
                curveTo(4.6f, 18.5f, 3.5f, 17.4f, 3.5f, 16f)
                lineTo(3.5f, 8f)
                curveTo(3.5f, 6.6f, 4.6f, 5.5f, 6f, 5.5f)
                close()
            }
            stroke {
                moveTo(4.2f, 7.2f)
                lineTo(10.9f, 12.3f)
                curveTo(11.6f, 12.8f, 12.4f, 12.8f, 13.1f, 12.3f)
                lineTo(19.8f, 7.2f)
            }
        }.build()
    }

    /** An arrow leaving a tray: send it anywhere else. */
    val More: ImageVector by lazy {
        builder("More").apply {
            stroke {
                moveTo(12f, 14.5f)
                lineTo(12f, 3.8f)
                moveTo(7.8f, 7.8f)
                lineTo(12f, 3.6f)
                lineTo(16.2f, 7.8f)
            }
            stroke {
                moveTo(8.5f, 10.5f)
                lineTo(7f, 10.5f)
                curveTo(5.6f, 10.5f, 4.5f, 11.6f, 4.5f, 13f)
                lineTo(4.5f, 17.7f)
                curveTo(4.5f, 19.1f, 5.6f, 20.2f, 7f, 20.2f)
                lineTo(17f, 20.2f)
                curveTo(18.4f, 20.2f, 19.5f, 19.1f, 19.5f, 17.7f)
                lineTo(19.5f, 13f)
                curveTo(19.5f, 11.6f, 18.4f, 10.5f, 17f, 10.5f)
                lineTo(15.5f, 10.5f)
            }
        }.build()
    }

    private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = LINE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
}

/** Each target's tile: its own gradient, drawn corner to corner. */
object ShareStyle {
    val WhatsApp = Brush.linearGradient(listOf(Color(0xFF5CF08A), Color(0xFF25D366), Color(0xFF0E8F6E)))
    val Instagram = Brush.linearGradient(
        listOf(Color(0xFFFFD36E), Color(0xFFFF7A2F), Color(0xFFE1306C), Color(0xFFA23BD6), Color(0xFF5B5BF0))
    )
    val Mail = Brush.linearGradient(listOf(Color(0xFF6FB1FF), Color(0xFF4F7BFF), Color(0xFF7B5CFF)))
    val More = Brush.linearGradient(listOf(Color(0xFF3A3F63), Color(0xFF262A45)))
}
