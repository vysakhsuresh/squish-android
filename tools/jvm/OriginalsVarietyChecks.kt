import com.squish.app.media.audio.Fft
import com.squish.app.media.audio.MusicSynth
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Do the Squish originals sound like different pieces of music?
 *
 * Reported on 24 October: "all the tracks start with the same sound - one
 * common instrument throughout". They did: every track opened on the same two
 * bars (the one hi-hat, the one saw bass, the one pad), every track used the
 * same drum hits and the same bass, and every lead walked the same arpeggio
 * order. A tempo and a key are not an arrangement.
 *
 * What is measured: the spectral envelope (the long-term average level in
 * thirty log-spaced bands, 40 Hz to 12 kHz, in dB) of each track's opening
 * four seconds and of the whole track - the closest cheap thing to "which
 * instruments are playing". Two envelopes are compared by correlation after
 * their own mean is taken out, so loudness does not count, only shape.
 *
 * And the arrangement itself, read off the recipes: no two tracks may share
 * their whole palette (drums, bass, pad, lead, melody, intro), and every
 * choice of each must be used by some track.
 */
const val BANDS = 30

fun envelope(file: File, fromSec: Double, toSec: Double): DoubleArray {
    val b = file.readBytes()
    val rate = MusicSynth.SAMPLE_RATE
    val frames = (b.size - 44) / 4
    val from = (fromSec * rate).toInt().coerceIn(0, frames)
    val to = (toSec * rate).toInt().coerceIn(from, frames)
    val n = 2048
    val win = Fft.hann(n)
    val re = FloatArray(n)
    val im = FloatArray(n)
    val mag = FloatArray(n / 2)
    val frame = FloatArray(n)
    val acc = DoubleArray(BANDS)
    val edges = DoubleArray(BANDS + 1) { 40.0 * Math.pow(12000.0 / 40.0, it.toDouble() / BANDS) }
    var count = 0
    var at = from
    while (at + n <= to) {
        for (k in 0 until n) {
            val p = 44 + (at + k) * 4
            val l = ((b[p + 1].toInt() shl 8) or (b[p].toInt() and 0xff)).toShort()
            val r = ((b[p + 3].toInt() shl 8) or (b[p + 2].toInt() and 0xff)).toShort()
            frame[k] = (l + r) / 65536f * win[k]
        }
        Fft.magnitudes(frame, re, im, mag)
        for (k in 1 until n / 2) {
            val hz = k.toDouble() * rate / n
            var band = 0
            while (band < BANDS && hz >= edges[band + 1]) band++
            if (band in 0 until BANDS && hz >= edges[0]) acc[band] += (mag[k] * mag[k]).toDouble()
        }
        count++
        at += n
    }
    return DoubleArray(BANDS) { 10 * ln(acc[it] / count.coerceAtLeast(1) + 1e-9) / ln(10.0) }
}

fun shapeCorrelation(rawA: DoubleArray, rawB: DoubleArray): Double {
    val a = rawA
    val b = rawB
    val ma = a.average()
    val mb = b.average()
    var ab = 0.0; var aa = 0.0; var bb = 0.0
    for (i in a.indices) {
        val x = a[i] - ma; val y = b[i] - mb
        ab += x * y; aa += x * x; bb += y * y
    }
    return if (aa > 0 && bb > 0) ab / sqrt(aa * bb) else 1.0
}

fun main(args: Array<String>) {
    val problems = mutableListOf<String>()
    val dir = File(System.getProperty("java.io.tmpdir"), "squish-variety").apply { mkdirs() }
    val intros = LinkedHashMap<String, DoubleArray>()
    val wholes = LinkedHashMap<String, DoubleArray>()
    for (s in MusicSynth.styles) {
        val f = File(dir, "${s.id}.wav")
        MusicSynth.render(s, f)
        intros[s.id] = envelope(f, 0.0, 4.0)
        wholes[s.id] = envelope(f, 0.0, s.seconds - 2.0)
        f.delete()
    }

    fun report(what: String, m: Map<String, DoubleArray>, limit: Double, medianLimit: Double) {
        val ids = m.keys.toList()
        val pairs = ArrayList<Triple<String, String, Double>>()
        for (i in ids.indices) for (j in i + 1 until ids.size) pairs += Triple(ids[i], ids[j], shapeCorrelation(m[ids[i]]!!, m[ids[j]]!!))
        pairs.sortByDescending { it.third }
        val median = pairs[pairs.size / 2].third
        val near = pairs.count { it.third > limit }
        println("%-8s median %.3f, most alike %.3f (%s / %s), %d pairs above %.2f".format(
            what, median, pairs[0].third, pairs[0].first, pairs[0].second, near, limit))
        if ("-v" in args) pairs.take(8).forEach { println("    %.3f  %s / %s".format(it.third, it.first, it.second)) }
        if (median > medianLimit) problems += "$what: half of all pairs of originals are more alike than $medianLimit (median ${"%.3f".format(median)}) - they open on the same instruments"
        pairs.filter { it.third > limit }.take(10).forEach {
            problems += "$what: ${it.first} and ${it.second} have the same sound (shape correlation ${"%.3f".format(it.third)})"
        }
    }
    report("opening", intros, limit = 0.999, medianLimit = 0.85)
    // Whole mixes share a broad shape - drums, a bass, a tune - as any two songs
    // do, so the bar here is no two the same and well under the 0.981 the one
    // band gave, not a number only noise would meet.
    report("whole", wholes, limit = 0.999, medianLimit = 0.95)

    // The arrangement, read off the recipes.
    val palettes = MusicSynth.styles.groupBy { MusicSynth.paletteOf(it) }
    palettes.filter { it.value.size > 1 }.forEach { (p, ss) ->
        problems += "${ss.joinToString { it.id }} share one palette: $p"
    }
    MusicSynth.Kit.entries.forEach { k -> if (MusicSynth.styles.none { it.kit == k }) problems += "no track uses the ${k.name} kit" }
    MusicSynth.BassKind.entries.forEach { k -> if (MusicSynth.styles.none { it.bassKind == k }) problems += "no track uses the ${k.name} bass" }
    MusicSynth.PadKind.entries.forEach { k -> if (MusicSynth.styles.none { it.padKind == k }) problems += "no track uses the ${k.name} pad" }
    MusicSynth.Intro.entries.forEach { k -> if (MusicSynth.styles.none { it.intro == k }) problems += "no track opens with ${k.name}" }

    if (problems.isEmpty()) println("OriginalsVarietyChecks: all checks passed (${MusicSynth.styles.size} tracks)")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
