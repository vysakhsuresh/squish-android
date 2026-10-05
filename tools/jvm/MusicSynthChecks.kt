import com.squish.app.media.audio.MusicSynth
import java.io.File
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** Peak and RMS of a 16-bit stereo WAV written by MusicSynth, and its length in seconds. */
fun measure(file: File): Triple<Double, Double, Double> {
    val b = file.readBytes()
    val data = b.size - 44
    var peak = 0
    var sum = 0.0
    var i = 44
    while (i + 1 < b.size) {
        val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()
        peak = maxOf(peak, kotlin.math.abs(v))
        sum += v.toDouble() * v
        i += 2
    }
    val samples = data / 2
    return Triple(peak / 32768.0, kotlin.math.sqrt(sum / samples.coerceAtLeast(1)) / 32768.0, data / 4.0 / MusicSynth.SAMPLE_RATE)
}

/** The loudest sample in the last [samples] frames of a WAV, 0..1. */
fun tailPeak(file: File, samples: Int): Double {
    val b = file.readBytes()
    val from = maxOf(44, b.size - samples * 4)
    var peak = 0
    var i = from
    while (i + 1 < b.size) {
        val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()
        peak = maxOf(peak, kotlin.math.abs(v))
        i += 2
    }
    return peak / 32768.0
}

/** The very last sample of the left channel, 0..1 - the size of the step into silence. */
fun lastSample(file: File): Double {
    val b = file.readBytes()
    if (b.size < 48) return 0.0
    val i = b.size - 4
    return kotlin.math.abs(((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()) / 32768.0
}

fun main() {
    val dir = File(System.getProperty("java.io.tmpdir"), "squish-synth").apply { mkdirs() }
    check(MusicSynth.styles.map { it.id }.toSet().size == MusicSynth.styles.size, "two tracks share an id")
    check(MusicSynth.effects.map { it.id }.toSet().size == MusicSynth.effects.size, "two effects share an id")
    check(MusicSynth.styles.size >= 20, "only ${MusicSynth.styles.size} originals")
    check(MusicSynth.effects.size >= 15, "only ${MusicSynth.effects.size} effects")
    for (s in MusicSynth.styles) {
        listOf(s.kickPattern, s.snarePattern, s.hatPattern).forEach { p ->
            check(p.length == 16 && p.all { it == 'x' || it == '-' }, "${s.id}: a pattern is not 16 steps: $p")
        }
        check(s.chords.isNotEmpty() && s.seconds in 20..90, "${s.id}: ${s.seconds}s long")
        val f = File(dir, "${s.id}.wav")
        MusicSynth.render(s, f)
        val (peak, rms, secs) = measure(f)
        check(kotlin.math.abs(secs - s.seconds) < 0.05, "${s.id}: ${secs}s written, ${s.seconds}s promised")
        check(rms > 0.03, "${s.id} is nearly silent (rms $rms)")
        check(peak < 0.99, "${s.id} clips (peak $peak)")
        f.delete()
    }
    for (e in MusicSynth.effects) {
        val f = File(dir, "${e.id}.wav")
        MusicSynth.renderEffect(e, f)
        val (peak, rms, secs) = measure(f)
        check(kotlin.math.abs(secs - e.seconds) < 0.01, "${e.id}: ${secs}s written")
        check(rms > 0.01 && peak > 0.3, "${e.id} is nearly silent (rms $rms, peak $peak)")
        check(peak < 0.99, "${e.id} clips (peak $peak)")
        // The step into silence, two ways. A sound still at level on its final
        // sample jumps to nothing, and a jump is a click; "Sound on every cut"
        // butts these against a join, so that tick lands on every cut in the
        // edit. MusicSynth.release fades the last three milliseconds to zero -
        // the last sample says the fade reaches nothing, the last half
        // millisecond says it is a taper and not a one-sample notch.
        //
        // The start is not checked. A transient begins at level because that is
        // what a transient is - silence to a hit is the sound.
        val half = MusicSynth.SAMPLE_RATE / 2000
        check(lastSample(f) < 0.02, "${e.id} ends on a sample at ${lastSample(f)} - it will click on its cut")
        check(tailPeak(f, half) < 0.25, "${e.id} has no taper (last 0.5 ms peaks at ${tailPeak(f, half)})")
        f.delete()
    }
    if (problems.isEmpty()) println("MusicSynthChecks: all checks passed (${MusicSynth.styles.size} tracks, ${MusicSynth.effects.size} effects)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
