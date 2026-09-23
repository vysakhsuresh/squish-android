package com.squish.app.export

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Glyphs for the share row.
 *
 * Material ships no mark for WhatsApp or Instagram, and a row of plain coloured
 * circles is unreadable - the only thing telling them apart was the colour, which
 * is exactly what a colour-blind user does not have. These are drawn here instead:
 * simplified single-colour silhouettes, the same treatment a system share sheet
 * uses, recognisable at 24dp without reproducing anyone's artwork.
 */
object ShareGlyphs {

    /**
     * A speech bubble with the handset punched out of it.
     *
     * One path, even-odd filled: the handset is an inner subpath, so it renders as
     * a hole and the ground shows through. That is how the real mark reads, and it
     * survives being tinted any colour - which a two-layer version would not.
     */
    val WhatsApp: ImageVector by lazy {
        builder("WhatsApp").apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(12f, 2f)
                curveTo(6.9f, 2f, 2.8f, 6.1f, 2.8f, 11.2f)
                curveTo(2.8f, 12.9f, 3.2f, 14.5f, 4.1f, 15.9f)
                lineTo(2.6f, 21.4f)
                lineTo(8.3f, 19.9f)
                curveTo(9.6f, 20.6f, 10.8f, 20.9f, 12f, 20.9f)
                curveTo(17.1f, 20.9f, 21.2f, 16.3f, 21.2f, 11.2f)
                curveTo(21.2f, 6.1f, 17.1f, 2f, 12f, 2f)
                close()

                moveTo(9.2f, 7.4f)
                curveTo(9.0f, 7.0f, 8.8f, 7.0f, 8.5f, 7.0f)
                lineTo(7.9f, 7.0f)
                curveTo(7.6f, 7.0f, 7.2f, 7.2f, 7.2f, 7.8f)
                curveTo(7.2f, 9.0f, 8.0f, 10.2f, 8.2f, 10.4f)
                curveTo(8.3f, 10.6f, 9.8f, 13.0f, 12.2f, 14.0f)
                curveTo(14.2f, 14.8f, 14.6f, 14.6f, 15.0f, 14.6f)
                curveTo(15.5f, 14.5f, 16.3f, 14.1f, 16.5f, 13.6f)
                curveTo(16.7f, 13.1f, 16.7f, 12.7f, 16.6f, 12.6f)
                curveTo(16.5f, 12.5f, 16.3f, 12.4f, 16.0f, 12.3f)
                lineTo(14.7f, 11.6f)
                curveTo(14.4f, 11.5f, 14.2f, 11.5f, 14.0f, 11.8f)
                lineTo(13.4f, 12.6f)
                curveTo(13.3f, 12.8f, 13.1f, 12.8f, 12.9f, 12.7f)
                curveTo(12.6f, 12.6f, 11.6f, 12.2f, 10.8f, 11.4f)
                curveTo(10.2f, 10.8f, 9.8f, 10.1f, 9.7f, 9.9f)
                curveTo(9.6f, 9.7f, 9.7f, 9.6f, 9.8f, 9.5f)
                lineTo(10.2f, 9.0f)
                curveTo(10.3f, 8.8f, 10.3f, 8.7f, 10.4f, 8.5f)
                curveTo(10.5f, 8.3f, 10.4f, 8.2f, 10.4f, 8.1f)
                lineTo(9.2f, 7.4f)
                close()
            }
        }.build()
    }

    /** Rounded square, lens, and the corner dot. */
    val Instagram: ImageVector by lazy {
        builder("Instagram").apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd
            ) {
                // Outer body, with the inner rounded square cut out of it.
                moveTo(7.5f, 2.5f)
                lineTo(16.5f, 2.5f)
                curveTo(19.3f, 2.5f, 21.5f, 4.7f, 21.5f, 7.5f)
                lineTo(21.5f, 16.5f)
                curveTo(21.5f, 19.3f, 19.3f, 21.5f, 16.5f, 21.5f)
                lineTo(7.5f, 21.5f)
                curveTo(4.7f, 21.5f, 2.5f, 19.3f, 2.5f, 16.5f)
                lineTo(2.5f, 7.5f)
                curveTo(2.5f, 4.7f, 4.7f, 2.5f, 7.5f, 2.5f)
                close()
                moveTo(7.5f, 4.5f)
                curveTo(5.8f, 4.5f, 4.5f, 5.8f, 4.5f, 7.5f)
                lineTo(4.5f, 16.5f)
                curveTo(4.5f, 18.2f, 5.8f, 19.5f, 7.5f, 19.5f)
                lineTo(16.5f, 19.5f)
                curveTo(18.2f, 19.5f, 19.5f, 18.2f, 19.5f, 16.5f)
                lineTo(19.5f, 7.5f)
                curveTo(19.5f, 5.8f, 18.2f, 4.5f, 16.5f, 4.5f)
                close()
            }
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd
            ) {
                // The lens ring.
                moveTo(12f, 7f)
                curveTo(14.8f, 7f, 17f, 9.2f, 17f, 12f)
                curveTo(17f, 14.8f, 14.8f, 17f, 12f, 17f)
                curveTo(9.2f, 17f, 7f, 14.8f, 7f, 12f)
                curveTo(7f, 9.2f, 9.2f, 7f, 12f, 7f)
                close()
                moveTo(12f, 9f)
                curveTo(10.3f, 9f, 9f, 10.3f, 9f, 12f)
                curveTo(9f, 13.7f, 10.3f, 15f, 12f, 15f)
                curveTo(13.7f, 15f, 15f, 13.7f, 15f, 12f)
                curveTo(15f, 10.3f, 13.7f, 9f, 12f, 9f)
                close()
            }
            path(fill = SolidColor(Color.White)) {
                // The flash dot.
                moveTo(17.3f, 5.5f)
                curveTo(18.0f, 5.5f, 18.5f, 6.0f, 18.5f, 6.7f)
                curveTo(18.5f, 7.4f, 18.0f, 7.9f, 17.3f, 7.9f)
                curveTo(16.6f, 7.9f, 16.1f, 7.4f, 16.1f, 6.7f)
                curveTo(16.1f, 6.0f, 16.6f, 5.5f, 17.3f, 5.5f)
                close()
            }
        }.build()
    }

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
}
