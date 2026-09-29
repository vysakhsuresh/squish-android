package com.squish.app.media.audio

import kotlin.math.abs
import kotlin.math.sqrt

class Waveform(val peaks: FloatArray, val durationMs: Long)

object WaveformBuilder {

    /**
     * How much of a sound one drawn peak stands for. Fixed, rather than a fixed
     * number of peaks per file: 480 peaks across a ten-minute song was one per
     * 1.25 s, which drew a hit as a plateau, while the same 480 across a
     * two-second effect was finer than the strip could show. Twenty a second
     * is a beat readable at any zoom the strip reaches.
     */
    const val BUCKET_MS = 50

    /**
     * Peak-per-bucket, normalized to 0..1, for drawing a timeline lane - one
     * bucket per [bucketMs] of the sound.
     */
    fun build(pcm: MonoPcm, bucketMs: Int = BUCKET_MS): Waveform {
        if (pcm.samples.isEmpty() || pcm.sampleRate <= 0) return Waveform(FloatArray(0), pcm.durationMs)
        val perBucket = (pcm.sampleRate.toLong() * bucketMs / 1000L).toInt().coerceAtLeast(1)
        val buckets = (pcm.samples.size + perBucket - 1) / perBucket
        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val start = b * perBucket
            val end = (start + perBucket).coerceAtMost(pcm.samples.size)
            var peak = 0f
            for (i in start until end) {
                val v = abs(pcm.samples[i])
                if (v > peak) peak = v
            }
            out[b] = peak
        }
        return fromPeaks(out, pcm.durationMs)
    }

    /** A fixed number of peaks across the whole sound, for a card of fixed width (the quick tools' preview). */
    fun buildBars(pcm: MonoPcm, buckets: Int): Waveform {
        if (pcm.samples.isEmpty() || buckets <= 0) return Waveform(FloatArray(0), pcm.durationMs)
        val out = FloatArray(buckets)
        val perBucket = (pcm.samples.size.toFloat() / buckets).coerceAtLeast(1f)
        for (b in 0 until buckets) {
            val start = (b * perBucket).toInt()
            val end = ((b + 1) * perBucket).toInt().coerceAtMost(pcm.samples.size)
            var peak = 0f
            for (i in start until end) {
                val v = abs(pcm.samples[i])
                if (v > peak) peak = v
            }
            out[b] = peak
        }
        return fromPeaks(out, pcm.durationMs)
    }

    /** Peaks already taken per bucket (see PcmDecoder.decodePeaks), normalized so the loudest reaches the top. */
    fun fromPeaks(peaks: FloatArray, durationMs: Long): Waveform {
        var maxPeak = 0f
        for (v in peaks) if (v > maxPeak) maxPeak = v
        if (maxPeak > 0f) for (b in peaks.indices) peaks[b] = peaks[b] / maxPeak
        return Waveform(peaks, durationMs)
    }

    /**
     * RMS energy envelope at a fixed bucket size, mean-removed. This is the signal
     * cross-correlated for automatic sync - amplitude envelopes survive the huge
     * timbre difference between a phone mic and an external recorder, where raw
     * sample correlation would not.
     */
    fun envelope(pcm: MonoPcm, bucketMs: Int): FloatArray {
        if (pcm.samples.isEmpty() || bucketMs <= 0) return FloatArray(0)
        val samplesPerBucket = (pcm.sampleRate * bucketMs / 1000).coerceAtLeast(1)
        val count = pcm.samples.size / samplesPerBucket
        if (count <= 0) return FloatArray(0)

        val env = FloatArray(count)
        for (b in 0 until count) {
            var sumSq = 0f
            val start = b * samplesPerBucket
            for (i in start until start + samplesPerBucket) {
                val v = pcm.samples[i]
                sumSq += v * v
            }
            env[b] = sqrt(sumSq / samplesPerBucket)
        }

        var mean = 0f
        for (v in env) mean += v
        mean /= count
        for (b in env.indices) env[b] = env[b] - mean
        return env
    }
}
