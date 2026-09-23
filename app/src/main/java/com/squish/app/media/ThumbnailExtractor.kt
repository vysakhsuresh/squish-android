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
    /** As stored in the file, before the container's rotation is applied. */
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val fps: Float,
    /** Whether the file carries sound at all. Unknown reads as yes. */
    val hasAudio: Boolean = true
) {
    /**
     * The shape the picture is actually seen in.
     *
     * A phone shoots portrait as a 1920x1080 stream with a 90 degree rotation tag
     * on it, and every decoder turns it on the way out. Anything that asks the
     * file how wide the video is and believes the answer ends up with a landscape
     * frame for portrait footage - which is how a portrait export came out
     * letterboxed into a landscape box with black down both sides.
     */
    val displayWidth: Int get() = if (quarterTurned) height else width
    val displayHeight: Int get() = if (quarterTurned) width else height

    private val quarterTurned: Boolean get() = rotationDegrees % 180 != 0
}

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

        // The track format wins where it has an answer. MediaMetadataRetriever is
        // documented loosely enough that whether its width is the coded width or
        // the displayed one has varied by device and by version, and getting that
        // backwards silently rotates every export. The extractor's width, height
        // and rotation-degrees are unambiguous: coded, and the tag separately.
        val track = probeVideoTrack(context, uri)
        base.copy(
            width = track?.width?.takeIf { it > 0 } ?: base.width,
            height = track?.height?.takeIf { it > 0 } ?: base.height,
            rotationDegrees = track?.rotation ?: base.rotationDegrees,
            fps = track?.fps ?: 30f,
            hasAudio = track?.hasAudio ?: true
        )
    }

    private data class TrackInfo(
        val width: Int,
        val height: Int,
        val rotation: Int?,
        val fps: Float?,
        val hasAudio: Boolean
    )

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
    private fun probeVideoTrack(context: Context, uri: Uri): TrackInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            // One pass for both questions: the video track's shape, and whether
            // there is any sound in the file at all. Asking separately would open
            // the extractor twice for an answer it already had.
            val hasAudio = (0 until extractor.trackCount).any { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }

            var result: TrackInfo? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue

                val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull() }
                } else {
                    null
                }
                result = TrackInfo(
                    width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0),
                    height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0),
                    // Not every container carries it, and a missing tag is not a
                    // zero - the retriever's answer is better than inventing one.
                    rotation = if (format.containsKey(KEY_ROTATION)) {
                        runCatching { format.getInteger(KEY_ROTATION) }.getOrNull()
                    } else {
                        null
                    },
                    fps = fps?.takeIf { it > 1f && it < 1000f },
                    hasAudio = hasAudio
                )
                break
            }
            // An audio-only file has no video track, and still has to report that
            // it has sound - otherwise extracting audio from an .m4a says it has
            // none, which is the opposite of true.
            result ?: TrackInfo(0, 0, null, null, hasAudio)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** MediaFormat.KEY_ROTATION, spelled out because it is API 23 and this is minSdk-safe. */
    private const val KEY_ROTATION = "rotation-degrees"

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
