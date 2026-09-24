package com.squish.app.media.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Automatic dual-system-sound alignment.
 *
 * Given a video (whose camera mic recorded a rough scratch track) and a separately
 * recorded audio file of the same moment, finds the offset that lines them up, by
 * cross-correlating their loudness envelopes. Same principle desktop tools use;
 * this runs entirely on-device in a few hundred milliseconds.
 *
 * Offset convention, used identically by the preview player and the exporter:
 *   at video time t, the aligned sample of the external track is at (t + offsetMs).
 * A positive offset means the external recorder was rolling BEFORE the camera.
 */
object AudioSyncAnalyzer {

    class Result(val offsetMs: Long, val confidence: Float)

    private const val COARSE_BUCKET_MS = 10
    private const val FINE_BUCKET_MS = 1
    private const val ANALYSIS_WINDOW_MS = 45_000L
    private const val MIN_OVERLAP_BUCKETS = 100

    suspend fun detectOffset(
        context: Context,
        videoUri: Uri,
        audioUri: Uri,
        maxLagMs: Long = 8_000L
    ): Result? = withContext(Dispatchers.Default) {
        val videoPcm = PcmDecoder.decodeMono(context, videoUri, maxDurationMs = ANALYSIS_WINDOW_MS)
            ?: return@withContext null
        val audioPcm = PcmDecoder.decodeMono(context, audioUri, maxDurationMs = ANALYSIS_WINDOW_MS)
            ?: return@withContext null

        val coarseRef = WaveformBuilder.envelope(videoPcm, COARSE_BUCKET_MS)
        val coarseExt = WaveformBuilder.envelope(audioPcm, COARSE_BUCKET_MS)
        if (coarseRef.size < MIN_OVERLAP_BUCKETS || coarseExt.size < MIN_OVERLAP_BUCKETS) return@withContext null

        val coarseLagLimit = (maxLagMs / COARSE_BUCKET_MS).toInt()
        val coarse = bestLag(coarseRef, coarseExt, -coarseLagLimit, coarseLagLimit) ?: return@withContext null
        val coarseOffsetMs = coarse.first.toLong() * COARSE_BUCKET_MS

        val fineRef = WaveformBuilder.envelope(videoPcm, FINE_BUCKET_MS)
        val fineExt = WaveformBuilder.envelope(audioPcm, FINE_BUCKET_MS)
        if (fineRef.size < MIN_OVERLAP_BUCKETS || fineExt.size < MIN_OVERLAP_BUCKETS) {
            return@withContext Result(coarseOffsetMs, coarse.second)
        }

        val center = coarseOffsetMs.toInt() / FINE_BUCKET_MS
        val window = COARSE_BUCKET_MS / FINE_BUCKET_MS + 2
        val fine = bestLag(fineRef, fineExt, center - window, center + window)
            ?: return@withContext Result(coarseOffsetMs, coarse.second)

        Result(fine.first.toLong() * FINE_BUCKET_MS, max(coarse.second, fine.second))
    }

    /**
     * Normalized cross-correlation over a lag range. Returns the winning lag (in
     * buckets) and a confidence from 0 to 1, or null if nothing correlated meaningfully.
     */
    private fun bestLag(ref: FloatArray, ext: FloatArray, minLag: Int, maxLag: Int): Pair<Int, Float>? {
        var winner = 0
        var best = -1f

        for (lag in minLag..maxLag) {
            val start = max(0, -lag)
            val end = min(ref.size, ext.size - lag)
            val overlap = end - start
            if (overlap < MIN_OVERLAP_BUCKETS) continue

            var dot = 0f
            var normRef = 0f
            var normExt = 0f
            for (i in start until end) {
                val a = ref[i]
                val b = ext[i + lag]
                dot += a * b
                normRef += a * a
                normExt += b * b
            }
            val denominator = sqrt(normRef * normExt)
            if (denominator <= 1e-6f) continue

            val score = dot / denominator
            if (score > best) {
                best = score
                winner = lag
            }
        }

        if (best <= 0f) return null
        return winner to abs(best).coerceIn(0f, 1f)
    }
}
