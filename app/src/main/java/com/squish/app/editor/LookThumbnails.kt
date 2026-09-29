package com.squish.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.squish.app.media.StillClips
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.effects.Look
import com.squish.app.media.effects.LookPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The frame under the playhead, small, for previewing looks on.
 *
 * One decode, shared by every thumbnail in the row: the panel shows a dozen looks
 * at once and pulling the same frame a dozen times would make opening the panel
 * a visible stall. It is deliberately tiny - a look is a judgement you can make
 * from a hundred pixels, and twelve graded copies of anything larger is real
 * memory for something on screen for a few seconds.
 */
class LookFrame(private val pixels: IntArray, val width: Int, val height: Int) {

    /** This frame with [look] applied, as a bitmap ready to draw. */
    fun graded(look: Look): Bitmap {
        val copy = pixels.copyOf()
        LookPreview.apply(copy, width, height, look)
        return Bitmap.createBitmap(copy, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Across, in pixels. Enough to read a face at thumbnail size, and no more. */
        private const val WIDTH = 96

        suspend fun grab(context: Context, uri: Uri, atMs: Long): LookFrame? =
            withContext(Dispatchers.IO) {
                // A photo on an overlay row is a picture, not footage: the
                // retriever has no frame of it, so it is decoded as the image it is.
                val frame = (
                    if (StillClips.isStill(uri)) StillClips.previewBitmap(context, uri, WIDTH * 2)
                    else ThumbnailExtractor.frameAt(context, uri, atMs)
                    ) ?: return@withContext null
                try {
                    val height = (WIDTH.toFloat() * frame.height / frame.width)
                        .toInt().coerceIn(1, WIDTH * 2)
                    val small = Bitmap.createScaledBitmap(frame, WIDTH, height, true)
                    val pixels = IntArray(WIDTH * height)
                    small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, height)
                    if (small !== frame) small.recycle()
                    LookFrame(pixels, WIDTH, height)
                } catch (t: Throwable) {
                    null
                } finally {
                    frame.recycle()
                }
            }
    }
}

/**
 * Keeps the look row showing the shot you are actually on.
 *
 * [uri] and [sourceMs] are the file and the moment in it that the preview shows
 * under the playhead - the caller resolves them through the clip there, its trim
 * and its speed. This used to be handed the first file opened and the raw
 * timeline time, so with several clips the looks were judged on the wrong shot,
 * at a moment that might not be in it at all.
 *
 * Re-grabbed when the playhead moves somewhere else, but not for every
 * millisecond of a scrub: the thumbnails are for judging a grade, and a grade
 * does not change between neighbouring frames. Rounding the position to a couple
 * of seconds means dragging the playhead across a clip re-decodes a handful of
 * times rather than a hundred.
 */
@Composable
fun rememberLookFrame(uri: Uri?, sourceMs: Long): LookFrame? {
    val context = LocalContext.current
    val bucket = sourceMs / FRAME_BUCKET_MS
    var frame by remember(uri) { mutableStateOf<LookFrame?>(null) }

    LaunchedEffect(uri, bucket) {
        if (uri == null) {
            frame = null
            return@LaunchedEffect
        }
        // Replaced even when the grab fails. Keeping the last frame on failure
        // showed an earlier shot's picture under a playhead that had left it;
        // the swatches are the honest fallback.
        frame = LookFrame.grab(context, uri, bucket * FRAME_BUCKET_MS)
    }
    return frame
}

private const val FRAME_BUCKET_MS = 2_000L
