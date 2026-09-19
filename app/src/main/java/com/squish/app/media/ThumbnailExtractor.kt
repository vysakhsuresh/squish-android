package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoMeta(val durationMs: Long, val width: Int, val height: Int, val rotationDegrees: Int)

object ThumbnailExtractor {

    suspend fun probe(context: Context, uri: Uri): VideoMeta = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            VideoMeta(duration, width, height, rotation)
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
                    runCatching { retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
                }
            } finally {
                retriever.release()
            }
        }
}
