import com.squish.app.media.audio.BeatDetector
import com.squish.app.media.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

const val RATE = 8000

/**
 * A synthetic track: kick on every beat, snare on 2 and 4, hats on eighths, a
 * sustained bass note underneath and noise over the top.
 *
 * The bass is the point of the fixture. A plain click track can be found by a
 * level detector, so passing on one proves nothing. A sustained low note means the
 * overall loudness barely moves when the snare lands, and only something looking at
 * the spectrum will see it.
 */
fun track(bpm: Double, seconds: Double, seed: Int = 7, withBass: Boolean = true, plain: Boolean = false): FloatArray {
    val rng = Random(seed)
    val n = (RATE * seconds).toInt()
    val out = FloatArray(n)
    val beat = 60.0 / bpm

    fun hit(atSec: Double, freq: Double, decay: Double, gain: Double, noisy: Double = 0.0) {
        val start = (atSec * RATE).toInt()
        var i = 0
        while (i < RATE * 0.25 && start + i < n) {
            val t = i.toDouble() / RATE
            val env = exp(-t / decay)
            val tone = sin(2 * PI * freq * t)
            val noise = (rng.nextDouble() * 2 - 1) * noisy
            out[start + i] += ((tone * (1 - noisy) + noise) * env * gain).toFloat()
            i++
        }
    }

    var b = 0
    var at = 0.0
    while (at < seconds) {
        if (plain) {
            // The same hit on every beat and nothing else. The only periodicity
            // in this is the beat, so there is no octave left to argue about.
            hit(at, 55.0, 0.09, 0.9)
        } else {
            when (b % 4) {
                0 -> hit(at, 55.0, 0.09, 0.9)                   // kick, downbeat
                2 -> hit(at, 55.0, 0.09, 0.7)                   // kick
                else -> hit(at, 190.0, 0.07, 0.6, noisy = 0.7)  // snare
            }
            hit(at + beat / 2, 6000.0, 0.02, 0.22, noisy = 0.9) // off-beat hat
        }
        at += beat
        b++
    }

    if (withBass) {
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            out[i] += (0.45 * sin(2 * PI * 82.0 * t)).toFloat()
        }
    }
    for (i in 0 until n) out[i] += ((rng.nextDouble() * 2 - 1) * 0.02).toFloat()
    return out
}

