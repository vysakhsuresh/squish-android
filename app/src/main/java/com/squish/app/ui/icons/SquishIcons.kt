package com.squish.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Squish's own marks: chunky filled shapes with a dark cartoon outline, a sunny
 * yellow, pink and lilac to pick details out, and a face wherever a thing could
 * plausibly have one. Sticker art rather than signage - the stock Material set
 * reads as a settings screen, and these are the fun parts of the app.
 *
 * They carry their own colours, so draw them with Image, not a tinted Icon,
 * which would flatten each one into a silhouette. All on a 24 by 24 grid.
 */
object SquishIcons {

    private val Ink = Color(0xFF140C26)
    private val White = Color.White
    private val Sun = Color(0xFFFFE066)
    private val Pink = Color(0xFFFF8FC7)
    private val Lilac = Color(0xFFDCD6FF)
    private val Tan = Color(0xFFFFB86B)
    private val Aqua = Color(0xFF7CF0FF)
    private val Rec = Color(0xFFFF4D6D)

    private const val OUTLINE = 1.3f

    // ---- Templates ----

    /** A phone with a play button on it, and a sparkle. */
    val Reel: ImageVector by lazy {
        icon("Reel") {
            shape { roundRect(6.5f, 2.5f, 17.5f, 21.5f, 3f) }
            shape(Lilac, outline = false) { roundRect(8.3f, 5f, 15.7f, 17.8f, 1.4f) }
            shape(Ink, outline = false) {
                moveTo(10.8f, 9f); lineTo(14.3f, 11.4f); lineTo(10.8f, 13.8f); close()
            }
            line { moveTo(10.8f, 19.7f); lineTo(13.2f, 19.7f) }
            shape(Sun) { sparkle(19.8f, 4.6f, 2.6f) }
        }
    }

    /** A camcorder with its record light on. */
    val Vlog: ImageVector by lazy {
        icon("Vlog") {
            shape(Lilac) {
                moveTo(15f, 10.5f); lineTo(21.5f, 7.5f); lineTo(21.5f, 17.5f); lineTo(15f, 14.5f); close()
            }
            shape { roundRect(2.5f, 7f, 15.5f, 18f, 3f) }
            shape(Ink, outline = false) { circle(9f, 12.5f, 3.2f) }
            shape(outline = false) { circle(10f, 11.5f, 0.9f) }
            shape(Rec, outline = false) { circle(5.3f, 9.6f, 1f) }
        }
    }

    /** A clapperboard, mid-clap. */
    val Cinematic: ImageVector by lazy {
        icon("Cinematic") {
            shape { roundRect(3f, 10f, 21f, 20.5f, 2.2f) }
            line { moveTo(6f, 14.3f); lineTo(12f, 14.3f); moveTo(6f, 17.2f); lineTo(15f, 17.2f) }
            shape(Sun) {
                moveTo(3f, 9.8f); lineTo(19.6f, 5.2f); lineTo(20.4f, 8f); lineTo(3.8f, 12.6f); close()
            }
            line(width = 1.6f) {
                moveTo(7.15f, 8.65f); lineTo(9.6f, 11f)
                moveTo(11.3f, 7.5f); lineTo(13.76f, 9.84f)
                moveTo(15.45f, 6.35f); lineTo(17.9f, 8.7f)
            }
        }
    }

    /** An old telly with bunny ears and a smile on screen. */
    val Retro: ImageVector by lazy {
        icon("Retro") {
            line { moveTo(8.5f, 3.2f); lineTo(12f, 6.8f); lineTo(15.8f, 2.8f) }
            shape(Pink) { circle(8.4f, 3f, 1f) }
            shape(Pink) { circle(15.9f, 2.7f, 1f) }
            line { moveTo(6.5f, 19.5f); lineTo(5.8f, 21.2f); moveTo(17.5f, 19.5f); lineTo(18.2f, 21.2f) }
            shape { roundRect(3f, 6.8f, 21f, 19.6f, 3.5f) }
            shape(Lilac) { roundRect(5.3f, 9f, 15.5f, 17.4f, 2f) }
            shape(Ink, outline = false) { circle(8.8f, 12f, 0.85f) }
            shape(Ink, outline = false) { circle(12f, 12f, 0.85f) }
            line { moveTo(8.8f, 14.3f); quadTo(10.4f, 15.7f, 12f, 14.3f) }
            shape(Ink, outline = false) { circle(18.2f, 11f, 1f) }
            shape(Sun) { circle(18.2f, 14.8f, 1f) }
        }
    }

