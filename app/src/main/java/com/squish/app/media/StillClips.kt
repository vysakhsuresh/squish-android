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
     * A freeze is rendered at the frame's own size, up to 4K: it sits between
     * two frames of the same shot, and one scaled down to 1080 visibly
     * softened at the cut into it. Past this the phone's encoder is not asked;
     * a still that cannot be encoded at its own size falls back to
     * [MAX_SHORT_SIDE] rather than to nothing.
     */
    private const val FREEZE_MAX_SHORT_SIDE = 2160

    /**
     * A clip made from the picture at [image], or null if it could not be read or
     * rendered. At least [minMs] long - a photo overlay dropped onto the main
     * track may already have been dragged out past [RENDER_MS].
     *
     * The picture itself is kept beside the clip (see [originalImage]): the
     * clip is what the strip and the preview play, the picture is what the
     * export writes.
     */
    suspend fun fromImage(context: Context, image: Uri, minMs: Long = RENDER_MS): Uri? {
        val name = uniqueName("photo")
        val clip = render(context, image, name, maxOf(RENDER_MS, minMs)) ?: return null
        withContext(Dispatchers.IO) { keepOriginal(context, image, name) }
        return clip
    }

    /**
     * The picture a main-track still was made from, if it was kept: the export
     * hands it to Media3 as an image (VideoProcessor.editedClip), at up to
     * [MAX_EXPORT_SIDE] across and for however long the clip runs, rather than
     * re-encoding the 1080p, 30 fps clip the strip plays. A still from before
     * the picture was kept, or whose copy failed, has none and exports as the
     * clip, as it always did.
     */
    fun originalImage(still: Uri?): Uri? {
        val path = still?.takeIf { it.scheme == "file" }?.path ?: return null
        if (!path.endsWith(".mp4") || "/$DIR/" !in path) return null
        val file = File(path.removeSuffix(".mp4") + ORIGINAL_SUFFIX)
        return if (file.exists() && file.length() > 0L) Uri.fromFile(file) else null
    }

    /**
     * The same still rendered again, [lengthMs] long, from the picture kept
     * beside it - so a tail dragged past the rendering's end (StillRules) has
     * a file under it in the preview. The picture is copied beside the new
     * clip, as [fromImage] keeps it, so the new clip exports as the picture
     * too. The old clip is left where it was: a draft, or an undo step, may
     * still name it. Null when the picture was not kept or the render failed;
     * the clip then stays as it is.
     */
    suspend fun extended(context: Context, still: Uri, lengthMs: Long): Uri? {
        val picture = originalImage(still) ?: return null
        val name = uniqueName("photo")
        val clip = render(context, picture, name, lengthMs) ?: return null
        val kept = withContext(Dispatchers.IO) {
            runCatching {
                File(picture.path!!).copyTo(File(dir(context), name + ORIGINAL_SUFFIX), overwrite = true)
            }.isSuccess
        }
        // Without its picture the longer clip would export as 1080p frames
        // where the shorter one exported as the picture: worse, not longer.
        if (!kept) {
            withContext(Dispatchers.IO) { runCatching { File(clip.path!!).delete() } }
            return null
        }
        return clip
    }

    /**
     * Writes the picture as the export will read it: upright, no bigger than
     * the biggest export, as a JPEG at a quality nothing shows.
     *
     * Decoded through the same decoder the overlay path uses rather than copied
     * byte for byte: the decoder applies the camera's orientation tag, so what
     * is written needs none, and it is sized on the way in, so a 50-megapixel
     * photo is never held whole. Best effort: a failure leaves no file, and
     * the still exports as the clip.
     */
    private fun keepOriginal(context: Context, image: Uri, name: String) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, image)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                if (long > MAX_EXPORT_SIDE) {
                    val scale = MAX_EXPORT_SIDE.toFloat() / long
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
            val target = File(dir(context), name + ORIGINAL_SUFFIX)
            val partial = File(target.absolutePath + ".part")
            try {
                FileOutputStream(partial).use { decoded.compress(Bitmap.CompressFormat.JPEG, ORIGINAL_JPEG_QUALITY, it) }
            } finally {
                decoded.recycle()
            }
            if (partial.length() <= 0L || !partial.renameTo(target)) {
                partial.delete()
                error("could not keep $target")
            }
        }.onFailure { android.util.Log.w("SquishStill", "could not keep the picture of $image", it) }
    }

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
        val target = File(dir(context), uniqueName("overlay", "png"))
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
    fun isStill(uri: Uri?): Boolean = com.squish.app.timeline.isStillPicture(uri?.toString())

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
     * A freeze frame: the frame of [video] at [sourceMs], kept as a picture under
     * files/stills/ and made into a clip like a photo, so it plays, trims and
     * exports as any still does. At full size (see [FREEZE_MAX_SHORT_SIDE]) -
     * the frame is the shot's own, and a freeze scaled down would visibly
     * soften at the cut into it; it went through the photo cap of 1080 once,
     * so a freeze of 4K footage was upscaled from 1080p between two 4K frames.
     * Null when the frame could not be read or the clip could not be rendered.
     */
    suspend fun freezeFrame(context: Context, video: Uri, sourceMs: Long): Uri? {
        val picture = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                val grabbed: Bitmap? = try {
                    retriever.setDataSource(context, video)
                    // The frame itself, not the nearest keyframe: a freeze a second
                    // off the frame under the playhead is a different picture.
                    retriever.getFrameAtTime(sourceMs * 1_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                } finally {
                    retriever.release()
                }
                val frame = grabbed ?: return@runCatching null
                val target = File(dir(context), uniqueName("freeze", "png"))
                val partial = File(target.absolutePath + ".part")
                try {
                    FileOutputStream(partial).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    frame.recycle()
                }
                if (partial.length() <= 0L || !partial.renameTo(target)) {
                    partial.delete()
                    error("could not keep $target")
                }
                Uri.fromFile(target)
            }.onFailure { android.util.Log.w("SquishStill", "could not freeze a frame of $video", it) }.getOrNull()
        } ?: return null
        return render(context, picture, uniqueName("freeze"), RENDER_MS, maxShortSide = FREEZE_MAX_SHORT_SIDE)
            ?: render(context, picture, uniqueName("freeze"), RENDER_MS)
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
        return render(context, Uri.fromFile(frame), uniqueName("blank"), RENDER_MS)
    }

    private suspend fun render(
        context: Context,
        image: Uri,
        name: String,
        lengthMs: Long,
        maxShortSide: Int = MAX_SHORT_SIDE
    ): Uri? {
        val (w, h) = withContext(Dispatchers.IO) { uprightSize(context, image) } ?: return null
        val (outW, outH) = fit(w, h, maxShortSide)
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
            // phone's encoder takes. Scaled so its short side is at most 1080 -
            // or, for a freeze, the frame's own size (see [freezeFrame]).
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
                // A still is a few kilobytes of picture; the muxer's reserved
                // moov space was four hundred of padding on each one, and a
                // project of twenty photos carried eight megabytes of it.
                .setMuxerFactory(compactMuxerFactory())
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

    /** The frame scaled so its short side is at most [maxShortSide], both sides even as encoders require. */
    private fun fit(width: Int, height: Int, maxShortSide: Int = MAX_SHORT_SIDE): Pair<Int, Int> {
        val scale = minOf(1f, maxShortSide.toFloat() / minOf(width, height))
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

    /**
     * A file name no other still has. The time alone was not: photos picked
     * together are made back to back, two small ones finished in the same
     * millisecond, and the second was renamed over the first - both overlays
     * then showed the second picture, and the first was gone. The name only
     * has to be new; the time stays in it so the folder still sorts by age.
     */
    private fun uniqueName(prefix: String, extension: String? = null): String {
        val name = "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
        return if (extension == null) name else "$name.$extension"
    }

    private const val DIR = "stills"

    /** An overlay picture's longest side; see [overlayFit]. */
    private const val MAX_LONG_SIDE = 3840

    /** A kept picture's longest side: 4K, the biggest frame any export is written at. */
    private const val MAX_EXPORT_SIDE = 3840

    /** Beside the clip, named for it: photo_<stamp>.mp4 and photo_<stamp>.jpg. */
    private const val ORIGINAL_SUFFIX = ".jpg"

    /** High enough that a second encode of a camera JPEG shows nothing. */
    private const val ORIGINAL_JPEG_QUALITY = 95

    /** Small, because it is drawn at nothing; even, because some decoders insist. */
    private const val CLEAR_SIDE = 16

    /** The rate most phone footage runs at, so a dissolve into a photo is as smooth as the shot beside it. */
    private const val FRAME_RATE = 30
}
