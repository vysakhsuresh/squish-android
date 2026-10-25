package com.squish.app.media.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Voice only" and "Remove voice" (EDGE_PLAN G3): a clip's voice taken apart
 * from everything else by DTLN (Westhausen and Meyer, 2020; MIT, the two
 * TFLite models under assets/dtln/), a small real-time speech-enhancement
 * network trained to keep speech and drop what is not speech - noise, room,
 * and most music.
 *
 * Everything here but the network itself is arithmetic that can be executed:
 * the resampling to the 16 kHz the network was trained at and back, the
 * network's own block loop (512 samples, moved on 128 at a time, a spectral
 * mask and then a learned time-domain stage, overlapped and added), and the
 * mix of the voice and the rest at the level the sheet asks.
 *
 * "Remove voice" is the original less the voice, at the clip's own rate, so
 * what is left keeps everything above the network's 8 kHz; "Voice only" is
 * the voice, which is speech-band by nature, with as much of the rest as the
 * level leaves in.
 */
object VoiceSplit {

    enum class Mode(val label: String) { VoiceOnly("Voice only"), RemoveVoice("Remove voice") }

    /** The rate DTLN runs at. */
    const val MODEL_RATE = 16_000
    const val BLOCK = 512
    const val SHIFT = 128
    const val BINS = BLOCK / 2 + 1

    /** How late the network's output is against its input, in model samples. */
    const val LATENCY = BLOCK - SHIFT

    /** The longest window split in one go: about 110 MB of floats at 48 kHz for five minutes. */
    const val MAX_MS = 5L * 60 * 1000

    /** The network as the block loop sees it. [DtlnTflite] is the real one. */
    interface Network {
        /** The mask for one block's magnitude spectrum, [BINS] values in, [BINS] out. */
        fun mask(magnitude: FloatArray): FloatArray
        /** The second stage: one masked block in the time domain, [BLOCK] in, [BLOCK] out. */
        fun refine(block: FloatArray): FloatArray
        /** Back to silence heard: a new file. */
        fun reset()
    }

    /**
     * The voice in [input] (mono, [MODEL_RATE]), the same length and in step
     * with it: the network's latency is taken off the front and the tail is
     * flushed with silence. [onProgress] hears 0..1 now and then.
     */
    fun voiceOf(input: FloatArray, network: Network, onProgress: (Float) -> Unit = {}): FloatArray {
        network.reset()
        val padded = FloatArray(LATENCY + input.size + BLOCK)
        input.copyInto(padded, LATENCY)
        val out = FloatArray(padded.size)
        val buffer = FloatArray(BLOCK)
        val acc = FloatArray(BLOCK)
        val re = FloatArray(BLOCK)
        val im = FloatArray(BLOCK)
        val mag = FloatArray(BINS)
        var written = 0
        var at = 0
        val steps = (padded.size - SHIFT) / SHIFT
        var step = 0
        while (at + SHIFT <= padded.size) {
            // The newest 128 samples in at the end of the block, as the
            // network's own real-time loop has them.
            System.arraycopy(buffer, SHIFT, buffer, 0, BLOCK - SHIFT)
            System.arraycopy(padded, at, buffer, BLOCK - SHIFT, SHIFT)
            at += SHIFT
            // Spectrum, unwindowed - the network was trained on np.fft.rfft of the raw block.
            for (i in 0 until BLOCK) { re[i] = buffer[i]; im[i] = 0f }
            Fft.transform(re, im)
            for (k in 0 until BINS) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            val m = network.mask(mag)
            for (k in 0 until BINS) { re[k] *= m[k]; im[k] *= m[k] }
            // The mirror half, so the inverse is real.
            for (k in BINS until BLOCK) { re[k] = re[BLOCK - k]; im[k] = -im[BLOCK - k] }
            // Inverse through the forward transform: conjugate, transform, conjugate, scale.
            for (k in 0 until BLOCK) im[k] = -im[k]
            Fft.transform(re, im)
            val block = FloatArray(BLOCK) { re[it] / BLOCK }
            val refined = network.refine(block)
            // Overlap and add: the first SHIFT of the sum is finished.
            for (i in 0 until BLOCK) acc[i] += refined[i]
            if (written + SHIFT <= out.size) System.arraycopy(acc, 0, out, written, SHIFT)
            written += SHIFT
            System.arraycopy(acc, SHIFT, acc, 0, BLOCK - SHIFT)
            java.util.Arrays.fill(acc, BLOCK - SHIFT, BLOCK, 0f)
            if (++step % 256 == 0) onProgress(step.toFloat() / steps)
        }
        onProgress(1f)
        // The block loop hands back the sample that went in LATENCY + (BLOCK - SHIFT)
        // earlier: once for the padding in front, once for the block filling up.
        val lag = 2 * LATENCY
        return FloatArray(input.size) { i -> out.getOrElse(i + lag) { 0f } }
    }