    /** A party popper going off. */
    val Party: ImageVector by lazy {
        icon("Party") {
            outlinedLine(White) { moveTo(10.5f, 12.8f); curveTo(11.5f, 10.5f, 13.2f, 11.6f, 14f, 9.6f) }
            shape(Sun) { moveTo(3.2f, 20.8f); lineTo(7.2f, 9.8f); lineTo(14.2f, 16.8f); close() }
            line { moveTo(5.9f, 13.4f); lineTo(10.6f, 18.1f) }
            shape { sparkle(17f, 6.2f, 3.4f) }
            shape(Pink) { circle(10.8f, 4.6f, 1.2f) }
            shape(Aqua) { circle(19.6f, 13.2f, 1.2f) }
        }
    }

    /** A tilted instant photo with a heart in it. */
    val Memories: ImageVector by lazy {
        icon("Memories") {
            group(rotate = -8f, pivotX = 12f, pivotY = 12f) {
                shape { roundRect(4.5f, 3f, 19.5f, 21f, 2f) }
                shape(Lilac) { roundRect(6.5f, 5f, 17.5f, 15.5f, 1f) }
                shape(Pink) { heart(12f, 10.4f) }
                line { moveTo(9.5f, 18.3f); lineTo(14.5f, 18.3f) }
            }
        }
    }

    // ---- Effects ----

    /** A face having a wobble. */
    val Shake: ImageVector by lazy {
        icon("Shake") {
            outlinedLine(White) {
                moveTo(3.2f, 8.5f); quadTo(1.6f, 12f, 3.2f, 15.5f)
                moveTo(20.8f, 8.5f); quadTo(22.4f, 12f, 20.8f, 15.5f)
            }
            shape(Sun) { circle(12f, 12f, 6.6f) }
            shape(Ink, outline = false) { circle(9.8f, 10.9f, 1f) }
            shape(Ink, outline = false) { circle(14.2f, 10.9f, 1f) }
            line { moveTo(9.4f, 14.6f); quadTo(10.7f, 13.4f, 12f, 14.6f); quadTo(13.3f, 15.8f, 14.6f, 14.6f) }
        }
    }

    /** A comic-book POW burst. */
    val Punch: ImageVector by lazy {
        icon("Punch") {
            shape(Sun) {
                moveTo(12f, 2f); lineTo(15.65f, 6.98f); lineTo(21.51f, 8.91f); lineTo(17.9f, 13.92f)
                lineTo(17.88f, 20.09f); lineTo(12f, 18.2f); lineTo(6.12f, 20.09f); lineTo(6.1f, 13.92f)
                lineTo(2.49f, 8.91f); lineTo(8.35f, 6.98f); close()
            }
            line(width = 2.1f) { moveTo(12f, 8.6f); lineTo(12f, 12.4f) }
            shape(Ink, outline = false) { circle(12f, 15f, 1.1f) }
        }
    }

    /** A magnifier with a plus in the glass. */
    val ZoomIn: ImageVector by lazy {
        icon("ZoomIn") {
            outlinedLine(Pink, width = 2.2f) { moveTo(15.6f, 15.6f); lineTo(20.4f, 20.4f) }
            shape { circle(10.5f, 10.5f, 6.8f) }
            shape(Lilac, outline = false) { circle(10.5f, 10.5f, 4.8f) }
            line(width = 1.8f) { moveTo(10.5f, 8f); lineTo(10.5f, 13f); moveTo(8f, 10.5f); lineTo(13f, 10.5f) }
        }
    }

