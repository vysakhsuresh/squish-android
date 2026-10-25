import com.squish.app.media.audio.AudioFinish
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
 * EDGE_PLAN G4: audio finishing on tones whose answers are known. Each band
 * of the equaliser lands where it is set and leaves the rest near flat; every
 * preset differs; flat is exactly nothing. The compressor takes a steady tone
 * over its threshold down by its ratio and leaves one under it; the limiter
 * lets nothing past its ceiling and leaves a quiet tone alone. Loudness reads
 * BS.1770's own calibration: a 0 dBFS 997 Hz sine on one channel is -3.01.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun db(x: Double) = 20 * log10(x)

private fun tone(hz: Double, amp: Double, seconds: Double, rate: Int) = FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
private fun rms(x: FloatArray, from: Int = 0) = sqrt(x.drop(from).map { it.toDouble() * it }.average())

fun main() {
    val rate = 48_000
    // --- Each band where it is set. ---
    for (i in AudioFinish.BANDS.indices) {
        val gains = FloatArray(5).also { it[i] = 9f }
        val eq = AudioFinish.Equaliser(gains, rate, 1)
        val at = db(eq.response(AudioFinish.BANDS[i].toDouble(), rate.toDouble()))
        // A shelf is at half its gain at its corner and the whole of it past it.
        val want = if (i == 0 || i == AudioFinish.BANDS.size - 1) 4.5 else 9.0
        check(abs(at - want) < 0.6, "band $i at ${AudioFinish.BANDS[i]} Hz reads ${"%.2f".format(at)} dB, not $want")
        val far = if (i < 2) 9_000.0 else 80.0
        check(abs(db(eq.response(far, rate.toDouble()))) < 1.5, "band $i reaches $far Hz")
    }
    // Run through samples, a band boosts a tone by what its response says.
    run {
        val eq = AudioFinish.Equaliser(floatArrayOf(0f, 0f, 6f, 0f, 0f), rate, 1)
        val x = tone(910.0, 0.1, 1.0, rate)
        val y = FloatArray(x.size) { eq.run(x[it], 0) }
        val gain = db(rms(y, rate / 4) / rms(x, rate / 4))
        check(abs(gain - 6.0) < 0.3, "a 6 dB band boosted its own tone by ${"%.2f".format(gain)} dB")
    }
    check(AudioFinish.Equaliser(FloatArray(5), rate, 2).isFlat, "flat is not nothing")
    val shapes = AudioFinish.Preset.entries.map { it.gains.toList() }
    check(shapes.toSet().size == shapes.size, "two presets are the same")
    check(AudioFinish.Preset.entries.all { p -> p.gains.all { abs(it) <= AudioFinish.MAX_DB } }, "a preset goes past the bands' reach")

    // --- The compressor: 4:1 over -20 dBFS. A -8 dBFS tone is 12 over, so comes out 3 over: -17. ---
    run {
        val c = AudioFinish.Compressor(-20f, 4f, 0f, 5f, 100f, rate)
        val x = tone(440.0, 10.0.pow(-8.0 / 20) * sqrt(2.0), 1.0, rate)
        val y = FloatArray(x.size) { x[it] * c.gain(x[it]) }
        // Peak-following: measured on the peaks the detector saw.
        val outPeak = y.drop(rate / 2).maxOf { abs(it) }
        // The detector follows peaks: the tone peaks at -5 dBFS, 15 over, and comes out 15/4 over.
        val wantPeak = 10.0.pow((-20 + 15.0 / 4) / 20)
        check(abs(db(outPeak.toDouble()) - db(wantPeak)) < 1.0, "4:1 over -20 took a tone to ${"%.1f".format(db(outPeak.toDouble()))} dBFS, not ${"%.1f".format(db(wantPeak))}")
        val quiet = AudioFinish.Compressor(-20f, 4f, 0f, 5f, 100f, rate)
        val q = tone(440.0, 0.02, 0.5, rate)
        check(q.drop(rate / 4).all { abs(quiet.gain(it) - 1f) < 1e-3f }, "a tone under the threshold was turned down")
    }

    // --- The limiter: nothing past the ceiling, and a quiet tone untouched (only delayed). ---
    run {
        val l = AudioFinish.Limiter(AudioFinish.CEILING, rate, 2)
        var worst = 0f
        for (i in 0 until rate) {
            val v = (1.6 * sin(2 * PI * 220 * i / rate)).toFloat() * (if (i % 4800 < 30) 2f else 1f)
            val f = floatArrayOf(v, -v)
            l.run(f)
            worst = maxOf(worst, abs(f[0]), abs(f[1]))
        }
        check(worst <= AudioFinish.CEILING + 1e-6f, "the limiter let ${worst} past a ceiling of ${AudioFinish.CEILING}")
        val calm = AudioFinish.Limiter(AudioFinish.CEILING, rate, 1)
        val x = tone(440.0, 0.3, 0.2, rate)
        val out = x.map { v -> floatArrayOf(v).also { calm.run(it) }[0] }
        val lag = calm.latencyFrames
        check((lag until x.size).all { abs(out[it] - x[it - lag]) < 1e-5f }, "a quiet tone through the limiter changed")
    }

    // --- Loudness: BS.1770's calibration, and the gain to -14. ---
    run {
        val full = tone(997.0, 1.0, 3.0, rate)
        val l = AudioFinish.lufs(full, rate, 1)
        check(abs(l - -3.01f) < 0.1f, "a 0 dBFS 997 Hz sine reads $l LUFS, not -3.01")
        val quieter = tone(997.0, 0.1, 3.0, rate)
        check(abs(AudioFinish.lufs(quieter, rate, 1) - -23.01f) < 0.1f, "20 dB down reads ${AudioFinish.lufs(quieter, rate, 1)}")
        // A pause does not count: the same tone with as long again of silence after it reads
        // within a few tenths (the blocks across the join count, as BS.1770 has them), not 3 dB down.
        val paused = quieter + FloatArray(rate * 3)
        check(abs(AudioFinish.lufs(paused, rate, 1) - AudioFinish.lufs(quieter, rate, 1)) < 0.4f, "silence pulled the loudness down")
        check(AudioFinish.lufs(FloatArray(rate), rate, 1).isNaN(), "silence has a loudness")
        val g = AudioFinish.gainTo(-23.01f)
        check(abs(db(g.toDouble()) - 9.01) < 0.05, "the gain from -23 to -14 is ${db(g.toDouble())} dB")
        // The level a clip may take stops at 4x, so a quiet one does not reach -14 and the
        // sheet must not say it did: -28.1 at 4x lands at -16.06 (seen on the POCO, 25 Oct).
        val capped = AudioFinish.levelFor(-28.1f, 4f)
        check(abs(capped.level - 4f) < 1e-4f && abs(capped.reachesLufs - (-16.06f)) < 0.05f && !capped.onTarget,
            "a quiet clip read as reaching -14: $capped")
        val reached = AudioFinish.levelFor(-20f, 4f)
        check(reached.onTarget && abs(reached.reachesLufs - (-14f)) < 0.01f, "a clip within reach read as short: $reached")
    }

    if (problems.isEmpty()) println("AudioFinishChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}

private fun Double.pow(e: Double) = Math.pow(this, e)
