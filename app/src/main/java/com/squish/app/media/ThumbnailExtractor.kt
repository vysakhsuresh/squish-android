package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoMeta(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val fps: Float
)

object ThumbnailExtractor {

    suspend fun probe(context: Context, uri: Uri): VideoMeta = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val base = try {
            retriever.setDataSource(context, uri)
            VideoMeta(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                fps = 30f
            )
        } finally {
            retriever.release()
        }
        base.copy(fps = probeFrameRate(context, uri) ?: 30f)
    }

    /** Container duration for any media file, including audio-only tracks. */
    suspend fun probeDurationMs(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            retriever.release()
        }
    }

    /**
     * MediaMetadataRetriever has no reliable frame-rate key, so read it off the
     * video track format directly. Frame rate drives every precision control in
     * the editor, so guessing 30 everywhere would put nudges off on 24/60fps clips.
     */
    private fun probeFrameRate(context: Context, uri: Uri): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var result: Float? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        result = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                            .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull() }
                    }
                    break
                }
            }
            result?.takeIf { it > 1f && it < 1000f }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * One frame, scaled down, for sampling a color out of.
     *
     * Scaled because a 4K frame is ~35 MB and this is held in Compose state while a
     * panel is open; a few hundred pixels across is far more than enough to pick a
     * screen color from, and averaging over the downscale actually helps - it
     * smooths the sensor noise that would otherwise make two adjacent taps on the
     * same green give two different answers.
     */
    suspend fun frameAt(context: Context, uri: Uri, timeMs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getScaledFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    SAMPLE_WIDTH_PX,
                    SAMPLE_HEIGHT_PX
                )
            } catch (t: Throwable) {
                null
            } finally {
                retriever.release()
            }
        }

    suspend fun extractFrames(context: Context, uri: Uri, count: Int, durationMs: Long): List<Bitmap> =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0 || count <= 0) return@withContext emptyList()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val step = durationMs / count
                (0 until count).mapNotNull { i ->
                    val timeUs = (i * step + step / 2) * 1000
                    runCatching {
                        // Scaled decode: full-resolution frames for a 4K clip would be
                        // ~35 MB each and OOM the filmstrip on mid-range devices.
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            THUMB_WIDTH_PX,
                            THUMB_HEIGHT_PX
                        )
                    }.getOrNull()
                }
            } finally {
                retriever.release()
            }
        }

    private const val SAMPLE_WIDTH_PX = 480
    private const val SAMPLE_HEIGHT_PX = 480

    private const val THUMB_WIDTH_PX = 160
    private const val THUMB_HEIGHT_PX = 160
}
