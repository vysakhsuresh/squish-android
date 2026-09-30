import com.squish.app.media.audio.VoiceCleaner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun rms(xs: List<Float>): Double = kotlin.math.sqrt(xs.sumOf { (it * it).toDouble() } / xs.size.coerceAtLeast(1))

fun main() {
    val rate = 48_000
    val noise = Random(7)

    // Two seconds of room hiss, then a second of "voice" (a 220 Hz tone) over the hiss, then hiss again.
    fun signal(i: Int): Float {
        val hiss = (noise.nextFloat() - 0.5f) * 0.02f
        val voiceOn = i in rate * 2 until rate * 3
        val voice = if (voiceOn) (0.3 * sin(2 * PI * 220 * i / rate)).toFloat() else 0f
        return hiss + voice
    }
    val cleaner = VoiceCleaner(rate, 1)
    val input = List(rate * 5) { signal(it) }
    val output = input.mapIndexed { _, s -> cleaner.process(s, 0) }

    // Measured a second after the word: the gate eases shut over its tail, as it should.
    val hissIn = rms(input.subList(rate * 4, rate * 5))
    val hissOut = rms(output.subList(rate * 4, rate * 5))
    check(hissOut < hissIn * 0.35, "the hiss after the voice was not turned down: in $hissIn out $hissOut")

    val voiceIn = rms(input.subList(rate * 2 + rate / 10, rate * 3))
    val voiceOut = rms(output.subList(rate * 2 + rate / 10, rate * 3))
    check(voiceOut > voiceIn * 0.9, "the voice itself was turned down: in $voiceIn out $voiceOut")

    // The start of a word is not swallowed: 20 ms in, the voice is already mostly through.
    val onset = rms(output.subList(rate * 2 + rate / 50, rate * 2 + rate / 25))
    check(onset > voiceIn * 0.6, "the start of the word was clipped: $onset against $voiceIn")

    // Rumble: a 30 Hz hum loses most of itself.
    val hum = VoiceCleaner(rate, 1)
    val humIn = List(rate) { (0.3 * sin(2 * PI * 30 * it / rate)).toFloat() }
    val humOut = humIn.map { hum.process(it, 0) }
    check(rms(humOut.subList(rate / 2, rate)) < rms(humIn) * 0.6, "a 30 Hz hum was not taken down")

    // Never past full scale, whatever goes in.
    val loud = VoiceCleaner(rate, 2)
    var peak = 0f
    for (i in 0 until rate) {
        val s = if (i % 2 == 0) 1f else -1f
        peak = maxOf(peak, abs(loud.process(s, i % 2)))
    }
    check(peak <= 1f, "the output went past full scale: $peak")

    if (problems.isEmpty()) {
        println("VoiceCleanerChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
