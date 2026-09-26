@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.squish.app.editor.TextFrame
import com.squish.app.editor.TextOverlayItem
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Every caption on the preview, drawn as one layer that is never rebuilt.
 *
 * Captions used to be one overlay each, fixed when the preview's effect list was
 * built - so adding a title, changing its look or its colour all rebuilt the
 * player's whole GL pipeline, and a few taps in a row left the picture frozen
 * while the clock ran on. This layer is installed once and reads the current
 * captions from [captions] on every frame, so a style change is a value write.
 *
 * The captions are composed onto one bitmap at [SCALE] of the frame, then drawn
 * back at full size. Half resolution is plenty for a preview and quarters the
 * upload; the export draws each caption at full size with [SquishTextOverlay].
 * The bitmap is only redrawn when what it shows changes - a caption that is just
 * sitting there costs nothing per frame.
 */
class LiveCaptionOverlay(
    private val captions: AtomicReference<List<TextOverlayItem>>,
    /**
     * True while the preview is paused. A paused editor shows every caption and
     * sticker as it looks at rest - a pop-in is invisible on its first frame,
     * which is exactly where one is added, so a new sticker seemed not to appear.
     * Motion plays when the video does.
     */
    private val atRest: AtomicBoolean
) : BitmapOverlay() {

    private var frameWidth = 1080
    private var frameHeight = 1920

    private var canvasBitmap: Bitmap? = null
    private var drawnKey: String? = null

    /** Each caption's rendered letters, by what they look like, so animating one does not redraw its text. */
    private val glyphs = HashMap<String, Bitmap>()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize.width > 0 && videoSize.height > 0 &&
            (videoSize.width != frameWidth || videoSize.height != frameHeight)
        ) {
            frameWidth = videoSize.width
            frameHeight = videoSize.height
            canvasBitmap = null
            drawnKey = null
            glyphs.clear()
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000L
        val w = (frameWidth * SCALE).roundToInt().coerceAtLeast(2)
        val h = (frameHeight * SCALE).roundToInt().coerceAtLeast(2)

        val showing = captions.get().mapNotNull { item ->
            CaptionRenderer.frameAt(item, timeMs)
                ?.let { if (atRest.get()) TextFrame() else it }
                ?.let { frame -> Triple(item, frame, CaptionRenderer.shownText(item, frame)) }
        }.filter { it.third.isNotBlank() }

        // Rounded so a caption at rest produces the same key frame after frame.
        val key = showing.joinToString("|") { (item, frame, shown) ->
            val (x, y) = item.anchorAt(timeMs)
            "${styleKey(item, shown)}@${q(x)},${q(y - frame.rise)},${q(frame.scale)},${q(frame.alpha)}"
        }

        val bitmap = canvasBitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                canvasBitmap = it
                drawnKey = null
            }
        if (key == drawnKey) return bitmap
        drawnKey = key

        bitmap.eraseColor(0)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((item, frame, shown) in showing) {
            val glyph = glyphs.getOrPut(styleKey(item, shown)) {
                CaptionRenderer.render(item, shown, w, h)
            }
            val (x, y) = item.anchorAt(timeMs)
            val cx = x * w
            val cy = (y - frame.rise) * h
            val gw = glyph.width * frame.scale
            val gh = glyph.height * frame.scale
            paint.alpha = (frame.alpha.coerceIn(0f, 1f) * 255).roundToInt()
            canvas.drawBitmap(glyph, null, RectF(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2), paint)
        }
        // Old renders of text that has since changed are dropped, so editing a
        // caption letter by letter does not pile up a bitmap per keystroke.
        if (glyphs.size > MAX_GLYPHS) {
            val live = showing.map { (item, _, shown) -> styleKey(item, shown) }.toSet()
            glyphs.keys.retainAll(live)
        }
        return bitmap
    }

    /** Drawn back at the frame's full size, filling it exactly. */
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder().setScale(1f / SCALE, 1f / SCALE).build()

    private fun styleKey(item: TextOverlayItem, shown: String): String =
        "${item.id}/${item.font}/${item.look}/${item.colorArgb}/${item.sizeSp}/${item.motion}/${item.text}/$shown"

    private fun q(v: Float): Int = (v * 1000).roundToInt()

    private companion object {
        const val SCALE = 0.5f
        const val MAX_GLYPHS = 48
    }
}
