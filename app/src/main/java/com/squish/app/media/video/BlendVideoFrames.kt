package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.squish.app.av.AvDecoder
import com.squish.app.av.AvFile
import com.squish.app.av.AvFrames
import com.squish.app.av.AvNative
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One decoded frame of a blended overlay, and a number that changes when the picture does. */
class BlendFrame(val bitmap: Bitmap, val version: Long)

/**
 * A blended video overlay's frames, decoded by hand through Squish's FFmpeg
 * (EDGE_PLAN F6): the picture the blend shader reads in place of a still.
 *
 * Media3's compositor blends source-over and offers no hook, so a video
 * overlay with a blend mode is not a layer at all: each shot under it asks
 * this for the overlay's frame at a moment and blends it in its own chain,
 * the way a blended still already is. [BlendFrameRules] decides whether the
 * frame in hand still serves, or the decoder goes on, or seeks.
 *
 * [frameAt] blocks while it decodes: the export calls it from its GL thread,
 * where waiting is right - every frame of the file has to be the right one.
 * The preview goes through [Async], which never waits.
 *
 * Frames come out upright, their longer side [maxSide], as ARGB bitmaps.
 */
class BlendVideoFrames(private val context: Context, private val uri: Uri, private val maxSide: Int) : Closeable {

    private var file: AvFile? = null
    private var decoder: AvDecoder? = null
    private var view: AvFrames.View? = null
    private val frame = AvDecoder.Frame()
    private var streamIndex = -1
    private var startUs = 0L
    private var frameMs = BlendFrameRules.DEFAULT_FRAME_MS
    private var opened = false
    private var failed = false
    private var closed = false

    private var heldMs: Long? = null
    private var held: BlendFrame? = null
    private var version = 0L

    /** The overlay's frame on screen at [sourceMs] of its own file, or null when it cannot be read. */
    @Synchronized
    fun frameAt(sourceMs: Long): BlendFrame? {
        if (closed || failed) return held
        if (!opened && !open()) return null
        val f = file ?: return null
        val d = decoder ?: return null
        try {
            when (BlendFrameRules.step(heldMs, sourceMs, frameMs)) {
                BlendFrameRules.Step.Hold -> return held
                BlendFrameRules.Step.Seek -> {
                    if (!AvFrames.seekToKeyFrame(f, d, streamIndex, frame, sourceMs * 1000L + startUs)) return held
                    // The key frame is in hand: take it if it is the one, else go on from it.
                    val at = timeOf() ?: Long.MIN_VALUE
                    if (at != Long.MIN_VALUE && BlendFrameRules.takes(at, sourceMs, frameMs)) return keep(at)
                    heldMs = at.takeIf { it != Long.MIN_VALUE }
                }
                BlendFrameRules.Step.Decode -> {}
            }
            while (true) {
                if (AvNative.fileDecodeNext(f.nativeHandle, d.nativeHandle, streamIndex, frame.meta) != AvNative.OK) {
                    // The end of the file: the last frame stays on screen.
                    return held
                }
                val at = timeOf() ?: continue
                if (BlendFrameRules.takes(at, sourceMs, frameMs)) return keep(at)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decoding $uri at $sourceMs", t)
            failed = true
            return held
        }
    }

    private fun timeOf(): Long? = frame.ptsUs.takeIf { it != Long.MIN_VALUE }?.let { (it - startUs) / 1000L }

    private fun keep(atMs: Long): BlendFrame? {
        heldMs = atMs
        val bitmap = view?.bitmap(maxSide) ?: return held
        // The one it replaces is not recycled: the GL thread may be uploading it
        // this moment, and a recycled bitmap there is a crash; the collector has it.
        held = BlendFrame(bitmap, ++version)
        return held
    }

    private fun open(): Boolean {
        opened = true
        val f = AvFile.open(context, uri)
        val stream = f?.info?.video
        val d = if (f != null && stream != null) AvDecoder.forStream(f, stream.index) else null
        if (f == null || stream == null || d == null) {
            d?.close()
            f?.close()
            Log.w(TAG, "cannot read the picture of $uri")
            failed = true
            return false
        }
        file = f
        decoder = d
        streamIndex = stream.index
        startUs = f.info.startUs
        frameMs = BlendFrameRules.frameMs(stream.fps)
        view = AvFrames.View(d, stream, frame)
        return true
    }

    @Synchronized
    override fun close() {
        closed = true
        runCatching { decoder?.close() }
        runCatching { file?.close() }
        decoder = null
        file = null
        view = null
    }

    /**
     * The preview's way in: [latest] answers at once with the frame decoded
     * nearest the last moment asked, and asks a worker thread for this one.
     * A paused frame comes through on the next ask, which a redraw makes.
     */
    class Async(private val frames: BlendVideoFrames, private val onFrame: () -> Unit = {}) : Closeable {
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "SquishBlendFrames").apply { isDaemon = true } }
        private val wanted = AtomicLong(Long.MIN_VALUE)
        private val served = AtomicLong(Long.MIN_VALUE)
        private val working = AtomicBoolean(false)
        private val latest = AtomicReference<BlendFrame?>(null)
        @Volatile private var closed = false

        fun latest(sourceMs: Long): BlendFrame? {
            wanted.set(sourceMs)
            kick()
            return latest.get()
        }

        // Goes when a moment is asked that the worker has not answered and it
        // is idle. Checked again as the worker stops, so a moment asked in the
        // instant between its last look and its stopping is not left unanswered.
        private fun kick() {
            if (closed || wanted.get() == served.get() || !working.compareAndSet(false, true)) return
            runCatching { worker.execute(::work) }.onFailure { working.set(false) }
        }

        private fun work() {
            try {
                while (!closed) {
                    val at = wanted.get()
                    val got = frames.frameAt(at)
                    served.set(at)
                    if (got != null && got !== latest.getAndSet(got)) onFrame()
                    if (wanted.get() == at) break
                }
            } finally {
                working.set(false)
            }
            kick()
        }

        override fun close() {
            closed = true
            worker.shutdown()
            // The decoder is closed once the worker is off it, not under it.
            Thread { runCatching { worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }; frames.close() }.start()
        }
    }

    private companion object {
        const val TAG = "SquishBlend"
    }
}
