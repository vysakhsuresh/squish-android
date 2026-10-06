package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * between a moving background and a held one is slight. Which frame is the
 * middle is FrameRules.backdropMomentMs, read on a coarse grid.
 */
object CanvasBackdrop {

    /**
     * Blurred stills are made one at a time. Reading a frame is a
     * MediaMetadataRetriever decoding a 4K picture, blocking and beyond
     * cancelling once started; a trim drag used to ask for one per pointer
     * event and every ask ran at once on the IO pool - dozens of decoders,
     * a memory spike, and the editor stalling. Waiting for the lock is
     * cancellable, so a preview request overtaken by the next never starts.
     */
    private val lane = Mutex()

    /**
     * A blurred still of [uri] at [atMs], cut to fill [aspect]. Call it off
     * the main thread. Null when the frame cannot be read.
     *
     * Blurred by being shrunk to a few dozen pixels and drawn back up with
     * filtering: the same on every phone, on the CPU, and far softer than any
     * shader blur at a sensible tap count. Darkened a little, the way every
     * editor's blur backdrop is, so the footage on top reads first.
     *
     * A photo on the main track is a picture, not footage: the retriever has
     * no frame to give for it, so it is decoded as the image it is. It used
     * to be skipped, and the file went black behind every photo while the
     * preview showed the colour.
     */
    suspend fun blurred(context: Context, uri: Uri, atMs: Long, aspect: Float): File? {
        val file = File(dir(context), "blur_${uri.toString().hashCode().toUInt().toString(16)}_${atMs}_${aspectName(aspect)}.png")
        if (file.exists() && file.length() > 0) return file
        return lane.withLock {
            // Made while this waited its turn.
            if (file.exists() && file.length() > 0) return@withLock file
            val frame = if (StillClips.isStill(uri)) {
                decodeImage(context, uri, BLUR_LONG_SIDE) ?: ThumbnailExtractor.frameAt(context, uri, atMs)
            } else {
                ThumbnailExtractor.frameAt(context, uri, atMs) ?: decodeImage(context, uri, BLUR_LONG_SIDE)
            } ?: return@withLock null
            try {
                write(file, backdropOf(frame, aspect, blur = true))?.also { prune(context, keep = it) }
            } finally {
                frame.recycle()
            }
        }
    }

    /** [image] cut to fill [aspect], for a picture background. Blocking. */
    fun fromImage(context: Context, image: Uri, aspect: Float): File? {
        val file = File(dir(context), "image_${image.toString().hashCode().toUInt().toString(16)}_${aspectName(aspect)}.png")
        if (file.exists() && file.length() > 0) return file
        val decoded = decodeImage(context, image, IMAGE_LONG_SIDE) ?: return null
        return try {
            // Pruned on the way out like a blurred one: each of these is a
            // whole picture at 1920, and one is written per picture per frame
            // shape, so left alone they are the heaviest thing in files/.
            write(file, backdropOf(decoded, aspect, blur = false))?.also { prune(context, keep = it) }
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

    /** [image] decoded no larger than [longSide] across, in software; null for anything that is not a picture. */
    private fun decodeImage(context: Context, image: Uri, longSide: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, image)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // PictureSample's, so the one sample-size arithmetic in the app is
            // the one that is executed on the JVM. info.size is the size the
            // way up it will be handed back, the orientation tag applied.
            decoder.setTargetSampleSize(
                PictureSample.forLongSide(info.size.width, info.size.height, longSide)
            )
        }
    }.getOrNull()

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

    /**
     * Nothing is let go of while a hold is open.
     *
     * The export takes one round the whole of building its plan and running
     * the encode. Pruning used to run after every write, including the writes
     * the export itself was making, so an export of a padded canvas with more
     * than [BLUR_KEEP] stretches deleted the earliest of its own backdrops out
     * from under its own plan: VideoProcessor.backdropClips names each file in
     * a Clip and the Transformer opens it minutes later. The defence was a
     * sixty-second grace on the file's age, and a wall clock does not protect a
     * still made for the same export a minute earlier, nor one the preview made
     * while scrubbing before the export began - so the cap was a silent ceiling
     * on how many shots a blurred canvas could export.
     *
     * Counted, because a proxy render and an export can be in flight together.
     */
    private val holds = java.util.concurrent.atomic.AtomicInteger(0)

    fun holdStills() {
        holds.incrementAndGet()
    }

    /** Blocking: it lists and deletes. Call it off the main thread. */
    fun releaseStills(context: Context) {
        if (holds.decrementAndGet() > 0) return
        holds.set(0)
        prune(context, keep = null)
    }

    /**
     * Keeps the blurred stills to a few dozen. Every one can be made again
     * from the footage, and a session of trimming and reordering would
     * otherwise leave a file for every moment ever asked about, none of them
     * ever removed. The newest stay, [keep] among them.
     */
    private fun prune(context: Context, keep: File?) {
        // Both kinds. It read `blur_` alone, so the backdrops made from a
        // *chosen picture* - one per picture and frame shape, several megabytes
        // each - were never taken off: they were counted by Settings' "Photos
        // and freezes" row and swept by nothing, so the number could not be
        // cleared and grew with every Background picked.
        prune(dir(context).listFiles { f -> f.name.startsWith("blur_") }, BLUR_KEEP, keep)
        prune(dir(context).listFiles { f -> f.name.startsWith("image_") }, IMAGE_KEEP, keep)
    }

    private fun prune(found: Array<File>?, keep: Int, keeping: File?) {
        val stills = found ?: return
        val victims = StillPrune.victims(
            stills = stills.map { it.name to it.lastModified() },
            keep = keep,
            held = holds.get() > 0,
            keeping = keeping?.name
        ).toSet()
        stills.filter { it.name in victims }.forEach { runCatching { it.delete() } }
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

    /** Enough for every shot of a long edit at a couple of shapes; the rest are made again when wanted. */
    private const val BLUR_KEEP = 48

    /**
     * And for the pictures chosen as backgrounds. Fewer, because each is a whole
     * decoded picture at 1920 rather than a blurred frame, and far fewer are
     * ever wanted: one per picture per frame shape.
     */
    private const val IMAGE_KEEP = 12
}
