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

    // And the hiss *before* the word, which this suite generated for two
    // seconds and never looked at. That is where the fault lived: the floor
    // had no time constant downward and the envelope started at nothing, so
    // the floor was dragged to MIN_FLOOR on the first sample, the gate read
    // wide open on room tone, and Enhance put its PRESENCE lift on the hiss
    // and made it 1.4x *louder* for the two or three seconds the floor took
    // to creep back. Media3 flushes the processor on every seek and at the
    // start of every clip, so a montage of short takes never got anything
    // else.
    val openingIn = rms(input.subList(0, rate * 2))
    val openingOut = rms(output.subList(0, rate * 2))
    check(
        openingOut < openingIn,
        "the hiss before the first word came out louder than it went in: in $openingIn out $openingOut"
    )
    check(
        openingOut < openingIn * 0.8,
        "the hiss before the first word was not turned down: in $openingIn out $openingOut"
    )
    // The very first moments, before any envelope has had time to move: a
    // stream that opens on room tone must not be lifted at all.
    val firstIn = rms(input.subList(0, rate / 4))
    val firstOut = rms(output.subList(0, rate / 4))
    check(
        firstOut < firstIn,
        "the first quarter second of room tone was lifted: in $firstIn out $firstOut"
    )
    // The two relations the start leans on. INITIAL_FLOOR has to be above
    // MIN_FLOOR or priming the envelope with it says nothing, and the floor
    // has to settle more slowly than the gate closes or a gap between two
    // words is read as the room having gone quiet.
    check(VoiceCleaner.INITIAL_FLOOR > VoiceCleaner.MIN_FLOOR, "INITIAL_FLOOR is not above MIN_FLOOR")
    check(
        VoiceCleaner.FLOOR_FALL_MS > VoiceCleaner.GAIN_CLOSE_MS,
        "the floor settles faster than the gate closes (${VoiceCleaner.FLOOR_FALL_MS} against ${VoiceCleaner.GAIN_CLOSE_MS})"
    )

    // A stream that opens *mid-word* still gets its word. The gate starts
    // open, and the envelope climbs past the primed floor inside the attack,
    // so the lift is on the voice within a few milliseconds.
    run {
        val midWord = VoiceCleaner(rate, 1)
        val words = List(rate / 2) { (0.3 * sin(2 * PI * 220 * it / rate)).toFloat() + (noise.nextFloat() - 0.5f) * 0.02f }
        val out = words.map { midWord.process(it, 0) }
        val firstTenth = rms(out.subList(0, rate / 100))
        check(
            firstTenth > rms(words) * 0.6,
            "a clip that starts mid-word was gated at its start: $firstTenth against ${rms(words)}"
        )
    }

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

    println("voice: opening hiss %.4f -> %.4f (%.3fx), after the word %.4f -> %.4f (%.3fx), the word %.3fx".format(openingIn, openingOut, openingOut / openingIn, hissIn, hissOut, hissOut / hissIn, voiceOut / voiceIn))
    if (problems.isEmpty()) {
        println("VoiceCleanerChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