fun main() {
    // --- The FFT itself, before trusting anything built on it. ----------------
    run {
        val n = 64
        val real = FloatArray(n) { sin(2 * PI * 8 * it / n).toFloat() }
        val imaginary = FloatArray(n)
        Fft.transform(real, imaginary)
        // A pure sinusoid at bin 8 must put all its energy in bin 8 (and its mirror).
        var peak = 0
        for (k in 1 until n / 2) if (hypot(real[k], imaginary[k]) > hypot(real[peak], imaginary[peak])) peak = k
        check(peak == 8, "FFT put a bin-8 sinusoid at bin $peak")

        // Parseval: energy is conserved. This catches a scaling or butterfly slip
        // that a peak test would sail past.
        val signal = FloatArray(n) { sin(2 * PI * 5 * it / n).toFloat() + 0.3f * sin(2 * PI * 17 * it / n).toFloat() }
        var timeEnergy = 0.0
        for (v in signal) timeEnergy += v * v
        val r2 = signal.copyOf(); val i2 = FloatArray(n)
        Fft.transform(r2, i2)
        var freqEnergy = 0.0
        for (k in 0 until n) freqEnergy += r2[k] * r2[k] + i2[k] * i2[k]
        freqEnergy /= n
        check(abs(timeEnergy - freqEnergy) / timeEnergy < 1e-4, "Parseval: $timeEnergy vs $freqEnergy")

        // A constant signal is all DC and nothing else.
        val flat = FloatArray(n) { 1f }
        val fr = flat.copyOf(); val fi = FloatArray(n)
        Fft.transform(fr, fi)
        check(abs(fr[0] - n) < 1e-3, "DC bin of a constant is ${fr[0]}, expected $n")
        for (k in 1 until n) check(hypot(fr[k], fi[k]) < 1e-3, "a constant leaked into bin $k")
    }

    // --- Tempo, across the range people actually use. -------------------------
    //
    // Two different assertions, because the question has two different answers.
    //
    // On a pattern with off-beat hi-hats the tempo is genuinely ambiguous: two
    // people tapping along to a slow track with busy hats will disagree by an
    // octave, and so will two beat trackers. What is *not* negotiable is that the
    // beats land on the music - so the check there is metrical consistency, which
    // is what anyone cutting to the track actually needs.
    //
    // On a pattern with nothing between the beats there is no ambiguity left, and
    // the exact tempo is demanded.

    val tempos = listOf(70.0, 90.0, 100.0, 120.0, 128.0, 140.0, 150.0, 174.0)

    println("--- with off-beat hats (tempo may legitimately come back at an octave)")
    println("%-8s %-10s %-8s %-8s %s".format("true", "found", "ratio", "conf", "beats"))
    for (bpm in tempos) {
        val map = BeatDetector.detect(track(bpm, 14.0), RATE)
        val ratio = map.bpm / bpm
        println("%-8.1f %-10.2f %-8.2f %-8.2f %d".format(bpm, map.bpm, ratio, map.confidence, map.beatsMs.size))

        check(map.confidence > 0.25f, "tempo $bpm scored only ${map.confidence} confidence")

        // Whatever level it counted at, it must be a simple metrical relation to
        // the truth - not merely "some number".
        val nearest = listOf(0.25, 1.0 / 3, 0.5, 1.0, 2.0, 3.0, 4.0).minByOrNull { abs(it - ratio) }!!
        check(
            abs(ratio - nearest) < 0.04,
            "tempo $bpm came back ${map.bpm}, a ratio of $ratio - not a metrical level at all"
        )

        // And the beats have to land on the music. Counting faster than the truth
        // means every true beat has a beat on it; counting slower means every
        // detected beat sits on a true one. Either way, a cut lands on the beat.
        val period = 60_000.0 / bpm
        val truth = generateSequence(0.0) { it + period }.takeWhile { it < 13_500.0 }.toList()
        val slop = maxOf(45.0, period * 0.12)

        if (nearest >= 1.0) {
            val covered = truth.count { e -> map.beatsMs.any { abs(it - e) <= slop } }
            check(
                covered.toDouble() / truth.size > 0.9,
                "tempo $bpm: only ${covered * 100 / truth.size}% of the true beats had a beat on them"
            )
        } else {
            val onGrid = map.beatsMs.count { b -> truth.any { abs(b - it) <= slop } }
            check(
                onGrid.toDouble() / map.beatsMs.size > 0.9,
                "tempo $bpm: only ${onGrid * 100 / map.beatsMs.size}% of its beats were on the grid"
            )
        }

        // Even spacing. A tracker that loses the pulse and regains it passes the
        // coverage test above and fails this one.
        val gaps = map.beatsMs.zipWithNext { a, b -> (b - a).toDouble() }
        if (gaps.isNotEmpty()) {
            val expected = period / nearest
            val worst = gaps.maxOf { abs(it - expected) }
            check(
                worst < expected * 0.2,
                "tempo $bpm: a gap was ${worst.toInt()}ms off an expected ${expected.toInt()}ms"
            )
        }
    }

    println()
    println("--- four on the floor, nothing in between (the tempo is not ambiguous)")
    println("%-8s %-10s %-8s %s".format("true", "found", "error", "beats"))
    for (bpm in tempos) {
        val map = BeatDetector.detect(track(bpm, 14.0, plain = true), RATE)
        val err = abs(map.bpm - bpm)
        println("%-8.1f %-10.2f %-8.2f %d".format(bpm, map.bpm, err, map.beatsMs.size))
        check(err < maxOf(2.0, bpm * 0.02), "unambiguous $bpm came back ${map.bpm}")

        val period = 60_000.0 / bpm
        val truth = generateSequence(0.0) { it + period }.takeWhile { it < 13_500.0 }.toList()
        val slop = maxOf(45.0, period * 0.12)
        val covered = truth.count { e -> map.beatsMs.any { abs(it - e) <= slop } }
        check(
            covered.toDouble() / truth.size > 0.92,
            "unambiguous $bpm: only ${covered * 100 / truth.size}% of beats found within ${slop.toInt()}ms"
        )
    }

    // --- Halving and doubling must stay on the same grid. ---------------------
    run {
        val map = BeatDetector.detect(track(128.0, 16.0), RATE)
        val half = map.halved()
        check(abs(half.bpm - map.bpm / 2) < 0.01f, "halved reported ${half.bpm} from ${map.bpm}")
        check(half.beatsMs.all { it in map.beatsMs }, "halving invented a beat that was not there")
        check(half.beatsMs.size in (map.beatsMs.size / 2 - 1)..(map.beatsMs.size / 2 + 1),
            "halving left ${half.beatsMs.size} of ${map.beatsMs.size}")

        val twice = map.doubled()
        check(abs(twice.bpm - map.bpm * 2) < 0.01f, "doubled reported ${twice.bpm}")
        check(map.beatsMs.all { it in twice.beatsMs }, "doubling lost one of the original beats")
        val gaps = twice.beatsMs.zipWithNext { a, b -> b - a }
        val spread = (gaps.maxOrNull() ?: 0L) - (gaps.minOrNull() ?: 0L)
        // A frame is 16ms at this rate, which is as fine as a midpoint can land.
        check(spread <= 20L, "doubling produced uneven gaps, spread ${spread}ms")

        check(map.halved().doubled().beatsMs.size >= map.beatsMs.size - 2,
            "halving then doubling lost the grid")
    }

    // --- The bass note must not be what it locks onto. ------------------------
    run {
        val withBass = BeatDetector.detect(track(128.0, 14.0, withBass = true), RATE)
        val without = BeatDetector.detect(track(128.0, 14.0, withBass = false), RATE)
        check(
            abs(withBass.bpm - without.bpm) < 2f,
            "a sustained bass note moved the answer from ${without.bpm} to ${withBass.bpm}"
        )
    }

    // --- Nothing rhythmic in it: say so rather than invent a tempo. -----------
    run {
        val rng = Random(3)
        val noise = FloatArray(RATE * 10) { (rng.nextDouble() * 2 - 1).toFloat() * 0.3f }
        val map = BeatDetector.detect(noise, RATE)
        check(map.confidence < 0.35f, "white noise reported ${map.confidence} confidence at ${map.bpm} BPM")

        val silence = FloatArray(RATE * 10)
        val quiet = BeatDetector.detect(silence, RATE)
        check(quiet.isEmpty || quiet.confidence < 0.2f, "silence reported ${quiet.beatsMs.size} beats")
    }

    // --- Degenerate inputs must not throw. ------------------------------------
    run {
        check(BeatDetector.detect(FloatArray(0), RATE).isEmpty, "empty input produced beats")
        check(BeatDetector.detect(FloatArray(100), RATE).isEmpty, "a too-short input produced beats")
        check(BeatDetector.detect(FloatArray(RATE), 0).isEmpty, "a zero sample rate produced beats")
    }

    // --- Bars: every(4) must take every fourth beat, from the downbeat. -------
    run {
        val map = BeatDetector.detect(track(120.0, 16.0), RATE)
        val bars = map.every(4)
        check(bars.size in (map.beatsMs.size / 4 - 1)..(map.beatsMs.size / 4 + 1),
            "every(4) returned ${bars.size} of ${map.beatsMs.size} beats")
        val gaps = bars.zipWithNext { a, b -> b - a }
        gaps.forEach { check(abs(it - 2000L) < 300L, "a bar was ${it}ms, expected about 2000ms at 120 BPM") }
        check(map.every(1).size == map.beatsMs.size, "every(1) dropped beats")
    }

    println()
    if (problems.isEmpty()) println("PASS - the beat tracker finds the tempo and the beats")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
