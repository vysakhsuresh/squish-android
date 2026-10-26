package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * A clip's person masks through time: one small greyscale image per sample,
 * 255 where the person is.
 */
class PersonMasks(val width: Int, val height: Int, val timesMs: LongArray, val masks: Array<ByteArray>) {

    /** The index of the mask nearest [timeMs], in the clip's source time. */
    fun indexAt(timeMs: Long): Int {
        if (timesMs.isEmpty()) return -1
        var lo = 0
        var hi = timesMs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (timesMs[mid] < timeMs) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && timeMs - timesMs[lo - 1] < timesMs[lo] - timeMs) lo--
        return lo
    }

    companion object {
        private const val MAGIC = 0x53514D4B // "SQMK"

        fun write(file: File, masks: PersonMasks) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(masks.timesMs.size)
                o.writeInt(masks.width)
                o.writeInt(masks.height)
                masks.timesMs.forEach { o.writeLong(it) }
                masks.masks.forEach { o.write(it) }
            }
            tmp.renameTo(file)
        }

        /** Loaded masks, kept by file, so the preview and the export share one copy. */
        private val cache = LinkedHashMap<String, PersonMasks>(4, 0.75f, true)

        @Synchronized
        fun load(path: String): PersonMasks? {
            cache[path]?.let { return it }
            val loaded = runCatching {
                DataInputStream(File(path).inputStream().buffered()).use { i ->
                    if (i.readInt() != MAGIC) return@runCatching null
                    val count = i.readInt()
                    val w = i.readInt()
                    val h = i.readInt()
                    val times = LongArray(count) { i.readLong() }
                    val data = Array(count) { ByteArray(w * h).also { bytes -> i.readFully(bytes) } }
                    PersonMasks(w, h, times, data)
                }
            }.getOrNull() ?: return null
            cache[path] = loaded
            while (cache.size > 3) cache.remove(cache.keys.first())
            return loaded
        }

        /** Drops cached masks whose files are not in [keep]: they have been swept. */
        @Synchronized
        fun forget(keep: Set<String>) {
            cache.keys.retainAll { it in keep }
        }
    }
}

/**
 * Finds the person in each frame of a clip, on the phone, with Google's
 * MediaPipe selfie segmenter. The model is bundled in the app, so nothing is
 * downloaded and no frame leaves the device.
 */
object Segmenter {

    private const val MODEL = "selfie_segmenter.tflite"
    internal const val MASK_SIZE = 128
    private const val SAMPLES_PER_SECOND = 10f
    private const val MAX_SAMPLES = 900
    private const val FRAME_SIDE = 320

