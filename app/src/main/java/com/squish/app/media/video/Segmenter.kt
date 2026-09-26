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
    }
}

/**
 * Finds the person in each frame of a clip, on the phone, with Google's
 * MediaPipe selfie segmenter. The model is bundled in the app, so nothing is
 * downloaded and no frame leaves the device.
 */
object Segmenter {

    private const val MODEL = "selfie_segmenter.tflite"
    private const val MASK_SIZE = 128
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
        onProgress: (done: Int, total: Int) -> Unit
    ): String? = withContext(Dispatchers.Default) {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val count = (span / 1000f * SAMPLES_PER_SECOND).roundToInt().coerceIn(2, MAX_SAMPLES)
        val step = span / (count - 1).coerceAtLeast(1)

        val segmenter = runCatching {
            ImageSegmenter.createFromOptions(
                context,
                ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build()
            )
        }.getOrNull() ?: return@withContext null

        val retriever = MediaMetadataRetriever()
        try {
            val times = ArrayList<Long>(count)
            val masks = ArrayList<ByteArray>(count)

            // Exact frames straight off the decoder, in order. The retriever is the
            // fallback: fast only because it hands back the nearest keyframe, which
            // can be a second away - a mask of where the person *was*.
            val decoded = LumaDecoder.decodeColour(context, uri, fromMs, toMs, step, FRAME_SIDE) { at, frame ->
                val mask = ByteArray(MASK_SIZE * MASK_SIZE)
                runCatching { segment(segmenter, frame, mask) }
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
                        runCatching { segment(segmenter, argb, mask) }
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
            val file = File(dir, "${uri.toString().hashCode().toUInt().toString(16)}_${fromMs}_$toMs.bin")
            PersonMasks.write(file, PersonMasks(MASK_SIZE, MASK_SIZE, times.toLongArray(), masks.toTypedArray()))
            file.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { segmenter.close() }
        }
    }

    /** One frame's person confidence, resampled into [out] as 0..255. */
    private fun segment(segmenter: ImageSegmenter, frame: Bitmap, out: ByteArray) {
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
}
