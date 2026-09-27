package com.squish.app.media.audio

/**
 * Stub for the JVM harness.
 *
 * The real one is built from decoded PCM and so drags MediaCodec in with it. The
 * timeline model only ever carries these around by reference, so the shape is
 * all that is needed here.
 */
class Waveform(val peaks: FloatArray, val durationMs: Long)
