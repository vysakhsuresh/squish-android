@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Photos and blanks, made into clips.
 *
 * Everything on the video track is a video file: the preview player, the
 * filmstrip, the trimmer and the export all read one. So rather than teach each
 * of them about pictures, a picture is turned into a short video once, on the
 * phone, and from then on it is a clip like any other - trimmed, cut, graded,
 * dissolved. A blank is the same thing made from a plain black frame.
 *
 * Each is rendered [RENDER_MS] long and placed at [DEFAULT_MS], so there is room
 * to drag its end out, as in other editors, without making it again. Kept under
 * files/, not the cache: a draft refers to the file, and Android empties caches.
 *
 * A photo on an overlay row is the exception: it stays a picture (see
 * [overlayFromImage]), because a logo or a cut-out is transparent and a video
 * file cannot be.
 */
object StillClips {

    /** How long a photo or blank lands on the timeline. */
    const val DEFAULT_MS = 3_000L

    /** How long each is rendered - the most its end can be dragged out to. */
    const val RENDER_MS = 10_000L

    /** Tall enough for a phone-sized export, small enough to render in a few seconds. */
    private const val MAX_SHORT_SIDE = 1080

    /**
     * A clip made from the picture at [image], or null if it could not be read or
     * rendered. At least [minMs] long - a photo overlay dropped onto the main
     * track may already have been dragged out past [RENDER_MS].
     */
    suspend fun fromImage(context: Context, image: Uri, minMs: Long = RENDER_MS): Uri? =
        render(context, image, "photo_${System.currentTimeMillis()}", maxOf(RENDER_MS, minMs))

