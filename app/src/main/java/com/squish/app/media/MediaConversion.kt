@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Converts a file only the software decoders take into one everything reads,
 * once, as it comes into a project. ConversionRules says why a conversion
 * and not a decode in place; this is the mechanics.
 *
 * The copy lives under `files/converted`, named by the source and its size,
 * and the project keeps the copy's URI as the clip's file - so the strip's
 * frames, the waveform, Freeze, Reverse, Stabilize, the proxy and the export
 * all see an H.264 and AAC file and never know. It is H.264 at the source's
 * own frame (scaled only where the encoder cannot write that frame), at a
 * mezzanine bitrate, with its sound as AAC; an HDR picture is tone-mapped to
 * SDR on the way, since a phone with no 10-bit decoder cannot show HDR
 * anyway. A track the phone decodes is copied without a decode. Software
 * decoding is slow - minutes for a few minutes of 4K - so the caller shows
 * the encoder's own percentage while it runs, the way the proxy's notice does.
 *
 * A conversion that fails returns the original: the preview still plays it
 * through the software renderer, and the export still reads it through the
 * software decoder factory, with the limits SquishFfmpegCodecs describes.
 */
object MediaConversion {

    const val DIR = "converted"

    /** How often the encoder is asked how far it has got. */
    private const val PROGRESS_POLL_MS = 500L

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Whether [uri] is already one of the copies made here. */
    fun isConverted(context: Context, uri: Uri): Boolean =
        uri.scheme == "file" && uri.path?.startsWith(dir(context).absolutePath) == true

    /**
     * The file a project should hold for [uri]: [uri] itself when the phone
     * decodes it, or when nothing does, or when the libraries are missing;
     * otherwise the converted copy, made now if it is not on disk.
     *
     * [onProgress] hears, on the caller's thread, null once when a conversion
     * begins and then the encoder's percentage as it goes; it is not called at
     * all when nothing needs converting, so a caller can show a notice from the
     * first call and take it down when this returns.
     */
    suspend fun prepare(context: Context, uri: Uri, onProgress: (Int?) -> Unit): Uri {
        if (!SoftwareDecoders.available || isConverted(context, uri)) return uri
        val report = MediaCompat.check(context, uri) ?: return uri
        val plan = ConversionRules.plan(report.videoSoftware, report.audioSoftware)
        if (!plan.needed) return uri
        val size = withContext(Dispatchers.IO) { sizeOf(context, uri) }
        val target = fileFor(context, uri, size)
        if (target.exists() && target.length() > 0L) return Uri.fromFile(target)

        onProgress(null)
        val meta = ThumbnailExtractor.probe(context, uri)
        val frame = if (!plan.video) null else {
            val ceiling = withContext(Dispatchers.IO) {
                EncoderCeiling.ceilingShortEdge(
                    ExportPresets.Resolution(meta.displayWidth, meta.displayHeight), MimeTypes.VIDEO_H264
                )
            }
            ConversionRules.frame(meta.displayWidth, meta.displayHeight, ceiling)
        }
        val bitrate = ConversionRules.bitrate(
            frame?.width ?: meta.displayWidth, frame?.height ?: meta.displayHeight,
            meta.fps, ConversionRules.sourceBps(size, meta.durationMs)
        )

        val partial = File(target.absolutePath + ".part")
        runCatching { partial.delete() }
        // Through runCatching so a cancellation - leaving the editor with the
        // notice still up - reaches the delete below rather than leaving a
        // half-written copy for ever, as ProxyEngine learned.
        val outcome = runCatching {
            withContext(Dispatchers.Main) { transcode(context, uri, plan, report.hdr, frame, bitrate, partial, onProgress) }
        }
        if (outcome.getOrDefault(false) && partial.length() > 0L && partial.renameTo(target)) {
            return Uri.fromFile(target)
        }
        runCatching { partial.delete() }
        outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        android.util.Log.w("SquishConvert", "could not convert $uri; keeping the original", outcome.exceptionOrNull())
        return uri
    }

    private suspend fun transcode(
        context: Context,
        uri: Uri,
        plan: ConversionRules.Plan,
        hdr: Boolean,
        frame: ConversionRules.Frame?,
        bitrate: Int,
        output: File,
        onProgress: (Int?) -> Unit
    ): Boolean = suspendCancellableCoroutine { continuation ->
        // A Presentation only when the picture is re-encoded anyway: an effect
        // on the item is itself a reason for Transformer to decode the video.
        val videoEffects: List<Effect> = frame
            ?.let { listOf(Presentation.createForWidthAndHeight(it.width, it.height, Presentation.LAYOUT_SCALE_TO_FIT)) }
            .orEmpty()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(videoEffects)))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            // Tone-mapped only when the picture goes through the decoder; a
            // picture copied as it is keeps whatever it was.
            .setHdrMode(
                if (hdr && plan.video) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                else Composition.HDR_MODE_KEEP_HDR
            )
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()

        var transformer: Transformer? = null
        val handler = Handler(Looper.getMainLooper())
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val session = transformer ?: return
                if (!continuation.isActive) return
                if (session.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                handler.postDelayed(this, PROGRESS_POLL_MS)
            }
        }
        fun finish(built: Boolean) {
            handler.removeCallbacks(poll)
            if (continuation.isActive) continuation.resume(built)
        }

        val session = Transformer.Builder(context)
            .setAssetLoaderFactory(SoftwareDecoders.assetLoaderFactory(context))
            .apply {
                // Naming a codec is what makes Transformer decode and re-encode
                // that track; a track left unnamed is copied as it is.
                if (plan.video) setVideoMimeType(MimeTypes.VIDEO_H264)
                if (plan.audio) setAudioMimeType(MimeTypes.AUDIO_AAC)
            }
            .setEncoderFactory(encoderFactory)
            .setMuxerFactory(compactMuxerFactory())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    android.util.Log.i(
                        "SquishConvert",
                        "done $uri video=${exportResult.videoEncoderName} frames=${exportResult.videoFrameCount} " +
                            "audio=${exportResult.audioEncoderName}"
                    )
                    finish(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    android.util.Log.w("SquishConvert", "failed $uri", exportException)
                    finish(false)
                }
            })
            .build()
        transformer = session

        continuation.invokeOnCancellation {
            handler.removeCallbacks(poll)
            runCatching { session.cancel() }
        }
        runCatching { session.start(composition, output.absolutePath) }
            .onSuccess { handler.postDelayed(poll, PROGRESS_POLL_MS) }
            .onFailure { finish(false) }
    }

    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    /**
     * Named by the source and its size, so the same file picked again finds
     * its copy and a different file at a reused address does not.
     */
    private fun fileFor(context: Context, uri: Uri, size: Long): File {
        val key = (uri.toString() + "|" + size).hashCode().toUInt().toString(16)
        return File(dir(context), "conv_$key.mp4")
    }
}
