package com.squish.app.media.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio finishing (EDGE_PLAN G4): a five-band equaliser with presets, a
 * compressor, a limiter, and loudness measured the broadcast way (ITU-R
 * BS.1770) so a sound can be brought to -14 LUFS, where the platforms play
 * things. Free of Android - FinishProcessor runs it inside Media3's chain,
 * tools/jvm/AudioFinishChecks.kt runs it on tones whose answers are known.
 */
object AudioFinish {

    /** The five bands' middles, Hz, and how far each may go, dB. */
    val BANDS = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)
    const val MAX_DB = 12f

    /** The shapes a person reaches for, each a gain per band. */
    enum class Preset(val label: String, val gains: FloatArray) {
        Flat("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
        Voice("Voice", floatArrayOf(-6f, -1f, 2f, 4f, 1f)),
        Podcast("Podcast", floatArrayOf(-4f, 2f, 1f, 3f, -1f)),
        BassBoost("Bass boost", floatArrayOf(8f, 4f, 0f, 0f, 0f)),
        Phone("Phone", floatArrayOf(-12f, -6f, 4f, 2f, -12f)),
        Warm("Warm", floatArrayOf(3f, 2f, 0f, -2f, -3f)),
        Bright("Bright", floatArrayOf(-1f, 0f, 0f, 3f, 5f));

        companion object {
            /** The preset [gains] are, or null for a shape of the person's own. */
            fun of(gains: List<Float>): Preset? = entries.firstOrNull { p -> p.gains.indices.all { abs(p.gains[it] - gains.getOrElse(it) { 0f }) < 0.05f } }
        }
    }

    /** One second-order section (RBJ's cookbook), run per channel. */
    class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double, channels: Int) {
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)

        fun run(x: Double, ch: Int): Double {
            val y = b0 * x + z1[ch]
            z1[ch] = b1 * x - a1 * y + z2[ch]
            z2[ch] = b2 * x - a2 * y
            return y
        }

        /** The section's gain at [hz], as a factor, for the checks. */
        fun response(hz: Double, rate: Double): Double {
            val w = 2 * PI * hz / rate
            val (cr, ci) = cos(w) to -sin(w)
            val (c2r, c2i) = cos(2 * w) to -sin(2 * w)
            val nr = b0 + b1 * cr + b2 * c2r; val ni = b1 * ci + b2 * c2i
            val dr = 1 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
            return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        }

        companion object {
            fun peaking(hz: Float, db: Float, q: Float, rate: Int, channels: Int): Biquad {
                val a = 10.0.pow(db / 40.0)
                val w = 2 * PI * hz / rate
                val alpha = sin(w) / (2 * q)
                val a0 = 1 + alpha / a
                return Biquad((1 + alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha / a) / a0, channels)
            }

            fun shelf(hz: Float, db: Float, high: Boolean, rate: Int, channels: Int): Biquad {
                val a = 10.0.pow(db / 40.0)
                val w = 2 * PI * hz / rate
                val cw = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0) // a shelf slope of 1
                val s = 2 * sqrt(a) * alpha
                return if (!high) {
                    val a0 = (a + 1) + (a - 1) * cw + s
                    Biquad(a * ((a + 1) - (a - 1) * cw + s) / a0, 2 * a * ((a - 1) - (a + 1) * cw) / a0, a * ((a + 1) - (a - 1) * cw - s) / a0,
                        -2 * ((a - 1) + (a + 1) * cw) / a0, ((a + 1) + (a - 1) * cw - s) / a0, channels)
                } else {
                    val a0 = (a + 1) - (a - 1) * cw + s
                    Biquad(a * ((a + 1) + (a - 1) * cw + s) / a0, -2 * a * ((a - 1) + (a + 1) * cw) / a0, a * ((a + 1) + (a - 1) * cw - s) / a0,
                        2 * ((a - 1) - (a + 1) * cw) / a0, ((a + 1) - (a - 1) * cw - s) / a0, channels)
                }
            }
        }
    }

    /** The five bands as filters: shelves at the two ends, peaks between. A band at 0 dB is left out. */
    class Equaliser(gains: FloatArray, rate: Int, channels: Int) {
        private val sections: List<Biquad> = BANDS.indices.mapNotNull { i ->
            val g = gains.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB)
            if (abs(g) < 0.05f || BANDS[i] >= rate * 0.45f) null
            else when (i) {
                0 -> Biquad.shelf(BANDS[i], g, high = false, rate = rate, channels = channels)
                BANDS.size - 1 -> Biquad.shelf(BANDS[i], g, high = true, rate = rate, channels = channels)
                else -> Biquad.peaking(BANDS[i], g, 1.1f, rate, channels)
            }
        }
        val isFlat: Boolean get() = sections.isEmpty()

        fun run(x: Float, ch: Int): Float {
            var y = x.toDouble()
            for (s in sections) y = s.run(y, ch)
            return y.toFloat()
        }

        fun response(hz: Double, rate: Double): Double = sections.fold(1.0) { acc, s -> acc * s.response(hz, rate) }
    }

    /**
     * Evens the loud and the quiet: above [thresholdDb] the level rises only
     * 1/[ratio] as fast, with [makeupDb] after; the level is followed with an
     * attack and a release so it breathes rather than pumps. One detector for
     * all channels, so the picture of the stereo does not move.
     */
    class Compressor(
        private val thresholdDb: Float, private val ratio: Float, private val makeupDb: Float,
        attackMs: Float, releaseMs: Float, rate: Int
    ) {
        private val attack = exp(-1.0 / (rate * attackMs / 1000.0))
        private val release = exp(-1.0 / (rate * releaseMs / 1000.0))
        private var envDb = -120.0
        private val makeup = 10.0.pow(makeupDb / 20.0)

        /** The gain for this moment, from the frame's loudest channel [peak]. */
        fun gain(peak: Float): Float {
            val db = 20 * log10(max(abs(peak).toDouble(), 1e-6))
            val k = if (db > envDb) attack else release
            envDb = k * envDb + (1 - k) * db
            val over = envDb - thresholdDb
            val reduce = if (over > 0) over * (1 - 1 / ratio) else 0.0
            return (10.0.pow(-reduce / 20.0) * makeup).toFloat()
        }
    }

    /**
     * Never over [ceiling]: the gain falls at once to what the next
     * [LOOKAHEAD_MS] need and climbs back over [RELEASE_MS]. The samples are
     * delayed by the look-ahead so the gain is down before the peak arrives.
     */
    class Limiter(private val ceiling: Float, rate: Int, private val channels: Int, lookaheadMs: Float = LOOKAHEAD_MS) {
        private val ahead = max(1, (rate * lookaheadMs / 1000f).toInt())
        private val delay = Array(channels) { FloatArray(ahead) }
        private val peaks = FloatArray(ahead)
        private var pos = 0
        private var gain = 1f
        private val recover = (1 - exp(-1.0 / (rate * RELEASE_MS / 1000.0))).toFloat()

        /** One frame in [frame] (one sample a channel), limited in place; what comes out is [ahead] frames late. */
        fun run(frame: FloatArray) {
            var peak = 0f
            for (c in 0 until channels) peak = max(peak, abs(frame[c]))
            peaks[pos] = peak
            var need = 0f
            for (p in peaks) need = max(need, p)
            val wanted = if (need > ceiling) ceiling / need else 1f
            gain = if (wanted < gain) wanted else gain + (wanted - gain) * recover
            for (c in 0 until channels) {
                val out = delay[c][pos]
                delay[c][pos] = frame[c]
                frame[c] = (out * gain).coerceIn(-ceiling, ceiling)
            }
            pos = (pos + 1) % ahead
        }

        val latencyFrames: Int get() = ahead

        companion object {
            const val LOOKAHEAD_MS = 5f
            const val RELEASE_MS = 80f
        }
    }

    /** -1 dBFS: under the platforms' true-peak ceiling once their encoders have had it. */
    const val CEILING = 0.891f

    /** Where streaming plays things, LUFS. */
    const val TARGET_LUFS = -14f

    /**
     * Integrated loudness, LUFS (ITU-R BS.1770-4): K-weighted, 400 ms blocks a
     * quarter apart, those under -70 LUFS left out, then those 10 LU under the
     * rest. [interleaved] at [rate] with [channels]; NaN for silence.
     */
    fun lufs(interleaved: FloatArray, rate: Int, channels: Int): Float {
        val frames = interleaved.size / channels
        if (frames <= 0) return Float.NaN
        // The two K-weighting stages in RBJ's form, with the corner and gain that form needs to match
        // BS.1770's own 48 kHz coefficients (the ones pyloudnorm uses; the analogue prototype's
        // 1681.97 Hz read 0.26 dB low at 1 kHz through this form).
        val shelf = Biquad.shelf(1_500f, 4f, high = true, rate = rate, channels = channels)
        val hp = highPass(38f, 0.5f, rate, channels)
        val weighted = DoubleArray(frames)
        for (f in 0 until frames) {
            var s = 0.0
            for (c in 0 until channels) {
                val y = hp.run(shelf.run(interleaved[f * channels + c].toDouble(), c), c)
                s += y * y
            }
            weighted[f] = s
        }
        val block = (rate * 0.4).toInt()
        val hop = block / 4
        if (frames < block) return loudnessOf(weighted.average())
        val blocks = ArrayList<Double>()
        var start = 0
        while (start + block <= frames) {
            var s = 0.0
            for (i in start until start + block) s += weighted[i]
            blocks += s / block
            start += hop
        }
        val absolute = blocks.filter { loudnessOf(it) > -70f }
        if (absolute.isEmpty()) return Float.NaN
        val relative = loudnessOf(absolute.average()) - 10f
        val gated = absolute.filter { loudnessOf(it) > relative }
        return loudnessOf(gated.average())
    }

    private fun loudnessOf(meanSquare: Double): Float = (-0.691 + 10 * log10(max(meanSquare, 1e-12))).toFloat()

    private fun highPass(hz: Float, q: Float, rate: Int, channels: Int): Biquad {
        val w = 2 * PI * hz / rate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        val cw = cos(w)
        return Biquad((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0, channels)
    }

    /** The level that takes a sound measured at [lufs] to [TARGET_LUFS], as a factor; 1 for silence. */
    fun gainTo(lufs: Float, target: Float = TARGET_LUFS): Float =
        if (lufs.isNaN() || lufs.isInfinite()) 1f else 10.0.pow((target - lufs) / 20.0).toFloat().coerceIn(0.05f, 8f)

    /** The level for a sound measured at [lufs], held under [maxLevel], and where that level really lands. */
    data class Leveled(val level: Float, val reachesLufs: Float, val onTarget: Boolean)

    // A quiet phone recording wants more than a clip's level may give (4x), and
    // the sheet said "set for -14" while the clip stopped two decibels short.
    fun levelFor(lufs: Float, maxLevel: Float, target: Float = TARGET_LUFS): Leveled {
        val level = gainTo(lufs, target).coerceIn(0f, maxLevel)
        val reaches = lufs + 20f * kotlin.math.log10(level)
        return Leveled(level, reaches, kotlin.math.abs(reaches - target) < 0.1f)
    }
}

