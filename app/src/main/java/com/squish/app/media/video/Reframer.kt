package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Finds where the subject is through a clip, so a crop can follow it.
 *
 * Two signals, both on the device and neither needing a model download:
 *
 * - **Faces**, from the platform's FaceDetector. When there are people, they are
 *   almost always what the shot is about.
 * - **Motion**, where there are no faces: the part of the frame that changes most
 *   between samples - the dancer, the car, the dog.
 *
 * With neither, the centre holds. The raw path is then smoothed heavily, because a
 * crop that jitters after every detection looks far worse than one that lags a
 * little: the result should read as a camera operator panning, not a tracker.
 */
object Reframer {

    /** Samples a second: enough to follow people, few enough to analyse a long clip quickly. */
    private const val SAMPLES_PER_SECOND = 4f
    private const val MAX_SAMPLES = 360
    private const val ANALYSIS_WIDTH = 256
    private const val GRID = 16

    /**
     * The subject's path from [fromMs] to [toMs] of [uri], in source time and in
     * fractions of the frame. Null if the file cannot be read at all.
     */
    suspend fun analyze(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): MotionTrack? = withContext(Dispatchers.IO) {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val count = (span / 1000f * SAMPLES_PER_SECOND).roundToInt().coerceIn(2, MAX_SAMPLES)
        val step = span / (count - 1).coerceAtLeast(1)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
        } catch (t: Throwable) {
            runCatching { retriever.release() }
            return@withContext null
        }

        val raw = ArrayList<TrackSample>(count)
        var previous: FloatArray? = null
        try {
            for (i in 0 until count) {
                if (!currentCoroutineContext().isActive) return@withContext null
                val at = fromMs + i * step
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(
                        at * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        ANALYSIS_WIDTH,
                        ANALYSIS_WIDTH * 2
                    )
                }.getOrNull()
                if (frame == null) {
                    onProgress(i + 1, count)
                    continue
                }
                val grid = lumaGrid(frame)
                val face = faceCentre(frame)
                val motion = previous?.let { motionCentre(it, grid) }
                previous = grid
                frame.recycle()

                val (x, y, confidence) = when {
                    face != null -> Triple(face.x, face.y, 1f)
                    motion != null -> Triple(motion.x, motion.y, 0.6f)
                    else -> Triple(0.5f, 0.5f, 0.2f)
                }
                raw.add(TrackSample(atMs = at, xFraction = x, yFraction = y, confidence = confidence))
                onProgress(i + 1, count)
            }
        } finally {
            runCatching { retriever.release() }
        }
        if (raw.isEmpty()) return@withContext null
        MotionTrack(smooth(raw))
    }

    /** The middle of the faces in view, weighted to the bigger (nearer) ones. */
    private fun faceCentre(frame: Bitmap): PointF? {
        // FaceDetector wants RGB_565 and an even width.
        val w = frame.width and 1.inv()
        if (w < 32 || frame.height < 32) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(frame, 0, 0, w, frame.height).copy(Bitmap.Config.RGB_565, false)
        }.getOrNull() ?: return null
        return try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val found = runCatching { FaceDetector(w, bitmap.height, MAX_FACES).findFaces(bitmap, faces) }
                .getOrDefault(0)
            if (found <= 0) return null
            var sx = 0f
            var sy = 0f
            var total = 0f
            val p = PointF()
            for (i in 0 until found) {
                val face = faces[i] ?: continue
                if (face.confidence() < 0.3f) continue
                face.getMidPoint(p)
                // Eyes are above the middle of a face; aim a little lower, at the face.
                val weight = face.eyesDistance() * face.eyesDistance()
                sx += p.x * weight
                sy += (p.y + face.eyesDistance() * 0.6f) * weight
                total += weight
            }
            if (total <= 0f) null else PointF(sx / total / w, sy / total / bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Mean brightness in a [GRID] x [GRID] block layout. */
    private fun lumaGrid(frame: Bitmap): FloatArray {
        val out = FloatArray(GRID * GRID)
        val bw = frame.width / GRID
        val bh = frame.height / GRID
        if (bw <= 0 || bh <= 0) return out
        val row = IntArray(frame.width)
        for (gy in 0 until GRID) {
            for (y in gy * bh until (gy + 1) * bh step 2) {
                frame.getPixels(row, 0, frame.width, 0, y, frame.width, 1)
                for (gx in 0 until GRID) {
                    var s = 0f
                    for (x in gx * bw until (gx + 1) * bw step 2) {
                        val c = row[x]
                        s += ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
                    }
                    out[gy * GRID + gx] += s
                }
            }
        }
        return out
    }

    /**
     * Where the frame changed most, or null when nothing changed enough to mean
     * anything - a still shot should hold its framing, not drift toward noise.
     */
    private fun motionCentre(a: FloatArray, b: FloatArray): PointF? {
        val diff = FloatArray(a.size) { abs(a[it] - b[it]) }
        val mean = diff.average().toFloat()
        val peak = diff.maxOrNull() ?: return null
        if (peak < mean * 2.5f || peak <= 1f) return null
        var sx = 0f
        var sy = 0f
        var total = 0f
        for (i in diff.indices) {
            val w = (diff[i] - mean).coerceAtLeast(0f)
            if (w <= 0f) continue
            sx += ((i % GRID) + 0.5f) / GRID * w
            sy += ((i / GRID) + 0.5f) / GRID * w
            total += w
        }
        return if (total <= 0f) null else PointF(sx / total, sy / total)
    }

    /**
     * Confidence-weighted Gaussian smoothing, about three quarters of a second
     * each way: slow enough to read as a deliberate pan, quick enough to keep a
     * walking person in frame.
     */
    private fun smooth(samples: List<TrackSample>): List<TrackSample> {
        val sigma = SAMPLES_PER_SECOND * 0.75f
        val radius = (sigma * 3).roundToInt()
        return samples.indices.map { i ->
            var sx = 0f
            var sy = 0f
            var total = 0f
            for (j in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(samples.lastIndex)) {
                val d = (j - i).toFloat()
                val w = exp(-(d * d) / (2 * sigma * sigma)) * samples[j].confidence
                sx += samples[j].xFraction * w
                sy += samples[j].yFraction * w
                total += w
            }
            val s = samples[i]
            if (total <= 0f) s else s.copy(xFraction = sx / total, yFraction = sy / total)
        }
    }

    private const val MAX_FACES = 4
}
