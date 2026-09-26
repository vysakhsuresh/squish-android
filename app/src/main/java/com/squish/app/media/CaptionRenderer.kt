package com.squish.app.media

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.squish.app.editor.TextFrame
import com.squish.app.editor.TextLook
import com.squish.app.editor.TextMotion
import com.squish.app.editor.TextOverlayItem
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws one styled caption as a bitmap, and says how it animates.
 *
 * Shared by the export overlay and the preview's caption layer, so a caption is
 * painted by one piece of code wherever it appears.
 *
 * Size is relative to the frame: a caption set to 28 is 28/360 of the frame's
 * short edge, whatever the resolution, so a title laid out on a small preview
 * lands in the same place and at the same weight on a 4K export.
 */
object CaptionRenderer {

    /** The caption's state at [timeMs], or null when it is not on screen. */
    fun frameAt(item: TextOverlayItem, timeMs: Long): TextFrame? {
        if (timeMs < item.startMs || timeMs >= item.endMs || item.text.isBlank()) return null
        val total = (item.endMs - item.startMs).coerceAtLeast(1L)
        return item.motion.frameAt(timeMs - item.startMs, item.endMs - timeMs, total)
    }

    /** The letters showing at [frame] - all of them, unless it is being typed. */
    fun shownText(item: TextOverlayItem, frame: TextFrame): String {
        if (item.motion != TextMotion.Typewriter) return item.text
        val count = ceil(item.text.length * frame.reveal).toInt().coerceIn(0, item.text.length)
        return item.text.substring(0, count)
    }

    /**
     * The caption drawn for a frame [frameWidth] by [frameHeight], showing [shown]
     * inside the box the full text needs - so typed text does not re-centre itself
     * letter by letter.
     */
    fun render(item: TextOverlayItem, shown: String, frameWidth: Int, frameHeight: Int): Bitmap {
        val shortEdge = minOf(frameWidth, frameHeight).toFloat().coerceAtLeast(1f)
        val textSize = (item.sizeSp / 360f * shortEdge).coerceAtLeast(6f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = item.font.typeface()
            color = item.colorArgb
        }

        val maxWidth = (frameWidth * 0.9f).roundToInt().coerceAtLeast(1)
        val fullLayout = layout(item, item.text, paint, maxWidth)
        val shownLayout = layout(item, shown, paint, fullLayout.width)

        // Room round the letters for the edge, the glow or the box.
        val pad = (textSize * when (item.look) {
            TextLook.Box -> 0.45f
            TextLook.Neon -> 0.6f
            TextLook.Shadow -> 0.35f
            TextLook.Outline -> 0.2f
            TextLook.Plain -> 0.05f
        }).roundToInt()
        val width = (fullLayout.width + pad * 2).coerceAtLeast(1)
        val height = (fullLayout.height + pad * 2).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (item.look == TextLook.Box) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
            val radius = textSize * 0.3f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, box)
        }

        canvas.save()
        canvas.translate(pad.toFloat(), pad.toFloat())
        when (item.look) {
            TextLook.Outline -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = textSize * 0.14f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.BLACK
                shownLayout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = item.colorArgb
                shownLayout.draw(canvas)
            }
            TextLook.Shadow -> {
                paint.setShadowLayer(textSize * 0.18f, textSize * 0.06f, textSize * 0.08f, Color.argb(200, 0, 0, 0))
                shownLayout.draw(canvas)
            }
            TextLook.Neon -> {
                // A wide soft pass and a tight one in the letter colour, then the
                // letters themselves lightened, which is what reads as a tube lit up.
                val glow = TextPaint(paint).apply {
                    maskFilter = BlurMaskFilter(textSize * 0.45f, BlurMaskFilter.Blur.NORMAL)
                }
                layout(item, shown, glow, fullLayout.width).draw(canvas)
                glow.maskFilter = BlurMaskFilter(textSize * 0.15f, BlurMaskFilter.Blur.NORMAL)
                layout(item, shown, glow, fullLayout.width).draw(canvas)
                paint.color = lighten(item.colorArgb)
                shownLayout.draw(canvas)
            }
            TextLook.Box, TextLook.Plain -> shownLayout.draw(canvas)
        }
        canvas.restore()
        return bitmap
    }

    private fun layout(item: TextOverlayItem, text: String, paint: TextPaint, width: Int): StaticLayout {
        val measured = ceil(StaticLayout.getDesiredWidth(item.text, paint)).toInt()
        val lineWidth = measured.coerceIn(1, width.coerceAtLeast(1))
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, lineWidth)
            // Typed text grows from the left, so the line does not slide about as
            // each letter lands; everything else is centred.
            .setAlignment(
                if (item.motion == TextMotion.Typewriter) Layout.Alignment.ALIGN_NORMAL
                else Layout.Alignment.ALIGN_CENTER
            )
            .setIncludePad(false)
            .build()
    }

    private fun lighten(argb: Int): Int {
        val mix = { c: Int -> (c + (255 - c) * 0.55f).roundToInt().coerceIn(0, 255) }
        return Color.argb(Color.alpha(argb), mix(Color.red(argb)), mix(Color.green(argb)), mix(Color.blue(argb)))
    }
}