    /**
     * Masks for [fromMs]..[toMs] of [uri], written to a file under the app's
     * storage. Returns the file's path, or null if the clip could not be read.
     */
    suspend fun analyze(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        /**
         * Cut out the thing at this point (fractions of the picture) rather than
         * the person (EDGE_PLAN F7): MediaPipe's MagicTouch, pointed at the tap
         * on the first frame and, on each frame after, at the point deepest
         * inside the last frame's cut-out (MaskRefine.innerPoint) - so the
         * thing is followed as it moves and turns.
         */
        pointAt: Pair<Float, Float>? = null,
        onProgress: (done: Int, total: Int) -> Unit
    ): String? = withContext(Dispatchers.Default) {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val count = (span / 1000f * SAMPLES_PER_SECOND).roundToInt().coerceIn(2, MAX_SAMPLES)
        val step = span / (count - 1).coerceAtLeast(1)

        val thing = pointAt?.let { createTouch(context) ?: return@withContext null }
        var point = pointAt
        // One frame's mask: the person, or the thing at the point carried along.
        val segmentOne: (Bitmap, ByteArray) -> Unit = { frame, mask ->
            val at = point
            if (thing != null && at != null) {
                segmentAt(thing, frame, at, mask)
                MaskRefine.innerPoint(mask, MASK_SIZE, MASK_SIZE, at)?.let { point = it }
            }
        }
        val segmenter = if (thing != null) null else (create(context) ?: return@withContext null)

        val retriever = com.squish.app.av.AvRetriever()
        try {
            val times = ArrayList<Long>(count)
            val masks = ArrayList<ByteArray>(count)

            // Exact frames straight off the decoder, in order. The retriever is the
            // fallback: fast only because it hands back the nearest keyframe, which
            // can be a second away - a mask of where the person *was*.
            val decoded = LumaDecoder.decodeColour(context, uri, fromMs, toMs, step, FRAME_SIDE) { at, frame ->
                val mask = ByteArray(MASK_SIZE * MASK_SIZE)
                runCatching { if (segmenter != null) segment(segmenter, frame, mask) else segmentOne(frame, mask) }
                // Its edge onto the picture's own (EDGE_PLAN F7, MaskRefine).
                runCatching { refine(frame, mask) }
                frame.recycle()
                times += at
                masks += mask
                onProgress(((at - fromMs) * count / span).toInt().coerceIn(1, count), count)
            }

            if (!decoded || times.isEmpty()) {
                times.clear()
                masks.clear()
                retriever.setDataSource(context, uri)
                for (i in 0 until count) {
                    currentCoroutineContext().ensureActive()
                    val at = fromMs + i * step
                    val mask = ByteArray(MASK_SIZE * MASK_SIZE)
                    val frame = runCatching {
                        retriever.getScaledFrameAtTime(at * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_SIDE, FRAME_SIDE)
                    }.getOrNull()
                    if (frame != null) {
                        val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame
                        else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
                        runCatching { if (segmenter != null) segment(segmenter, argb, mask) else segmentOne(argb, mask) }
                        runCatching { refine(argb, mask) }
                        argb.recycle()
                    } else {
                        masks.lastOrNull()?.copyInto(mask)
                    }
                    times += at
                    masks += mask
                    onProgress(i + 1, count)
                }
            }
            onProgress(count, count)
            val dir = File(context.filesDir, "segments").apply { mkdirs() }
            val tag = pointAt?.let { (x, y) -> "_at${(x * 1000).roundToInt()}x${(y * 1000).roundToInt()}" } ?: ""
            val file = File(dir, "${uri.toString().hashCode().toUInt().toString(16)}_${fromMs}_$toMs$tag.bin")
            // The flicker between samples taken out (EDGE_PLAN F7): the outline no longer boils.
            val steady = MaskRefine.temporal(masks, MASK_SIZE * MASK_SIZE)
            PersonMasks.write(file, PersonMasks(MASK_SIZE, MASK_SIZE, times.toLongArray(), steady.toTypedArray()))
            file.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { segmenter?.close() }
            runCatching { thing?.close() }
        }
    }