/** What a clip's finishing is set to: the five band gains, dB, and how much it is compressed, 0..1. */
data class FinishSettings(
    val gains: List<Float> = FLAT,
    val compress: Float = 0f,
    /**
     * The clip's level, applied here in float ahead of the limiter. A boost given
     * to a GainProcessor came out of it as 16-bit, already clipped flat, so the
     * limiter behind it could only turn a clipped sound down - "Bring to -14 LUFS"
     * on a quiet recording would have distorted its loudest words.
     */
    val level: Float = 1f
) {
    val isOff: Boolean get() = gains.all { kotlin.math.abs(it) < 0.05f } && compress <= 0f && kotlin.math.abs(level - 1f) < 1e-4f

    companion object {
        val FLAT = listOf(0f, 0f, 0f, 0f, 0f)
        val OFF = FinishSettings()
    }
}

/** A compressor for [amount] of the Compress slider: gentle at the start, a broadcast squeeze at the end, with the level it took back made up by half. */
fun AudioFinish.compressorFor(amount: Float, rate: Int): AudioFinish.Compressor {
    val a = amount.coerceIn(0f, 1f)
    val threshold = -10f - 20f * a
    val ratio = 1.5f + 4.5f * a
    val makeup = -threshold * (1f - 1f / ratio) * 0.5f
    return AudioFinish.Compressor(threshold, ratio, makeup, 8f, 150f, rate)
}

/** A clip's finishing from its two fields: the preview's sinks and the export's processors read it the same way. */
fun finishOf(eqGains: List<Float>, compress: Float): FinishSettings =
    FinishSettings(eqGains.ifEmpty { FinishSettings.FLAT }, compress)
