package com.squish.app.media.audio

/** Stub for the JVM harness: the decoded sound the segmenter reads, without the decoder (which is Android's). */
class MonoPcm(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate <= 0) 0L else samples.size * 1000L / sampleRate
}
