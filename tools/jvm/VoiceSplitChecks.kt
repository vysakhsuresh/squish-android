import com.squish.app.media.audio.VoiceSplit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
 * EDGE_PLAN G3: Voice only and Remove voice, everything about them but DTLN's
 * weights - the resampling to its 16 kHz and back, its block loop with a
 * stand-in network, and the mix at a level.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun tone(hz: Double, rate: Int, seconds: Double, amp: Double = 0.5) =
    FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
private fun db(x: Double) = 20 * log10(x.coerceAtLeast(1e-12))

/** A network that changes nothing: the mask all ones, the second stage the block scaled to sum back to one. */
private object Identity : VoiceSplit.Network {
    override fun mask(magnitude: FloatArray) = FloatArray(magnitude.size) { 1f }
    override fun refine(block: FloatArray) = FloatArray(block.size) { block[it] * VoiceSplit.SHIFT / VoiceSplit.BLOCK }
    override fun reset() {}
}

fun main() {
    // --- Resampling. ---------------------------------------------------------
    run {
        val x = tone(1_000.0, 48_000, 1.0)
        val down = VoiceSplit.resample(x, 48_000, 16_000)
        check(abs(down.size - 16_000) <= 1, "48k to 16k gave ${down.size} samples for one second")
        val lossDb = db(rms(down, 800, 15_200)) - db(rms(x, 2_400, 45_600))
        check(abs(lossDb) < 0.1, "a 1 kHz tone lost $lossDb dB going to 16 kHz")
        // 10 kHz is above the new Nyquist: it must not fold down to 6 kHz.
        val high = VoiceSplit.resample(tone(10_000.0, 48_000, 1.0), 48_000, 16_000)
        val alias = db(rms(high, 800, 15_200)) - db(0.5 / sqrt(2.0))
        check(alias < -40, "10 kHz folded into 16 kHz audio at $alias dB")
        val back = VoiceSplit.resample(down, 16_000, 48_000)
        check(abs(back.size - 48_000) <= 3, "16k back to 48k gave ${back.size}")
        // In step: the round trip lines up with the original, sample for sample.
        var err = 0.0
        for (i in 2_400 until 45_600) err = maxOf(err, abs((back[i] - x[i]).toDouble()))
        check(err < 0.01, "48k -> 16k -> 48k off the original by $err (a delay or a level)")
        check(VoiceSplit.resample(x, 48_000, 48_000).contentEquals(x), "a same-rate resample changed the sound")
    }
    // 44.1 kHz, the CD rate: 160 phases, where 48 kHz has one. Level and sync both ways.
    run {
        val x = tone(1_000.0, 44_100, 1.0)
        val down = VoiceSplit.resample(x, 44_100, 16_000)
        check(abs(down.size - 16_000) <= 1, "44.1k to 16k gave ${down.size} samples for one second")
        val lossDb = db(rms(down, 800, 15_200)) - db(rms(x, 2_205, 41_895))
        check(abs(lossDb) < 0.1, "a 1 kHz tone at 44.1 kHz lost $lossDb dB going to 16 kHz")
        val back = VoiceSplit.resample(down, 16_000, 44_100)
        var err = 0.0
        for (i in 2_205 until 41_895) err = maxOf(err, abs((back[i] - x[i]).toDouble()))
        check(err < 0.01, "44.1k -> 16k -> 44.1k off the original by $err")
    }

    // --- The block loop: with a network that changes nothing, the sound comes back as it went in. ---
    run {
        val rate = VoiceSplit.MODEL_RATE
        val x = FloatArray(rate) { i -> if (i == 4_000) 1f else 0f } + tone(440.0, rate, 1.0)
        val y = VoiceSplit.voiceOf(x, Identity)
        check(y.size == x.size, "${x.size} samples in, ${y.size} out")
        val peakAt = (0 until 8_000).maxBy { abs(y[it]) }
        check(peakAt == 4_000, "a click at sample 4000 came out at $peakAt")
        var err = 0.0
        for (i in 0 until x.size - VoiceSplit.BLOCK) err = maxOf(err, abs((y[i] - x[i]).toDouble()))
        check(err < 1e-4, "an identity network changed the sound by $err")
    }
    // The progress is heard, and ends near the end.
    run {
        var last = 0f
        VoiceSplit.voiceOf(FloatArray(VoiceSplit.MODEL_RATE * 3), Identity) { last = it }
        check(last > 0.8f && last <= 1f, "progress ended at $last")
    }

    // --- The mix. ------------------------------------------------------------
    run {
        val orig = floatArrayOf(0.5f, -0.2f, 0.3f)
        val voice = floatArrayOf(0.3f, -0.1f, 0.0f)
        val vo = VoiceSplit.mix(orig, voice, VoiceSplit.Mode.VoiceOnly, 1f)
        val rv = VoiceSplit.mix(orig, voice, VoiceSplit.Mode.RemoveVoice, 1f)
        check(vo.indices.all { abs(vo[it] - voice[it]) < 1e-6 }, "Voice only at full is not the voice: ${vo.toList()}")
        check(rv.indices.all { abs(rv[it] - (orig[it] - voice[it])) < 1e-6 }, "Remove voice at full is not the rest: ${rv.toList()}")
        for (m in VoiceSplit.Mode.entries) {
            val none = VoiceSplit.mix(orig, voice, m, 0f)
            check(none.indices.all { abs(none[it] - orig[it]) < 1e-6 }, "${m.label} at level 0 changed the sound")
        }
        // Voice and rest add back to the sound: the two modes at full are complements.
        check(vo.indices.all { abs(vo[it] + rv[it] - orig[it]) < 1e-6 }, "the voice and the rest do not add back to the sound")
    }

    if (problems.isEmpty()) println("VoiceSplitChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