    /**
     * [x] at [from] Hz as [to] Hz: band-limited, through a Hann-windowed sinc
     * [TAPS] wide that cuts below the lower of the two Nyquists - the network
     * hears no alias of what was above 8 kHz, and the way back puts no image
     * of it there.
     */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x.copyOf()
        // As a ratio of whole numbers, up by L and down by M: an output sample's
        // centre falls on one of only L fractional places between input samples,
        // so the kernel is worked out once for each place - polyphase - rather than
        // a sine and a cosine per tap per sample, which took 8.5 s each way over
        // 18 s of sound on the moto while the network itself took 1.2.
        val g = gcd(from, to)
        val up = to / g
        val down = from / g
        val ratio = up.toDouble() / down
        val n = (x.size.toLong() * up / down).toInt()
        // In the units of the slower side (the kernel is stretched to them below), so
        // one cutoff - just under its Nyquist - serves both ways.
        val cutoff = 0.94
        val half = TAPS / 2
        // Spread by how much is thrown away, so a downsample's kernel spans as many source samples.
        val stretch = if (ratio < 1.0) 1.0 / ratio else 1.0
        val reach = (half * stretch).toInt()
        val taps = 2 * reach
        val kernels = Array(up) { phase ->
            val frac = phase.toDouble() / up
            FloatArray(taps) { k ->
                // Tap k reads input sample floor(centre) - reach + 1 + k.
                val t = (frac + reach - 1 - k) / stretch
                (window(t, half) * sinc(t * cutoff) * cutoff).toFloat()
            }
        }
        val sums = FloatArray(up) { p -> kernels[p].sum() }
        val out = FloatArray(n)
        for (i in 0 until n) {
            val pos = i.toLong() * down
            val base = (pos / up).toInt()
            val phase = (pos % up).toInt()
            val kern = kernels[phase]
            val first = base - reach + 1
            var sum = 0f
            if (first >= 0 && first + taps <= x.size) {
                for (k in 0 until taps) sum += x[first + k] * kern[k]
                out[i] = sum / sums[phase]
            } else {
                // At the ends, divided by the weights that fell inside the file, so
                // the level holds there too.
                var weights = 0f
                for (k in 0 until taps) {
                    val j = first + k
                    if (j < 0 || j >= x.size) continue
                    sum += x[j] * kern[k]
                    weights += kern[k]
                }
                out[i] = if (weights != 0f) sum / weights else 0f
            }
        }
        return out
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun sinc(x: Double): Double = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

    private fun window(t: Double, half: Int): Double =
        if (abs(t) >= half) 0.0 else 0.5 + 0.5 * cos(PI * t / half)

    private const val TAPS = 32

    /**
     * What is heard for [mode] at [level] (0..1), from the clip's own sound and
     * its voice at the same rate and in step: "Voice only" fades the rest out
     * as the level rises, "Remove voice" takes the voice out as it does. At
     * level 0 either is the sound as it was.
     */
    fun mix(original: FloatArray, voice: FloatArray, mode: Mode, level: Float): FloatArray {
        val l = level.coerceIn(0f, 1f)
        return FloatArray(original.size) { i ->
            val v = voice.getOrElse(i) { 0f }
            val rest = original[i] - v
            when (mode) {
                Mode.VoiceOnly -> v + (1f - l) * rest
                Mode.RemoveVoice -> rest + (1f - l) * v
            }.coerceIn(-1f, 1f)
        }
    }
}
