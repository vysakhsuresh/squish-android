@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.graphics.Bitmap
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import com.squish.app.editor.TextOverlayItem

/**
 * Burns one caption into an export between its start and end, styled and animated.
 *
 * Drawn by [CaptionRenderer] rather than handed to Media3's TextOverlay, which
 * takes a string and a colour and nothing else: an outline, a box, a shadow or a
 * glow all have to be painted. The preview uses the same renderer through
 * [com.squish.app.editor.CaptionLayer], so what the editor shows is what the file gets.
 *
 * Drawn once, on the finished frame above every layer (VideoProcessor
 * .compositionEffects), so the presentation times it is handed are the edit's
 * own and the frame it is sized to is the canvas the file is written at. It
 * used to ride on each base clip, which drew a caption twice through a
 * dissolve and not at all over a gap.
 */
class SquishTextOverlay(private val item: TextOverlayItem) : BitmapOverlay() {

    private var frameWidth = 1080
    private var frameHeight = 1920

    /** The last bitmap drawn and the text it shows, so an unchanging caption is drawn once. */
    private var cached: Bitmap? = null
    private var cachedText: String? = null
    private var cachedActive = -1
    private var cachedCut: CaptionRenderer.Cut? = null

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
        // A shape has no letters: it shows whenever its window does.
        if (shown.isBlank() && !item.drawsWithoutWords) return blank
        // A GIF's frame stands where the word being said does: it is what changes the picture.
        val active = when {
            item.isGif -> com.squish.app.media.gif.GifStickers.frameIndex(item, presentationTimeUs / 1000L - item.startMs)
            // An animated design changes its picture as the moment does (EDGE_PLAN F4).
            item.design.animated -> com.squish.app.editor.StickerArt.frameOf(item.design, presentationTimeUs / 1000L - item.startMs)
            else -> frame.words?.active ?: -1
        }
        // A knockout card's letters are cut where the line stands (EDGE_PLAN E4).
        val cut = if (item.knockout) CaptionRenderer.Cut(item.cutAt(presentationTimeUs / 1000L), item.cutTurn(frame)) else null
        if (shown == cachedText && active == cachedActive && cut == cachedCut) cached?.let { return it }
        val bitmap = CaptionRenderer.render(item, shown, frameWidth, frameHeight, frame.words, cut, presentationTimeUs / 1000L - item.startMs)
        cached = bitmap
        cachedText = shown
        cachedActive = active
        cachedCut = cut
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1000L
        val frame = CaptionRenderer.frameAt(item, timeMs)
            ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        // A knockout card at the frame's middle, never turned or raised (drawnAt).
        val drawn = item.drawnFrame(frame)
        val (x, y) = item.drawnAt(timeMs)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(x * 2 - 1, 1 - (y - drawn.rise) * 2)
            .setScale(drawn.scale, drawn.scale)
            // Media3 turns counter-clockwise in a frame whose y runs up; the
            // editor's degrees are clockwise on screen, as the preview's
            // graphicsLayer and ExportPlan.placementMatrix take them.
            .setRotationDegrees(-item.drawnTurn(frame))
            .setAlphaScale((drawn.alpha * item.opacity).coerceIn(0f, 1f))
            .build()
    }
}
