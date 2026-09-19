package com.squish.app.media.audio

import kotlin.math.abs
import kotlin.math.sqrt

class Waveform(val peaks: FloatArray, val durationMs: Long)

object WaveformBuilder {

    /** Peak-per-bucket, normalized to 0..1, for drawing a timeline lane. */
    fun build(pcm: MonoPcm, buckets: Int = 480): Waveform {
        if (pcm.samples.isEmpty() || buckets <= 0) return Waveform(FloatArray(0), pcm.durationMs)
        val out = FloatArray(buckets)
        val perBucket = (pcm.samples.size.toFloat() / buckets).coerceAtLeast(1f)
        var maxPeak = 0f
        for (b in 0 until buckets) {
            val start = (b * perBucket).toInt()
            val end = ((b + 1) * perBucket).toInt().coerceAtMost(pcm.samples.size)
            var peak = 0f
            for (i in start until end) {
                val v = abs(pcm.samples[i])
                if (v > peak) peak = v
            }
            out[b] = peak
            if (peak > maxPeak) maxPeak = peak
        }
        if (maxPeak > 0f) for (b in out.indices) out[b] = out[b] / maxPeak
        return Waveform(out, pcm.durationMs)
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
