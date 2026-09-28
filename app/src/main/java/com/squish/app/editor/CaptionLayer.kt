package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.squish.app.media.CaptionRenderer
import kotlin.math.roundToInt

/**
 * Every caption and sticker on the preview, drawn once, above every picture layer.
 *
 * They used to be baked into each player's picture by an effect on its chain.
 * That drew them on every surface that had one - twice across a dissolve, and a
 * shrunken copy inside every picture-in-picture - never over a gap, where there
 * is no player, and in the player's source clock, so over a clip at 2x a caption
 * came and went at the wrong moments. The export draws each caption once over the
 * finished frame, in timeline time; so does this.
 *
 * Painted by [CaptionRenderer], the same code the export uses, laid out in the
 * part of the canvas the crop keeps ([frame]), which is the frame the export lays
 * them out in.
 */
@Composable
fun CaptionLayer(
    captions: List<TextOverlayItem>,
    /** Timeline time. */
    timeMs: Long,
    /**
     * True while paused. A paused editor shows every caption as it looks at rest -
     * a pop-in is invisible on its first frame, which is exactly where one is
     * added, so a new sticker seemed not to appear. Motion plays when the video does.
     */
    atRest: Boolean,
    frame: PreviewBox.Frame,
    modifier: Modifier = Modifier
) {
    // Each caption's letters, by what they look like and the size they were drawn
    // for, so animating one or scrubbing past it does not redraw its text.
    val glyphs = remember { HashMap<String, ImageBitmap>() }

    Canvas(modifier = modifier) {
        val boxLeft = frame.left * size.width
        val boxTop = frame.top * size.height
        val boxWidth = (frame.width * size.width).roundToInt()
        val boxHeight = (frame.height * size.height).roundToInt()
        if (boxWidth <= 0 || boxHeight <= 0) return@Canvas

        val live = HashSet<String>()
        for (item in captions) {
            val moving = CaptionRenderer.frameAt(item, timeMs) ?: continue
            val look = if (atRest) TextFrame() else moving
            val shown = CaptionRenderer.shownText(item, look)
            if (shown.isBlank()) continue

            val key = "${item.id}/${item.font}/${item.look}/${item.colorArgb}/${item.sizeSp}/${item.motion}/" +
                "${item.text}/$shown@${boxWidth}x$boxHeight"
            live.add(key)
            val glyph = glyphs.getOrPut(key) {
                CaptionRenderer.render(item, shown, boxWidth, boxHeight).asImageBitmap()
            }

            val (x, y) = item.anchorAt(timeMs)
            val cx = boxLeft + x * boxWidth
            val cy = boxTop + (y - look.rise) * boxHeight
            val w = glyph.width * look.scale
            val h = glyph.height * look.scale
            drawImage(
                image = glyph,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(glyph.width, glyph.height),
                dstOffset = IntOffset((cx - w / 2f).roundToInt(), (cy - h / 2f).roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                alpha = look.alpha.coerceIn(0f, 1f)
            )
        }
        // Old renders of text that has since changed are dropped, so editing a
        // caption letter by letter does not pile up a bitmap per keystroke.
        if (glyphs.size > MAX_GLYPHS) glyphs.keys.retainAll(live)
    }
}

private const val MAX_GLYPHS = 48