    /** Two frames slipping out of register, with torn scan lines. */
    val Glitch: ImageVector by lazy {
        icon("Glitch") {
            shape(Pink) { roundRect(3f, 4.5f, 15f, 16.5f, 2.5f) }
            shape(Aqua) { roundRect(8f, 7.5f, 20f, 19.5f, 2.5f) }
            shape(Ink, outline = false) { roundRect(5f, 10f, 12.5f, 11.4f, 0.5f) }
            shape(Ink, outline = false) { roundRect(11f, 14.6f, 21.5f, 16f, 0.5f) }
            shape { roundRect(2f, 18.8f, 4f, 20.8f, 0.4f) }
            shape { roundRect(19.8f, 3.2f, 21.8f, 5.2f, 0.4f) }
        }
    }

    /** A fat lightning bolt and two sparkles. */
    val Flash: ImageVector by lazy {
        icon("Flash") {
            shape(Sun) {
                moveTo(13.5f, 2.5f); lineTo(5.5f, 13.5f); lineTo(11f, 13.5f); lineTo(9.5f, 21.5f)
                lineTo(18.5f, 9.8f); lineTo(12.8f, 9.8f); close()
            }
            shape { sparkle(19f, 4.4f, 2.4f) }
            shape { sparkle(4.8f, 19f, 1.9f) }
        }
    }

    /** A tape with its two reels. */
    val Vhs: ImageVector by lazy {
        icon("Vhs") {
            shape { roundRect(2.5f, 6f, 21.5f, 18f, 2.5f) }
            shape(Lilac) { roundRect(6f, 8.6f, 18f, 14.2f, 1.5f) }
            shape(Ink, outline = false) { circle(9.2f, 11.4f, 1.7f) }
            shape(Ink, outline = false) { circle(14.8f, 11.4f, 1.7f) }
            shape(outline = false) { circle(9.2f, 11.4f, 0.6f) }
            shape(outline = false) { circle(14.8f, 11.4f, 0.6f) }
            shape(Pink, outline = false) { roundRect(7f, 15.3f, 17f, 16.8f, 0.75f) }
        }
    }

    /** Half light, half dark, each half with an eye. */
    val Mono: ImageVector by lazy {
        icon("Mono") {
            shape { circle(12f, 12f, 8f) }
            shape(Ink, outline = false) {
                moveTo(12f, 4f); arcTo(8f, 8f, 0f, false, true, 12f, 20f); close()
            }
            shape(Ink, outline = false) { circle(8.8f, 10.8f, 1.1f) }
            shape(outline = false) { circle(15.2f, 10.8f, 1.1f) }
            shape(Sun) { sparkle(19f, 4.8f, 2.5f) }
        }
    }

    /** A drop, flipped down the middle. */
    val Invert: ImageVector by lazy {
        icon("Invert") {
            shape(outline = false) { drop() }
            shape(Ink, outline = false) {
                moveTo(12f, 2.8f); curveTo(12f, 2.8f, 19f, 10.5f, 19f, 14.5f)
                curveTo(19f, 18.4f, 15.9f, 21.2f, 12f, 21.2f); close()
            }
            line { drop() }
            shape(Aqua, outline = false) { circle(8.6f, 14.6f, 1.2f) }
        }
    }

    /** A soft glow with a sparkle. */
    val Blur: ImageVector by lazy {
        icon("Blur") {
            shape(White.copy(alpha = 0.22f), outline = false) { circle(12f, 12.5f, 9f) }
            shape(White.copy(alpha = 0.38f), outline = false) { circle(12f, 12.5f, 7f) }
            shape(White.copy(alpha = 0.7f), outline = false) { circle(12f, 12.5f, 5f) }
            shape(outline = false) { circle(12f, 12.5f, 3.2f) }
            shape(Sun) { sparkle(18.6f, 5.4f, 2.6f) }
        }
    }

