@file:OptIn(UnstableApi::class)

package com.squish.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Low-resolution stand-ins for heavy footage, so the timeline stays responsive.
 *
 * Scrubbing a 4K60 clip means the decoder must produce a 8.3-megapixel frame for
 * every drag of your finger, and no phone does that smoothly. Every professional
 * NLE solves this the same way and has for twenty years: edit against a small
 * proxy, render from the original. Squish does exactly that - the preview player
 * is handed the proxy, while the export pipeline never sees it and always reads
 * the camera original, so nothing about the finished file is degraded.
 *
 * Proxies live in the cache directory. Android may delete them under storage
 * pressure, which is correct: they are derived data and regenerate in the
 * background the next time the clip is opened.
 */
object ProxyEngine {

    /** Footage below this needs no help; making a proxy would cost more than it saves. */
    private const val PROXY_THRESHOLD_LONG_EDGE = 1920

    const val PROXY_HEIGHT = 540
    private const val PROXY_BITRATE = 2_500_000

    /**
     * The widest a proxy frame can be, for anyone budgeting memory against it.
     *
     * The height is fixed and the width follows the source's aspect, so the
     * widest case is an unusually wide frame rather than a typical one - which is
     * the right way round for a budget.
     */
    const val PROXY_WIDTH_HINT = 1280

    /**
     * Longest source worth making a proxy of.
     *
     * A proxy is a full re-encode of the whole file. That is a fine trade on a
     * two-minute 4K clip - a few seconds of work for an hour of smooth scrubbing -
     * and a terrible one on a three-hour recording, where it is tens of minutes of
     * encoding, several gigabytes into the cache, and a second encoder session
     * running the entire time the editor is open. It was started automatically on
     * import, so a long file put the app under that load before the user had
     * touched anything.
     *
     * Past this the editor plays the original. Heavier per frame, but the whole
     * file is not re-encoded to find that out.
     */
    private const val PROXY_MAX_DURATION_MS = 15L * 60_000

    fun isWorthProxying(width: Int, height: Int, durationMs: Long): Boolean =
        maxOf(width, height) > PROXY_THRESHOLD_LONG_EDGE &&
            durationMs in 1..PROXY_MAX_DURATION_MS

    /**
     * The proxy for this clip if one is already on disk. Instant and side-effect
     * free - the editor calls it on load to decide whether to show any progress at
     * all.
     */
    fun cached(context: Context, uri: Uri): File? =
        fileFor(context, uri).takeIf { it.exists() && it.length() > 0 }

    /**
     * Builds the proxy if it is missing. Returns null when the source cannot be
     * transcoded, and that is not an error the user needs to hear about: the
     * editor simply keeps playing the original, which still works, just heavier.
     */
    suspend fun ensure(context: Context, uri: Uri): File? {
        cached(context, uri)?.let { return it }

        val target = fileFor(context, uri)
        val partial = File(target.absolutePath + ".part")
        runCatching { partial.delete() }

        // Transformer posts callbacks to the looper it was built on, so it is built
        // and started on the main thread exactly like the export path. The encoding
        // itself runs on the library's own threads; this coroutine only waits.
        val built = withContext(Dispatchers.Main) { transcode(context, uri, partial) }

        return if (built && partial.length() > 0 && partial.renameTo(target)) {
            target
        } else {
            runCatching { partial.delete() }
            null
        }
    }

    fun evict(context: Context, uri: Uri) {
        runCatching { fileFor(context, uri).delete() }
    }

    /** Total bytes currently held by proxies, for the storage line in Settings. */
    fun cacheSizeBytes(context: Context): Long =
        proxyDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(context: Context) {
        proxyDir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private suspend fun transcode(context: Context, uri: Uri, output: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            // Declared as List<Effect> before the copy: Java generics are invariant,
            // so an ImmutableList<Presentation> will not satisfy ImmutableList<Effect>.
            val videoEffects: List<Effect> = listOf(Presentation.createForHeight(PROXY_HEIGHT))

            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(videoEffects)))
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(PROXY_BITRATE).build()
                )
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        // Deliberately silent. A proxy is an optimization; failing to
                        // build one must never interrupt an edit in progress.
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
                .build()

            continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
            runCatching { transformer.start(item, output.absolutePath) }
                .onFailure { if (continuation.isActive) continuation.resume(false) }
        }

    private fun proxyDir(context: Context): File =
        File(context.cacheDir, "proxies").apply { mkdirs() }

    /**
     * Named by a hash of the content URI. Two different clips can never collide,
     * and re-opening the same clip finds its proxy immediately.
     */
    private fun fileFor(context: Context, uri: Uri): File {
        val key = uri.toString().hashCode().toUInt().toString(16)
        return File(proxyDir(context), "proxy_$key.mp4")
    }
}