    /**
     * A picture for an overlay row, kept a picture: upright, no larger than an
     * export needs, and written as a PNG so a logo's or a cut-out's transparency
     * survives - the H.264 a main-track photo becomes has no alpha at all.
     *
     * The export hands the file to Media3 as an image item (CompositionFactory),
     * and the preview draws it itself (TimelinePreview); neither needs a decoder
     * for it. Blocking; call it off the main thread. Null if it could not be read.
     */
    fun overlayFromImage(context: Context, image: Uri): Uri? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, image)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software, so it can be scaled and written; sampled down on the way
            // in, so a 50-megapixel photo never sits whole in memory.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val short = minOf(info.size.width, info.size.height).coerceAtLeast(1)
            var sample = 1
            while (short / (sample * 2) >= MAX_SHORT_SIDE) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        val (w, h) = overlayFit(decoded.width, decoded.height)
        val bitmap = if (w == decoded.width && h == decoded.height) decoded
        else Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        val target = File(dir(context), "overlay_${System.currentTimeMillis()}.png")
        val partial = File(target.absolutePath + ".part")
        try {
            FileOutputStream(partial).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        if (partial.length() <= 0L || !partial.renameTo(target)) {
            partial.delete()
            error("could not keep $target")
        }
        Uri.fromFile(target)
    }.onFailure { android.util.Log.w("SquishStill", "could not make an overlay of $image", it) }.getOrNull()

    /**
     * Whether a clip's file is a picture kept as a picture - an overlay made by
     * [overlayFromImage] - rather than footage. Only this app writes those, and
     * only as PNGs in its own folder; everything picked from the gallery arrives
     * as a content:// address.
     */
    fun isStill(uri: Uri?): Boolean =
        uri != null && uri.scheme == "file" && uri.path?.let { it.endsWith(".png") && it.contains("/$DIR/") } == true

    /** The picture's width over its height, from its header alone. Blocking. */
    fun aspectOf(context: Context, uri: Uri): Float? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else bounds.outWidth.toFloat() / bounds.outHeight
    }.getOrNull()

    /**
     * A picture for the preview to draw, at most [maxSide] on its long side.
     * Blocking. Null if it cannot be read.
     */
    fun previewBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return@runCatching null
        var sample = 1
        while (long / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    /**
     * An overlay picture's size: its short side at most [MAX_SHORT_SIDE] and its
     * long side at most [MAX_LONG_SIDE] - a panorama at 1080 tall would be ten
     * thousand pixels wide, far past anything a layer is drawn at.
     */
    private fun overlayFit(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(
            1f,
            MAX_SHORT_SIDE.toFloat() / minOf(width, height).coerceAtLeast(1),
            MAX_LONG_SIDE.toFloat() / maxOf(width, height).coerceAtLeast(1)
        )
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * A plain black clip, [width] by [height] - the edit's own frame shape, so it
     * sits in the timeline without bars.
     */
    suspend fun blank(context: Context, width: Int, height: Int): Uri? {
        val (w, h) = fit(width.takeIf { it > 0 } ?: 1080, height.takeIf { it > 0 } ?: 1920)
        val frame = File(dir(context), "blank_${w}x$h.png")
        if (!frame.exists()) {
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
                    FileOutputStream(frame).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }.isSuccess
            }
            if (!made) return null
        }
        return render(context, Uri.fromFile(frame), "blank_${System.currentTimeMillis()}", RENDER_MS)
    }

    private suspend fun render(context: Context, image: Uri, name: String, lengthMs: Long): Uri? {
        val (w, h) = withContext(Dispatchers.IO) { uprightSize(context, image) } ?: return null
        val (outW, outH) = fit(w, h)
        val target = File(dir(context), "$name.mp4")
        val partial = File(target.absolutePath + ".part")
        val done = withContext(Dispatchers.Main) {
            transcode(context.applicationContext, image, partial, outW, outH, lengthMs, withSound = true) || run {
                // The silent track is a convenience, not the point. A phone whose
                // Media3 will not make silence for a picture used to be a phone
                // that could add photos and blanks at all; it still is, with a
                // still that has no sound track - which the export copes with,
                // since it declares sound on every sequence itself.
                runCatching { partial.delete() }
                transcode(context.applicationContext, image, partial, outW, outH, lengthMs, withSound = false)
            }
        }
        return if (done && partial.length() > 0 && partial.renameTo(target)) {
            Uri.fromFile(target)
        } else {
            runCatching { partial.delete() }
            null
        }
    }

    /**
     * The picture's size the way up it is meant to be seen. A portrait phone photo
     * is usually stored landscape with a rotation tag; the decoder applies the tag,
     * so the size asked for has to as well, or the photo is squeezed into the
     * wrong shape.
     */
    private fun uprightSize(context: Context, image: Uri): Pair<Int, Int>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val turned = context.contentResolver.openInputStream(image)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
                else -> false
            }
        } ?: false
        if (turned) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }.getOrNull()

    private suspend fun transcode(
        context: Context,
        image: Uri,
        output: File,
        width: Int,
        height: Int,
        lengthMs: Long,
        withSound: Boolean
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            // A photo straight off a camera is 12 or 50 megapixels, beyond what a
            // phone's encoder takes. Scaled so its short side is at most 1080.
            val scale: List<Effect> = listOf(
                Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
            )
            val item = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(image).setImageDurationMs(lengthMs).build()
            )
                .setFrameRate(FRAME_RATE)
                .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(scale)))
                .build()

            // With a track of silence, so a still is a clip like any other all the
            // way down. Without one, a photo was the only thing on the timeline with
            // no sound at all, and Media3 will not start a sequence on an item with
            // no audio and then meet one with: a dissolve that dealt the photo to
            // the front of the second roll failed every export at the first frame.
            // The export forces a sound track on every sequence as well; this makes
            // a still safe even where something else reads the file.
            val tracks = if (withSound) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO)
            val composition = Composition.Builder(
                EditedMediaItemSequence.Builder(tracks)
                    .addItem(item)
                    .build()
            ).build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .apply { if (withSound) setAudioMimeType(MimeTypes.AUDIO_AAC) }
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        android.util.Log.w("SquishStill", "could not render $image", exportException)
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
                .build()

            continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
            runCatching { transformer.start(composition, output.absolutePath) }
                .onFailure { if (continuation.isActive) continuation.resume(false) }
        }

    /** The frame scaled so its short side is at most [MAX_SHORT_SIDE], both sides even as encoders require. */
    private fun fit(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_SHORT_SIDE.toFloat() / minOf(width, height))
        fun even(v: Float) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    /**
     * A picture with nothing in it: every pixel fully transparent. The export
     * lays it where a layer has nothing to show - before an overlay starts,
     * between two shots on a roll, after the last one - so the layers underneath
     * show through.
     *
     * Media3 can fill a gap in a sequence by itself, but what it fills it with is
     * a small opaque black frame, which the compositor draws as a black square in
     * the middle of whatever is below. Nothing the app draws itself has that
     * problem, and nothing about it depends on the compositor honouring a setting.
     *
     * Blocking; call it off the main thread. Null if it could not be written.
     */
    fun clearFrame(context: Context): File? {
        val file = File(dir(context), "clear_${CLEAR_SIDE}.png")
        if (file.exists() && file.length() > 0) return file
        return runCatching {
            val partial = File(file.absolutePath + ".part")
            val bitmap = Bitmap.createBitmap(CLEAR_SIDE, CLEAR_SIDE, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.TRANSPARENT) }
            FileOutputStream(partial).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (!partial.renameTo(file)) error("could not keep $file")
            file
        }.getOrNull()
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private const val DIR = "stills"

    /** An overlay picture's longest side; see [overlayFit]. */
    private const val MAX_LONG_SIDE = 3840

    /** Small, because it is drawn at nothing; even, because some decoders insist. */
    private const val CLEAR_SIDE = 16

    /** The rate most phone footage runs at, so a dissolve into a photo is as smooth as the shot beside it. */
    private const val FRAME_RATE = 30
}
