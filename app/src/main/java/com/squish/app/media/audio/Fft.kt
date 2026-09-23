package com.squish.app.media.audio

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * An in-place radix-2 FFT, and the magnitude spectrum built on it.
 *
 * Written here rather than pulled in because the whole of it is forty lines and a
 * dependency would be a build risk for something this well defined. It exists for
 * one caller - [BeatDetector] - which needs the spectrum of a short window to see
 * where the energy in a piece of music suddenly arrives.
 *
 * Time-domain energy is not enough for this. A snare over a sustained bass note
 * barely moves the overall level, but it lands almost entirely in bins the bass is
 * not using, so it is obvious in the spectrum and invisible in the waveform. That
 * difference is the difference between finding the beat in real music and only
 * finding it in a drum loop.
 */
object Fft {

    /**
     * Transforms [real] and [imaginary] in place. Both must be the same power-of-two
     * length.
     */
    fun transform(real: FloatArray, imaginary: FloatArray) {
        val n = real.size
        require(n == imaginary.size) { "mismatched halves" }
        require(n > 0 && (n and (n - 1)) == 0) { "length must be a power of two" }

        // Bit-reversal permutation, so the butterflies below can run in place.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wReal = cos(angle).toFloat()
            val wImaginary = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curReal = 1f
                var curImaginary = 0f
                for (k in 0 until len / 2) {
                    val aReal = real[i + k]
                    val aImaginary = imaginary[i + k]
                    val bReal = real[i + k + len / 2] * curReal - imaginary[i + k + len / 2] * curImaginary
                    val bImaginary = real[i + k + len / 2] * curImaginary + imaginary[i + k + len / 2] * curReal

                    real[i + k] = aReal + bReal
                    imaginary[i + k] = aImaginary + bImaginary
                    real[i + k + len / 2] = aReal - bReal
                    imaginary[i + k + len / 2] = aImaginary - bImaginary

                    val nextReal = curReal * wReal - curImaginary * wImaginary
                    curImaginary = curReal * wImaginary + curImaginary * wReal
                    curReal = nextReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * The magnitude of the first half of the spectrum of a real signal.
     *
     * Only the first half: for real input the second is its mirror image, so
     * summing over all of it would count every bin twice and tell you nothing new.
     *
     * The caller owns [real] and [imaginary] and they are overwritten. A track has
     * tens of thousands of frames in it, and allocating two arrays per frame is how
     * an analysis that should take a second spends it in the collector instead.
     */
    fun magnitudes(
        windowed: FloatArray,
        real: FloatArray,
        imaginary: FloatArray,
        into: FloatArray
    ) {
        windowed.copyInto(real)
        java.util.Arrays.fill(imaginary, 0f)
        transform(real, imaginary)
        for (k in into.indices) into[k] = hypot(real[k], imaginary[k])
    }

    /** A Hann window of [size], so a windowed frame does not leak across every bin. */
    fun hann(size: Int): FloatArray = FloatArray(size) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))).toFloat()
    }
}
