import com.squish.app.media.audio.NoiseReducer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.system.exitProcess

/*
 * EDGE_PLAN G2 / INSHOT_PARITY P2: noise taken out from *under* the voice -
 * a fan, traffic, mains hum - not only between words, which is all Enhance
 * did. NoiseReducer is the arithmetic; NoiseProcessor runs it inside Media3's
 * audio chain for the preview and the export alike.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val RATE = 48_000

/** Speech-like: harmonics of a gliding 140-200 Hz voice, gated into syllables of ~220 ms with gaps. */
private fun speech(seconds: Double): FloatArray {
    val n = (seconds * RATE).toInt()
    val out = FloatArray(n)
    var phase = 0.0
    for (i in 0 until n) {
        val t = i.toDouble() / RATE
        val f0 = 170 + 30 * sin(2 * PI * 0.7 * t)
        phase += 2 * PI * f0 / RATE
        val syllable = (t % 0.35) < 0.22
        val env = if (syllable) sin(PI * (t % 0.35) / 0.22) else 0.0
        var s = 0.0
        for (h in 1..12) s += sin(h * phase) / h * (if (h in 3..6) 1.6 else 1.0)
        out[i] = (0.18 * env * s).toFloat()
    }
    return out
}

private fun noise(n: Int, level: Float, seed: Int) = Random(seed).let { r -> FloatArray(n) { (r.nextFloat() * 2 - 1) * level } }
private fun hum(n: Int, level: Float) = FloatArray(n) { (level * sin(2 * PI * 120 * it / RATE)).toFloat() }
private fun tone(n: Int, hz: Double, level: Float) = FloatArray(n) { (level * sin(2 * PI * hz * it / RATE)).toFloat() }

private fun plus(vararg xs: FloatArray) = FloatArray(xs[0].size) { i -> xs.sumOf { it[i].toDouble() }.toFloat() }
private fun energy(x: FloatArray, from: Int = 0, to: Int = x.size) = (from until to).sumOf { x[it].toDouble() * x[it] }
private fun db(x: Double) = 10 * log10(x.coerceAtLeast(1e-20))

/** The reducer over [x] in buffers of [chunk], then drained: what the processor does. */
private fun run(x: FloatArray, strength: Float = 1f, chunk: Int = 1_000): FloatArray {
    val r = NoiseReducer(RATE, 1)
    r.strength = strength
    val out = ArrayList<Float>(x.size)
    var i = 0
    while (i < x.size) {
        val end = minOf(x.size, i + chunk)
        r.process(x.copyOfRange(i, end)).forEach { out += it }
        i = end
    }
    r.drain().forEach { out += it }
    return out.toFloatArray()
}

/** The signal's share of [y] by projection onto the clean [s]: how much of [s] survives, and what is left over. */
private fun snr(y: FloatArray, s: FloatArray, from: Int): Double {
    var ss = 0.0; var ys = 0.0
    for (i in from until y.size) { ss += s[i].toDouble() * s[i]; ys += y[i].toDouble() * s[i] }
    val g = ys / ss
    var resid = 0.0
    for (i in from until y.size) { val e = y[i] - g * s[i]; resid += e * e }
    return db(g * g * ss / resid)
}

fun main() {
    val seconds = 8.0
    val clean = speech(seconds)
    val n = clean.size
    val noisy = plus(clean, noise(n, 0.05f, 1), hum(n, 0.04f))
    val out = run(noisy)
    // The first two seconds are where the floor is learnt: measured after.
    val from = 2 * RATE

    check(out.size == noisy.size, "${noisy.size} samples in, ${out.size} out")
    check(out.all { abs(it) <= 1f }, "a sample went past full scale")

    val before = snr(noisy, clean, from)
    val after = snr(out, clean, from)
    check(after - before >= 8, "the SNR rose ${"%.1f".format(after - before)} dB (from ${"%.1f".format(before)}), not 8")

    // The voice itself kept: the clean signal's energy in the output within 2 dB.
    var ss = 0.0; var ys = 0.0
    for (i in from until n) { ss += clean[i].toDouble() * clean[i]; ys += out[i].toDouble() * clean[i] }
    val kept = db((ys / ss) * (ys / ss))
    check(abs(kept) <= 2.0, "the voice came out ${"%.1f".format(kept)} dB")

    // Between words, where there is only noise, it is much lower.
    val gapBefore = energy(noisy, from, n) - energy(clean, from, n)
    val residual = (from until n).sumOf { val e = out[it] - clean[it]; e.toDouble() * e }
    check(db(residual) < db(gapBefore) - 6, "the noise came down only ${"%.1f".format(db(gapBefore) - db(residual))} dB")

    // Silence stays silence; a clean steady tone is left alone; nothing is delayed.
    val quiet = run(FloatArray(RATE))
    check(quiet.all { abs(it) < 1e-4f }, "silence came out with noise in it")
    val t = tone(4 * RATE, 1_000.0, 0.3f)
    val tOut = run(t)
    val tLevel = db(energy(tOut, RATE, 4 * RATE) / energy(t, RATE, 4 * RATE))
    check(abs(tLevel) <= 1.0, "a clean 1 kHz tone came out ${"%.2f".format(tLevel)} dB")
    var aligned = 0.0; var shifted = 0.0
    for (i in RATE until 4 * RATE - 12) { aligned += tOut[i] * t[i]; shifted += tOut[i] * t[i + 12] }
    val corr = aligned / sqrt(energy(tOut, RATE, 4 * RATE - 12) * energy(t, RATE, 4 * RATE - 12))
    check(aligned > abs(shifted) && corr > 0.9, "the output is not in step with the input (correlation $corr)")

    // Strength 0 is the input, sample for sample.
    val off = run(noisy, strength = 0f)
    check(off.indices.all { abs(off[it] - noisy[it]) < 1e-4f }, "strength 0 changed the sound")
    // Half strength takes out less than full.
    val half = run(noisy, strength = 0.5f)
    check(snr(half, clean, from) in before..after, "half strength (${"%.1f".format(snr(half, clean, from))} dB) is not between off and full")

    if (problems.isEmpty()) println("NoiseReduceChecks: all checks passed (SNR ${"%.1f".format(before)} -> ${"%.1f".format(after)} dB, voice ${"%.1f".format(kept)} dB, tone ${"%.2f".format(tLevel)} dB)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