    /** A rainbow between two clouds. */
    val Rainbow: ImageVector by lazy {
        icon("Rainbow") {
            line(width = 4.2f) {
                moveTo(3f, 17f); arcTo(9f, 9f, 0f, false, true, 21f, 17f)
                moveTo(6f, 17f); arcTo(6f, 6f, 0f, false, true, 18f, 17f)
                moveTo(9f, 17f); arcTo(3f, 3f, 0f, false, true, 15f, 17f)
            }
            line(Pink, width = 2.6f) { moveTo(3f, 17f); arcTo(9f, 9f, 0f, false, true, 21f, 17f) }
            line(Sun, width = 2.6f) { moveTo(6f, 17f); arcTo(6f, 6f, 0f, false, true, 18f, 17f) }
            line(Aqua, width = 2.6f) { moveTo(9f, 17f); arcTo(3f, 3f, 0f, false, true, 15f, 17f) }
            shape { circle(4.6f, 16.2f, 2.1f) }
            shape { roundRect(1.5f, 16f, 8.2f, 20.4f, 2.2f) }
            shape { circle(19.4f, 16.2f, 2.1f) }
            shape { roundRect(15.8f, 16f, 22.5f, 20.4f, 2.2f) }
        }
    }

    // ---- Voices ----

    /** A stage mic on its stand. */
    val Mic: ImageVector by lazy {
        icon("Mic") {
            outlinedLine(White) {
                moveTo(5.5f, 11f); curveTo(5.5f, 15f, 8.5f, 17.5f, 12f, 17.5f)
                curveTo(15.5f, 17.5f, 18.5f, 15f, 18.5f, 11f)
                moveTo(12f, 17.5f); lineTo(12f, 20.8f)
                moveTo(8.8f, 21f); lineTo(15.2f, 21f)
            }
            shape(Sun) { roundRect(8.5f, 2.5f, 15.5f, 14.5f, 3.5f) }
            line { moveTo(10.6f, 6.6f); lineTo(13.4f, 6.6f); moveTo(10.6f, 9.2f); lineTo(13.4f, 9.2f) }
        }
    }

    /** A chipmunk: round ears, full cheeks, two big teeth. */
    val Chipmunk: ImageVector by lazy {
        icon("Chipmunk") {
            shape(Tan) { circle(6.3f, 6.4f, 2.8f) }
            shape(Tan) { circle(17.7f, 6.4f, 2.8f) }
            shape(Pink, outline = false) { circle(6.3f, 6.4f, 1.3f) }
            shape(Pink, outline = false) { circle(17.7f, 6.4f, 1.3f) }
            shape(Tan) { circle(12f, 13.2f, 8f) }
            shape(outline = false) { circle(7.9f, 16f, 2.7f) }
            shape(outline = false) { circle(16.1f, 16f, 2.7f) }
            shape(Ink, outline = false) { circle(9f, 11.6f, 1.35f) }
            shape(Ink, outline = false) { circle(15f, 11.6f, 1.35f) }
            shape(outline = false) { circle(9.4f, 11.1f, 0.45f) }
            shape(outline = false) { circle(15.4f, 11.1f, 0.45f) }
            shape(Ink, outline = false) { circle(12f, 14.2f, 0.95f) }
            shape { roundRect(10.8f, 16f, 13.2f, 19f, 0.6f) }
            line(width = 1f) { moveTo(12f, 16f); lineTo(12f, 19f) }
        }
    }

