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
    /**
     * The long side frames are analysed at. 256 found no face across a table:
     * the platform detector wants a face tens of pixels wide (ReframeRules).
     */
    private const val ANALYSIS_SIDE = 480
    private const val GRID = 16

    /**
     * The subject's path from [fromMs] to [toMs] of [uri], in source time and in
     * fractions of the frame. Null if the file cannot be read at all.
     *
     * Frames are decoded exactly and in order (LumaDecoder, as the cut-out
     * does), not asked of the retriever by time: its quick answer is the
     * nearest keyframe, the same picture for seconds on a phone clip, which
     * left one usable sample in thirty-two. The retriever stays as the
     * fallback, asked for the closest frame rather than the closest keyframe.
     */
    suspend fun analyze(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): MotionTrack? = withContext(Dispatchers.Default) {
        val span = (toMs - fromMs).coerceAtLeast(1L)
        val step = ReframeRules.sampleStepMs(fromMs, toMs, SAMPLES_PER_SECOND, MAX_SAMPLES)
        val count = (span / step + 1).toInt()
        val segmenter = Segmenter.create(context)
        // EDGE_PLAN F8: MediaPipe's faces and things, bundled, nothing sent anywhere.
        val faces = Detectors.faces(context)
        val things = Detectors.things(context)
        var aimed: FloatArray? = null
        val mask = ByteArray(Segmenter.MASK_SIZE * Segmenter.MASK_SIZE)
        val raw = ArrayList<TrackSample>(count)
        var previous: FloatArray? = null

        fun read(at: Long, frame: Bitmap) {
            val grid = lumaGrid(frame)
            val image = runCatching { com.google.mediapipe.framework.image.BitmapImageBuilder(frame).build() }.getOrNull()
            val face = faces?.let { d -> image?.let { Detectors.seenFaces(d, it, frame.width, frame.height) } }?.let { ReframeRules.faceCentre(it) }
                ?: faceCentre(frame)?.let { floatArrayOf(it.x, it.y) }
            val person = if (face == null && segmenter != null) {
                java.util.Arrays.fill(mask, 0)
                runCatching { Segmenter.segment(segmenter, frame, mask) }
                ReframeRules.maskCentre(mask, Segmenter.MASK_SIZE, Segmenter.MASK_SIZE)
            } else null
            val motion = previous?.let { motionCentre(it, grid) }?.let { floatArrayOf(it.x, it.y) }
            previous = grid
            // A thing - a dog, a car - only where no face or person was found, as the rules rank them.
            val thing = if (face == null && person == null) things?.let { d -> image?.let { Detectors.seenThings(d, it, frame.width, frame.height) } }?.let { ReframeRules.pickSubject(it, aimed) } else null
            val (x, y, confidence) = ReframeRules.aim(face, person, motion, thing).let { Triple(it[0], it[1], it[2]) }
            aimed = floatArrayOf(x, y)
            raw.add(TrackSample(atMs = at, xFraction = x, yFraction = y, confidence = confidence))
            onProgress(((at - fromMs) * count / span).toInt().coerceIn(1, count), count)
        }

        try {
            val decoded = LumaDecoder.decodeColour(context, uri, fromMs, toMs, step, ANALYSIS_SIDE) { at, frame ->
                if (!currentCoroutineContext().isActive) return@decodeColour
                val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame
                else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
                read(at, argb)
                argb.recycle()
            }
            if (!decoded || raw.isEmpty()) {
                raw.clear()
                previous = null
                val retriever = com.squish.app.av.AvRetriever()
                try {
                    try {
                        retriever.setDataSource(context, uri)
                    } catch (t: Throwable) {
                        return@withContext null
                    }
                    for (i in 0 until count) {
                        if (!currentCoroutineContext().isActive) return@withContext null
                        val at = fromMs + i * step
                        val frame = runCatching {
                            retriever.getScaledFrameAtTime(at * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, ANALYSIS_SIDE, ANALYSIS_SIDE)
                        }.getOrNull() ?: continue
                        val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame
                        else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
                        read(at, argb)
                        argb.recycle()
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("SquishReframe", "analysing $uri", t)
        } finally {
            runCatching { segmenter?.close() }
            runCatching { faces?.close() }
            runCatching { things?.close() }
        }
        if (!currentCoroutineContext().isActive) return@withContext null
        if (raw.isEmpty()) return@withContext null
        val found = raw.count { it.confidence > ReframeRules.NOTHING }
        android.util.Log.i("SquishReframe", "${raw.size} frames, subject in $found (faces ${raw.count { it.confidence == ReframeRules.FACE }}, person ${raw.count { it.confidence == ReframeRules.PERSON }}, motion ${raw.count { it.confidence == ReframeRules.MOTION }})")
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
     * Confidence-weighted Gaussian smoothing, [SMOOTH_SECONDS] each way: slow
     * enough to read as a deliberate pan, quick enough to keep a walking person
     * in frame.
     *
     * In `smoothedTrack` rather than here, because the window has to be sized
     * in seconds and the spacing read off the samples - see there for what
     * sizing it in samples cost on a clip over ninety seconds.
     */
    private fun smooth(samples: List<TrackSample>): List<TrackSample> =
        smoothedTrack(samples, SMOOTH_SECONDS)

    /** How wide the smoothing window is, each way. */
    private const val SMOOTH_SECONDS = 0.75f

    private const val MAX_FACES = 4
}