    /**
     * Deletes every mask file no draft names any more (V19). Each analysis
     * wrote a file under files/segments and nothing ever removed one: turning
     * background removal off, or deleting the draft, left tens of megabytes
     * behind per clip. [keep] is every path still referenced - by the drafts
     * on disk (ProjectAutosave.referencedMaskFiles) and the edit open now.
     * A file younger than a minute is an analysis about to be referenced and
     * is left alone. Blocking; call it off the main thread.
     */
    fun sweep(context: Context, keep: Set<String>) {
        val dir = File(context.filesDir, "segments")
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        files.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.absolutePath in keep) return@forEach
            if (now - file.lastModified() < SWEEP_GRACE_MS) return@forEach
            runCatching { file.delete() }
        }
        PersonMasks.forget(keep)
    }

    private const val SWEEP_GRACE_MS = 60_000L

    /** One frame's person confidence, resampled into [out] as 0..255. */
    /** The person model, or null where it will not load; the caller closes it. Auto-reframe uses it too. */
    internal fun create(context: Context): ImageSegmenter? = runCatching {
        ImageSegmenter.createFromOptions(
            context,
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
        )
    }.getOrNull()

    /**
     * MediaPipe's MagicTouch (EDGE_PLAN F7): cuts out whatever is at a point -
     * a thing, an animal, a person. Apache-2.0 by its own model card (read
     * 26 Oct 2026, storage.googleapis.com/mediapipe-assets/Model Card
     * MagicTouch.pdf); the 6.2 MB float32 v1, bundled.
     */
    internal fun createTouch(context: Context): com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter? = runCatching {
        com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.createFromOptions(
            context,
            com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(TOUCH_MODEL).build())
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
        )
    }.getOrNull()

    private const val TOUCH_MODEL = "magic_touch.tflite"

    /** The thing at [point] in [frame], resampled into [out] as 0..255. */
    internal fun segmentAt(
        segmenter: com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter,
        frame: Bitmap,
        point: Pair<Float, Float>,
        out: ByteArray
    ) {
        val roi = com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter.RegionOfInterest.create(
            com.google.mediapipe.tasks.components.containers.NormalizedKeypoint.create(point.first.coerceIn(0f, 1f), point.second.coerceIn(0f, 1f))
        )
        val result = segmenter.segment(BitmapImageBuilder(frame).build(), roi)
        val all = result.confidenceMasks().orElse(null) ?: return
        if (all.isEmpty()) return
        // One mask, the thing; or the background first and the thing after it.
        writeMask(if (all.size >= 2) all[1] else all[0], out)
    }

    private fun writeMask(image: com.google.mediapipe.framework.image.MPImage, out: ByteArray) {
        val buffer = ByteBufferExtractor.extract(image).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val w = image.width
        val h = image.height
        for (y in 0 until MASK_SIZE) {
            val sy = (y * h / MASK_SIZE).coerceIn(0, h - 1)
            for (x in 0 until MASK_SIZE) {
                val sx = (x * w / MASK_SIZE).coerceIn(0, w - 1)
                out[y * MASK_SIZE + x] = (buffer.get(sy * w + sx).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            }
        }
    }

    internal fun segment(segmenter: ImageSegmenter, frame: Bitmap, out: ByteArray) {
        val result = segmenter.segment(BitmapImageBuilder(frame).build())
        val all = result.confidenceMasks().orElse(null) ?: return
        if (all.isEmpty()) return
        // The selfie model gives one mask, the person; multi-class models give a
        // background mask first and the person after it.
        val image = if (all.size >= 2) all[1] else all[0]
        val buffer = ByteBufferExtractor.extract(image).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val w = image.width
        val h = image.height
        for (y in 0 until MASK_SIZE) {
            val sy = (y * h / MASK_SIZE).coerceIn(0, h - 1)
            for (x in 0 until MASK_SIZE) {
                val sx = (x * w / MASK_SIZE).coerceIn(0, w - 1)
                val v = buffer.get(sy * w + sx)
                out[y * MASK_SIZE + x] = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            }
        }
    }

    /** [mask]'s edge pulled onto [frame]'s own edges, at the mask's size (MaskRefine.guided). */
    internal fun refine(frame: Bitmap, mask: ByteArray) {
        val small = Bitmap.createScaledBitmap(frame, MASK_SIZE, MASK_SIZE, true)
        val px = IntArray(MASK_SIZE * MASK_SIZE)
        small.getPixels(px, 0, MASK_SIZE, 0, 0, MASK_SIZE, MASK_SIZE)
        if (small !== frame) small.recycle()
        val guide = FloatArray(px.size) { i ->
            val c = px[i]
            (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
        }
        val m = FloatArray(mask.size) { (mask[it].toInt() and 0xFF) / 255f }
        val out = MaskRefine.guided(guide, m, MASK_SIZE, MASK_SIZE)
        for (i in out.indices) mask[i] = (out[i] * 255f).roundToInt().coerceIn(0, 255).toByte()
    }
}
