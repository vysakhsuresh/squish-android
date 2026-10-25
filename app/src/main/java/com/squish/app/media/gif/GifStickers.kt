package com.squish.app.media.gif

import android.graphics.Bitmap
import android.graphics.Matrix
import com.squish.app.editor.ShapeGeometry
import com.squish.app.editor.TextOverlayItem
import com.squish.app.media.CaptionRenderer
import java.io.File
import kotlin.math.ceil

/**
 * GIF stickers (EDGE_PLAN F4, P6) on the picture: the frame showing at a
 * moment, at the sticker's size, for the preview's caption layer and the
 * export's overlay alike - CaptionRenderer.paint hands GIFs here, as it hands
 * designs to StickerPainter, so there is still one drawing.
 *
 * Each file is read once (GifDecoder, at most [GifDecoder.MAX_FRAMES] frames
 * of at most 320 px) and the last few kept, since a preview asks for the next
 * frame thirty times a second and an export once a frame.
 */
object GifStickers {

    private const val KEEP = 6

    private val decoded = object : LinkedHashMap<String, GifDecoder.Animation>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GifDecoder.Animation>?) = size > KEEP
    }

    /** [path] read, or null when it cannot be - the sticker then draws nothing rather than taking the export down. */
    fun animation(path: String): GifDecoder.Animation? = synchronized(decoded) {
        decoded[path] ?: runCatching { GifDecoder.decode(File(path).readBytes()) }.getOrNull()?.also { decoded[path] = it }
    }

    /** Which frame of [item] shows [elapsedMs] into it. */
    fun frameIndex(item: TextOverlayItem, elapsedMs: Long): Int {
        val a = item.gif?.let { animation(it) } ?: return 0
        return GifDecoder.frameAt(a, elapsedMs)
    }

    fun paint(item: TextOverlayItem, elapsedMs: Long, frameWidth: Int, frameHeight: Int): CaptionRenderer.Painted {
        val a = item.gif?.let { animation(it) }
        if (a == null) return CaptionRenderer.Painted(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 1, 1)
        val shortEdge = minOf(frameWidth, frameHeight).toFloat().coerceAtLeast(1f)
        val h = ceil((item.sizeSp / 360f * shortEdge).coerceAtLeast(4f)).toInt()
        val w = ceil(h * item.shapeAspect.coerceIn(ShapeGeometry.MIN_ASPECT, ShapeGeometry.MAX_ASPECT)).toInt().coerceAtLeast(1)
        val frame = a.frames[GifDecoder.frameAt(a, elapsedMs)]
        val source = Bitmap.createBitmap(frame, a.width, a.height, Bitmap.Config.ARGB_8888)
        val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
        if (!item.flipped) return CaptionRenderer.Painted(scaled, w, h)
        val mirror = Matrix().apply { preScale(-1f, 1f) }
        return CaptionRenderer.Painted(Bitmap.createBitmap(scaled, 0, 0, w, h, mirror, true), w, h)
    }
}
