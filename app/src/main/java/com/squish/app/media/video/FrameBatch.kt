package com.squish.app.media.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

/**
 * How many frames it is safe to ask a retriever for at once.
 *
 * `getFramesAtIndex(index, count)` decodes every one of those frames before it
 * returns, and hands them back as full-resolution bitmaps. Nothing can be
 * recycled until the call is over, so the peak is the whole batch at once. At 4K
 * that is 35 MB a frame.
 *
 * The analyses used to ask for `BATCH * stride` frames, which had it exactly
 * backwards: the stride grows with the length of the clip, so a longer video
 * asked for *more* frames in one call. A half-hour clip at 30fps came out at 360
 * frames per call — three gigabytes at 1080p, and considerably worse at 4K. That
 * is the crash on large videos, and it was arithmetic rather than bad luck.
 *
 * The budget below is deliberately modest. These analyses run while a preview is
 * playing and two decoders are already alive, so the frame batch is not entitled
 * to the whole heap.
 */
object FrameBatch {

    /** Peak bytes a single batch of decoded frames may occupy. */
    private const val BUDGET_BYTES = 48L * 1024 * 1024

    /** ARGB_8888 — four bytes a pixel, which is what the retriever hands back. */
    private const val BYTES_PER_PIXEL = 4L

    /** Enough to be worth batching; beyond this the decode is not the bottleneck. */
    private const val MAX_FRAMES = 12

    /**
     * Frames per call for a video of this size, never fewer than one.
     *
     * One is the honest answer for 4K: a single 3840x2160 frame is 33 MB, and two
     * of them plus a preview player is where a mid-range phone gives up.
     */
    fun framesPerCall(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val bytesPerFrame = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        if (bytesPerFrame <= 0) return 1
        return (BUDGET_BYTES / bytesPerFrame).toInt().coerceIn(1, MAX_FRAMES)
    }

    /** One call to the retriever: where to start, and how many consecutive frames. */
    data class FrameCall(val startIndex: Int, val count: Int)

    /**
     * The calls needed to visit every wanted frame, and nothing else.
     *
     * Pure, so it can be checked: every wanted frame appears exactly once, no
     * unwanted frame is ever decoded, and no single call exceeds the budget. The
     * old loop got all three wrong at once.
     *
     * With a stride, each call asks for the single frame at that index. Batching
     * consecutive frames only pays when every one of them is wanted; otherwise it
     * decodes twenty-nine frames to look at one.
     */
    fun planCalls(firstIndex: Int, lastIndex: Int, stride: Int, perCall: Int): List<FrameCall> {
        if (lastIndex < firstIndex) return emptyList()
        val step = stride.coerceAtLeast(1)
        val batch = perCall.coerceAtLeast(1)
        val calls = ArrayList<FrameCall>()

        var index = firstIndex
        while (index <= lastIndex) {
            if (step > 1) {
                calls.add(FrameCall(index, 1))
                index += step
            } else {
                val count = minOf(batch, lastIndex - index + 1)
                calls.add(FrameCall(index, count))
                index += count
            }
        }
        return calls
    }

    /**
     * Walks a frame range, handing each wanted frame to [onFrame] and recycling it.
     *
     * [onFrame] receives the absolute frame index, which matters: with a batch
     * size that is no longer a multiple of the stride, an offset within a batch no
     * longer says where a frame sits in the clip.
     */
    inline fun forEachFrame(
        retriever: MediaMetadataRetriever,
        firstIndex: Int,
        lastIndex: Int,
        stride: Int,
        perCall: Int,
        onBatch: () -> Unit = {},
        onFrame: (index: Int, bitmap: Bitmap) -> Unit
    ) {
        for (call in planCalls(firstIndex, lastIndex, stride, perCall)) {
            val frames: List<Bitmap>? =
                runCatching { retriever.getFramesAtIndex(call.startIndex, call.count) }.getOrNull()
            if (frames.isNullOrEmpty()) return

            frames.forEachIndexed { offset, bitmap ->
                onFrame(call.startIndex + offset, bitmap)
                bitmap.recycle()
            }
            onBatch()
        }
    }
}
