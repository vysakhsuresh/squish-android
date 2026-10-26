package com.squish.app.timeline

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import com.squish.app.media.video.FilmstripLoader
import com.squish.app.media.video.FilmstripPlan

/**
 * The clip's own pictures, laid along it.
 *
 * A timeline of coloured rectangles tells you where the cuts are and nothing
 * whatsoever about what is in them, so finding the moment you wanted meant
 * scrubbing for it. With the frames on the strip the shot is legible at a
 * glance, which is the single largest readability difference between a toy
 * timeline and one you can actually cut on.
 *
 * Draws nothing at all when the clip is too narrow to carry a picture - the cost
 * of a filmstrip is decoding, and decoding for a thirty-pixel smear is waste.
 */
@Composable
internal fun Filmstrip(
    uri: Uri,
    times: List<Long>,
    modifier: Modifier = Modifier
) {
    com.squish.app.util.PerfFlags.hit("Filmstrip")
    val context = LocalContext.current
    if (times.isEmpty() || com.squish.app.util.PerfFlags.on("nofilm")) return

    // Straight from the cache, so a zoom or a scroll redraws the strip in the
    // same frame instead of blanking it. Reading the arrival count is what
    // redraws it again as the missing tiles land.
    val arrived = FilmstripLoader.arrivals.intValue
    val frames = remember(uri, times, arrived) {
        // A tile still on its way shows its nearest neighbour rather than nothing.
        times.map { FilmstripLoader.cached(uri, it) ?: FilmstripLoader.nearest(uri, it) }
    }

    // Asked again whenever anything lands, not only when the times change. The
    // queue is capped and trims its oldest asks, and a dropped ask leaves no
    // cache entry, nothing in `decoded` and no record that it was ever wanted -
    // so a row whose asks were trimmed away drew as bare lane colour until a
    // scroll or a pinch changed `times`. request() skips what is already
    // cached, so this costs a hash lookup per tile and the loop converges.
    LaunchedEffect(uri, times, arrived) {
        FilmstripLoader.request(context, uri, times)
    }

    // One Canvas for the whole row (FilmstripPlan.tileSpan says why not a
    // Row of Images), each frame cropped to cover its tile as
    // ContentScale.Crop did.
    val images = remember(frames) { frames.map { it?.asImageBitmap() } }
    Canvas(modifier = modifier) {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return@Canvas
        val n = images.size
        for (i in 0 until n) {
            val image = images[i] ?: continue
            val span = FilmstripPlan.tileSpan(i, n, w)
            val tw = span[1] - span[0]
            if (tw <= 0) continue
            val src = FilmstripPlan.coverSource(image.width, image.height, tw, h)
            drawImage(
                image = image,
                srcOffset = IntOffset(src[0], src[1]),
                srcSize = IntSize(src[2] - src[0], src[3] - src[1]),
                dstOffset = IntOffset(span[0], 0),
                dstSize = IntSize(tw, h),
                filterQuality = FilterQuality.Low
            )
        }
    }
}
