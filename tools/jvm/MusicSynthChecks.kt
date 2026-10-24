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
    // ---- The library as it is browsed (docs/INSHOT_PARITY.md P7). ----------
    check(MusicSynth.effects.size >= 60, "only ${MusicSynth.effects.size} effects - the library promises about sixty")
    check(MusicSynth.styles.size >= 34, "only ${MusicSynth.styles.size} originals")
    MusicSynth.effects.forEach { e ->
        check(e.category in MusicSynth.effectCategories, "${e.id} is in \"${e.category}\", which is not a category the panel shows")
    }
    MusicSynth.effectCategories.forEach { c ->
        val n = MusicSynth.effects.count { it.category == c }
        check(n >= 4, "the category \"$c\" has $n effects - a tile with one or two in it is not a category")
    }
    MusicSynth.vibes.filter { it != "All" }.forEach { v ->
        val n = MusicSynth.styles.count { MusicSynth.vibeOf(it) == v }
        check(n >= 3, "the music category \"$v\" has $n tracks")
    }
    MusicSynth.styles.forEach { s ->
        check(MusicSynth.vibeOf(s) in MusicSynth.vibes, "${s.id} is filed under \"${MusicSynth.vibeOf(s)}\", which is not on the list")
    }
    // Same peak for every effect, so no one of them is much louder than the
    // others when five are laid in one edit; and none identical to another.
    val rendered = HashMap<String, ShortArray>()
    fun samples(file: File): ShortArray {
        val b = file.readBytes()
        return ShortArray((b.size - 44) / 2) { k -> ((b[44 + 2 * k + 1].toInt() shl 8) or (b[44 + 2 * k].toInt() and 0xff)).toShort() }
    }
    for (e in MusicSynth.effects) {
        val f = File(dir, "${e.id}.wav")
        MusicSynth.renderEffect(e, f)
        val (peak, rms, secs) = measure(f)
        val s = samples(f)
        rendered[e.id] = s
        // write() normalises to 0.85 before its tanh: tanh(0.85) * 32000 / 32768.
        check(kotlin.math.abs(peak - 0.6735) < 0.02, "${e.id} peaks at $peak, not at the level every effect is set to")
        // No DC offset: a sound sitting off zero thumps where it starts and stops.
        var sumL = 0.0
        var sumR = 0.0
        for (k in s.indices step 2) { sumL += s[k]; sumR += s[k + 1] }
        val frames = (s.size / 2).coerceAtLeast(1)
        check(kotlin.math.abs(sumL / frames / 32768) < 0.01 && kotlin.math.abs(sumR / frames / 32768) < 0.01,
            "${e.id} sits off zero (mean ${sumL / frames / 32768}, ${sumR / frames / 32768})")
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
        // The length in the subtitle is the length of the file. Each of these
        // reads "Impact · 1.6s", and the list is the only place anyone can see
        // how long a sound is before placing it.
        Regex("""([0-9.]+)\s*s\b""").find(e.hint)?.let { m ->
            val said = m.groupValues[1].toFloat()
            check(
                kotlin.math.abs(said - e.seconds) < 0.05f,
                "${e.id} says ${said}s on the list and is ${e.seconds}s long"
            )
        } ?: check(false, "${e.id}'s subtitle \"${e.hint}\" does not say how long it is")
        val half = MusicSynth.SAMPLE_RATE / 2000
        check(lastSample(f) < 0.02, "${e.id} ends on a sample at ${lastSample(f)} - it will click on its cut")
        check(tailPeak(f, half) < 0.25, "${e.id} has no taper (last 0.5 ms peaks at ${tailPeak(f, half)})")
        f.delete()
    }
    // No two alike: correlation of the left channels over the shorter one.
    val ids = rendered.keys.sorted()
    for (a in ids.indices) for (b in a + 1 until ids.size) {
        val x = rendered[ids[a]]!!
        val y = rendered[ids[b]]!!
        val n = minOf(x.size, y.size) / 2
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (k in 0 until n) {
            val p = x[2 * k].toDouble(); val q = y[2 * k].toDouble()
            xy += p * q; xx += p * p; yy += q * q
        }
        val corr = if (xx > 0 && yy > 0) xy / kotlin.math.sqrt(xx * yy) else 0.0
        check(corr < 0.95, "${ids[a]} and ${ids[b]} are the same sound (correlation $corr)")
    }
    if (problems.isEmpty()) println("MusicSynthChecks: all checks passed (${MusicSynth.styles.size} tracks, ${MusicSynth.effects.size} effects)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
