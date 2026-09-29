package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import com.squish.app.timeline.Clip
import java.io.File
import java.io.FileOutputStream

/**
 * The pictures a padded canvas is filled with behind the footage, written once
 * under files/stills/backdrops and read by both the preview (drawn by Compose)
 * and the export (an image item on the bottom layer, CompositionFactory). One
 * file for both is what makes the background in the file the one on screen.
 *
 * The blurred backdrop is a still - one frame of the shot, from its middle,
 * blurred - not a live blurred copy of the footage. A live copy is another
 * decoder per base roll, and with two rolls under a transition and three rows
 * of overlays the export is already at the five hardware decoders a mid-range
 * phone opens (MAX_FOOTAGE_LAYER); the failure is a black picture or an export
 * that stops. A still costs nothing to play, and behind a blur the difference
 * between a moving background and a held one is slight.
 */
object CanvasBackdrop {

    /** The moment of a shot its blurred backdrop is taken from: its middle, in the file's own time. */
    fun stillMomentOf(clip: Clip): Long = clip.sourceAt(clip.timelineStartMs + clip.durationMs / 2)

    /**
     * A blurred still of [uri] at [atMs], cut to fill [aspect]. Blocking; call
     * it off the main thread. Null when the frame cannot be read.
     *
     * Blurred by being shrunk to a few dozen pixels and drawn back up with
     * filtering: the same on every phone, on the CPU, and far softer than any
     * shader blur at a sensible tap count. Darkened a little, the way every
     * editor's blur backdrop is, so the footage on top reads first.
     */
    suspend fun blurred(context: Context, uri: Uri, atMs: Long, aspect: Float): File? {
        val file = File(dir(context), "blur_${uri.toString().hashCode().toUInt().toString(16)}_${atMs}_${aspectName(aspect)}.png")
        if (file.exists() && file.length() > 0) return file
        val frame = ThumbnailExtractor.frameAt(context, uri, atMs) ?: return null
        return try {
            write(file, backdropOf(frame, aspect, blur = true))
        } finally {
            frame.recycle()
        }
    }

    /** [image] cut to fill [aspect], for a picture background. Blocking. */
    fun fromImage(context: Context, image: Uri, aspect: Float): File? {
        val file = File(dir(context), "image_${image.toString().hashCode().toUInt().toString(16)}_${aspectName(aspect)}.png")
        if (file.exists() && file.length() > 0) return file
        val decoded = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, image)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                var sample = 1
                while (long / (sample * 2) >= IMAGE_LONG_SIDE) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
        }.getOrNull() ?: return null
        return try {
            write(file, backdropOf(decoded, aspect, blur = false))
        } finally {
            decoded.recycle()
        }
    }

    /** A picture of one colour at [aspect], for a coloured canvas. Blocking. */
    fun solid(context: Context, argb: Int, aspect: Float): File? {
        val file = File(dir(context), "solid_${(argb.toLong() and 0xFFFFFFFFL).toString(16)}_${aspectName(aspect)}.png")
        if (file.exists() && file.length() > 0) return file
        val (w, h) = sizeFor(aspect, SOLID_LONG_SIDE)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(argb or Color.BLACK) }
        return write(file, bitmap)
    }

    /**
     * [source] cut to fill a frame of [aspect]: scaled so the frame is covered,
     * the middle kept. With [blur], shrunk to [BLUR_SIDE] first and filtered
     * back up, then darkened.
     */
    private fun backdropOf(source: Bitmap, aspect: Float, blur: Boolean): Bitmap {
        val (w, h) = sizeFor(aspect, if (blur) BLUR_LONG_SIDE else IMAGE_LONG_SIDE)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val picture = if (blur) {
            val small = sizeFor(source.width.toFloat() / source.height.coerceAtLeast(1), BLUR_SIDE)
            Bitmap.createScaledBitmap(source, small.first, small.second, true)
        } else source
        // Cover: scale to the larger of the two ratios and centre.
        val scale = maxOf(w.toFloat() / picture.width, h.toFloat() / picture.height)
        val dw = picture.width * scale
        val dh = picture.height * scale
        canvas.drawBitmap(
            picture,
            null,
            android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
            paint
        )
        if (picture !== source) picture.recycle()
        if (blur) canvas.drawColor(Color.argb(DARKEN_ALPHA, 0, 0, 0))
        return out
    }

    private fun write(file: File, bitmap: Bitmap): File? = try {
        val partial = File(file.absolutePath + ".part")
        FileOutputStream(partial).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (partial.length() > 0L && partial.renameTo(file)) file else { partial.delete(); null }
    } catch (t: Throwable) {
        null
    } finally {
        bitmap.recycle()
    }

    /** A frame of [aspect] with its long side [longSide], both sides even as encoders require. */
    private fun sizeFor(aspect: Float, longSide: Int): Pair<Int, Int> {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
        val (w, h) = if (a >= 1f) longSide.toFloat() to longSide / a else longSide * a to longSide.toFloat()
        fun even(v: Float) = (Math.round(v / 2f) * 2).coerceAtLeast(2)
        return even(w) to even(h)
    }

    private fun aspectName(aspect: Float): String = Math.round(aspect * 1000f).toString()

    private fun dir(context: Context): File = File(context.filesDir, "stills/backdrops").apply { mkdirs() }

    /** Small, so a frame of it is nothing to decode; the blur hides the rest. */
    private const val BLUR_LONG_SIDE = 480
    private const val BLUR_SIDE = 28
    private const val IMAGE_LONG_SIDE = 1920
    private const val SOLID_LONG_SIDE = 64
    private const val DARKEN_ALPHA = 64
}
