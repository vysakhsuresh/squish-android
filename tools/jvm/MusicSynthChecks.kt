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
        f.delete()
    }
    if (problems.isEmpty()) println("MusicSynthChecks: all checks passed (${MusicSynth.styles.size} tracks, ${MusicSynth.effects.size} effects)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