    /** A speaker thumping out big, slow waves. */
    val Deep: ImageVector by lazy {
        icon("Deep") {
            outlinedLine(White, width = 2f) {
                moveTo(15f, 9f); quadTo(17.2f, 12f, 15f, 15f)
                moveTo(18f, 6.2f); quadTo(21.8f, 12f, 18f, 17.8f)
            }
            shape(Sun) {
                moveTo(3.5f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
                lineTo(7f, 15f); lineTo(3.5f, 15f); close()
            }
        }
    }

    /** A friendly robot head. */
    val Robot: ImageVector by lazy {
        icon("Robot") {
            line { moveTo(12f, 6f); lineTo(12f, 3.8f) }
            shape(Pink) { circle(12f, 2.9f, 1.3f) }
            shape(Ink, outline = false) { roundRect(2.8f, 10.5f, 4.8f, 15f, 1f) }
            shape(Ink, outline = false) { roundRect(19.2f, 10.5f, 21.2f, 15f, 1f) }
            shape { roundRect(4.5f, 6f, 19.5f, 20f, 4f) }
            shape(Lilac) { roundRect(6.8f, 8.8f, 17.2f, 14f, 2.5f) }
            shape(Ink, outline = false) { circle(9.7f, 11.4f, 1.25f) }
            shape(Ink, outline = false) { circle(14.3f, 11.4f, 1.25f) }
            shape(Aqua, outline = false) { circle(10.1f, 11f, 0.45f) }
            shape(Aqua, outline = false) { circle(14.7f, 11f, 0.45f) }
            line {
                moveTo(9.5f, 17f); lineTo(14.5f, 17f)
                moveTo(11.2f, 16.2f); lineTo(11.2f, 17.8f); moveTo(12.8f, 16.2f); lineTo(12.8f, 17.8f)
            }
        }
    }

    /** One call, three answers rippling back. */
    val Echo: ImageVector by lazy {
        icon("Echo") {
            outlinedLine(White, width = 1.9f) {
                moveTo(10f, 7.8f); quadTo(12.4f, 12f, 10f, 16.2f)
                moveTo(13.8f, 5.2f); quadTo(17.6f, 12f, 13.8f, 18.8f)
                moveTo(17.6f, 3f); quadTo(22.6f, 12f, 17.6f, 21f)
            }
            shape(Sun) { circle(5.6f, 12f, 2.4f) }
        }
    }

    /** A little transistor radio. */
    val Radio: ImageVector by lazy {
        icon("Radio") {
            line { moveTo(6f, 8f); lineTo(16.8f, 3f) }
            shape(Sun) { circle(17.4f, 2.8f, 1.1f) }
            shape { roundRect(2.5f, 8f, 21.5f, 20f, 3f) }
            shape(Ink, outline = false) { circle(8.3f, 14f, 3.4f) }
            shape(outline = false) { circle(8.3f, 14f, 1.1f) }
            shape(Lilac) { roundRect(13.5f, 10.8f, 19.3f, 13.3f, 1f) }
            shape(Ink, outline = false) { circle(14.8f, 16.7f, 1.1f) }
            shape(Pink) { circle(18f, 16.7f, 1.1f) }
        }
    }

    // ---- Quick tools ----

    /** A face being squashed flat, eyes screwed shut. */
    val Squeeze: ImageVector by lazy {
        icon("Squeeze") {
            outlinedLine(White, width = 1.8f) {
                moveTo(12f, 1.8f); lineTo(12f, 4.8f); moveTo(10f, 3.2f); lineTo(12f, 4.8f); lineTo(14f, 3.2f)
                moveTo(12f, 22.2f); lineTo(12f, 19.2f); moveTo(10f, 20.8f); lineTo(12f, 19.2f); lineTo(14f, 20.8f)
            }
            shape(Sun) { roundRect(2.5f, 7f, 21.5f, 17f, 5f) }
            line(width = 1.5f) {
                moveTo(7.8f, 10.3f); lineTo(9.8f, 11.5f); lineTo(7.8f, 12.7f)
                moveTo(16.2f, 10.3f); lineTo(14.2f, 11.5f); lineTo(16.2f, 12.7f)
            }
            shape(Ink, outline = false) { circle(12f, 13.8f, 1f) }
            shape(Pink, outline = false) { circle(6f, 14f, 1.1f) }
            shape(Pink, outline = false) { circle(18f, 14f, 1.1f) }
        }
    }

    /** Big-handled scissors. */
    val Snip: ImageVector by lazy {
        icon("Snip") {
            outlinedLine(White, width = 2.2f) {
                moveTo(8.8f, 9.3f); lineTo(20.8f, 18.2f)
                moveTo(8.8f, 14.7f); lineTo(20.8f, 5.8f)
            }
            outlinedLine(Pink, width = 2.4f) { circle(6.2f, 7.4f, 2.7f) }
            outlinedLine(Pink, width = 2.4f) { circle(6.2f, 16.6f, 2.7f) }
            shape(Sun) { circle(12.4f, 12f, 1.2f) }
        }
    }

    /** Two beamed notes and a sparkle. */
    val Rip: ImageVector by lazy {
        icon("Rip") {
            outlinedLine(White, width = 1.8f) {
                moveTo(9f, 17f); lineTo(9f, 6.5f)
                moveTo(19f, 15f); lineTo(19f, 4.5f)
            }
            shape { moveTo(9f, 5.2f); lineTo(19f, 3.2f); lineTo(19f, 6.4f); lineTo(9f, 8.4f); close() }
            shape(Sun) { circle(6.8f, 17.6f, 2.8f) }
            shape(Sun) { circle(16.8f, 15.6f, 2.8f) }
            shape { sparkle(4.2f, 5f, 2.3f) }
        }
    }

    /** Two clips sewn edge to edge. */
    val Stitch: ImageVector by lazy {
        icon("Stitch") {
            shape(Lilac) { roundRect(2.5f, 6f, 12.5f, 18f, 2.5f) }
            shape { roundRect(11.5f, 6f, 21.5f, 18f, 2.5f) }
            line(width = 1.5f) {
                moveTo(10.2f, 8.6f); lineTo(13.8f, 9.6f)
                moveTo(10.2f, 11.5f); lineTo(13.8f, 12.5f)
                moveTo(10.2f, 14.4f); lineTo(13.8f, 15.4f)
            }
            shape(Ink, outline = false) { moveTo(15.6f, 10f); lineTo(18.6f, 12f); lineTo(15.6f, 14f); close() }
            shape(Sun) { sparkle(20.4f, 4.2f, 2.3f) }
        }
    }

    // ---- Drawing kit ----

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    /** A filled shape, ringed in ink unless told otherwise. */
    private fun ImageVector.Builder.shape(
        fill: Color = White,
        outline: Boolean = true,
        block: PathBuilder.() -> Unit
    ) = path(
        fill = SolidColor(fill),
        stroke = if (outline) SolidColor(Ink) else null,
        strokeLineWidth = if (outline) OUTLINE else 0f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )

    /** A plain stroke, ink by default. */
    private fun ImageVector.Builder.line(
        color: Color = Ink,
        width: Float = OUTLINE,
        block: PathBuilder.() -> Unit
    ) = path(
        fill = null,
        stroke = SolidColor(color),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )

    /** A coloured stroke with an ink edge either side, so it holds up on any tile. */
    private fun ImageVector.Builder.outlinedLine(color: Color, width: Float = 1.8f, block: PathBuilder.() -> Unit) {
        line(Ink, width + OUTLINE * 2, block)
        line(color, width, block)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, r * 2, 0f)
        arcToRelative(r, r, 0f, true, true, -r * 2, 0f)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b)
        arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t)
        close()
    }

    /** A four-point twinkle with pinched sides. */
    private fun PathBuilder.sparkle(cx: Float, cy: Float, s: Float) {
        moveTo(cx, cy - s)
        quadTo(cx + s * 0.18f, cy - s * 0.18f, cx + s, cy)
        quadTo(cx + s * 0.18f, cy + s * 0.18f, cx, cy + s)
        quadTo(cx - s * 0.18f, cy + s * 0.18f, cx - s, cy)
        quadTo(cx - s * 0.18f, cy - s * 0.18f, cx, cy - s)
        close()
    }

    /** A heart about 7 wide, its dip at (cx, cy - 2.8). */
    private fun PathBuilder.heart(cx: Float, cy: Float) {
        moveTo(cx, cy + 3f)
        curveTo(cx - 3.8f, cy + 0.6f, cx - 3.7f, cy - 2.6f, cx - 1.8f, cy - 2.8f)
        curveTo(cx - 0.8f, cy - 2.9f, cx - 0.2f, cy - 2.2f, cx, cy - 1.6f)
        curveTo(cx + 0.2f, cy - 2.2f, cx + 0.8f, cy - 2.9f, cx + 1.8f, cy - 2.8f)
        curveTo(cx + 3.7f, cy - 2.6f, cx + 3.8f, cy + 0.6f, cx, cy + 3f)
        close()
    }

    private fun PathBuilder.drop() {
        moveTo(12f, 2.8f)
        curveTo(12f, 2.8f, 5f, 10.5f, 5f, 14.5f)
        curveTo(5f, 18.4f, 8.1f, 21.2f, 12f, 21.2f)
        curveTo(15.9f, 21.2f, 19f, 18.4f, 19f, 14.5f)
        curveTo(19f, 10.5f, 12f, 2.8f, 12f, 2.8f)
        close()
    }
}
