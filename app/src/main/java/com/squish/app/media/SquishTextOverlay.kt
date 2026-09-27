@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.graphics.Bitmap
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.squish.app.editor.TextOverlayItem

/**
 * Burns one caption into an export between its start and end, styled and animated.
 *
 * Drawn by [CaptionRenderer] rather than handed to Media3's TextOverlay, which
 * takes a string and a colour and nothing else: an outline, a box, a shadow or a
 * glow all have to be painted. The preview uses the same renderer through
 * [LiveCaptionOverlay], so what the editor shows is what the file gets.
 */
class SquishTextOverlay(private val item: TextOverlayItem) : BitmapOverlay() {

    private var frameWidth = 1080
    private var frameHeight = 1920

    /** The last bitmap drawn and the text it shows, so an unchanging caption is drawn once. */
    private var cached: Bitmap? = null
    private var cachedText: String? = null

    private val blank: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize.width > 0 && videoSize.height > 0 &&
            (videoSize.width != frameWidth || videoSize.height != frameHeight)
        ) {
            frameWidth = videoSize.width
            frameHeight = videoSize.height
            cachedText = null
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frame = CaptionRenderer.frameAt(item, presentationTimeUs / 1000L) ?: return blank
        val shown = CaptionRenderer.shownText(item, frame)
        if (shown.isBlank()) return blank
        if (shown == cachedText) cached?.let { return it }
        val bitmap = CaptionRenderer.render(item, shown, frameWidth, frameHeight)
        cached = bitmap
        cachedText = shown
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1000L
        val frame = CaptionRenderer.frameAt(item, timeMs)
            ?: return OverlaySettings.Builder().setAlphaScale(0f).build()
        val (x, y) = item.anchorAt(timeMs)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(x * 2 - 1, 1 - (y - frame.rise) * 2)
            .setScale(frame.scale, frame.scale)
            .setAlphaScale(frame.alpha.coerceIn(0f, 1f))
            .build()
    }
}
