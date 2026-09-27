@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
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
 */
object StillClips {

    /** How long a photo or blank lands on the timeline. */
    const val DEFAULT_MS = 3_000L

    /** How long each is rendered - the most its end can be dragged out to. */
    const val RENDER_MS = 10_000L

    /** Tall enough for a phone-sized export, small enough to render in a few seconds. */
    private const val MAX_SHORT_SIDE = 1080

    /** A clip made from the picture at [image], or null if it could not be read or rendered. */
    suspend fun fromImage(context: Context, image: Uri): Uri? =
        render(context, image, "photo_${System.currentTimeMillis()}")

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
        return render(context, Uri.fromFile(frame), "blank_${System.currentTimeMillis()}")
    }

    private suspend fun render(context: Context, image: Uri, name: String): Uri? {
        val (w, h) = withContext(Dispatchers.IO) { uprightSize(context, image) } ?: return null
        val (outW, outH) = fit(w, h)
        val target = File(dir(context), "$name.mp4")
        val partial = File(target.absolutePath + ".part")
        val done = withContext(Dispatchers.Main) { transcode(context.applicationContext, image, partial, outW, outH) }
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

    private suspend fun transcode(context: Context, image: Uri, output: File, width: Int, height: Int): Boolean =
        suspendCancellableCoroutine { continuation ->
            // A photo straight off a camera is 12 or 50 megapixels, beyond what a
            // phone's encoder takes. Scaled so its short side is at most 1080.
            val scale: List<Effect> = listOf(
                Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
            )
            val item = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(image).setImageDurationMs(RENDER_MS).build()
            )
                .setFrameRate(FRAME_RATE)
                .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(scale)))
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
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
            runCatching { transformer.start(item, output.absolutePath) }
                .onFailure { if (continuation.isActive) continuation.resume(false) }
        }

    /** The frame scaled so its short side is at most [MAX_SHORT_SIDE], both sides even as encoders require. */
    private fun fit(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_SHORT_SIDE.toFloat() / minOf(width, height))
        fun even(v: Float) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    private fun dir(context: Context): File = File(context.filesDir, "stills").apply { mkdirs() }

    /** The rate most phone footage runs at, so a dissolve into a photo is as smooth as the shot beside it. */
    private const val FRAME_RATE = 30
}
