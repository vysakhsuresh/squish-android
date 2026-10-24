import com.squish.app.media.SyncAnalysis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * EDGE_PLAN B3: the sync reader finds what is known to be there - flashes and
 * beeps on every second, a fixed offset, and a drift that grows - before it is
 * trusted on a real export.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** A file's worth: [seconds] of flashes at 30 fps and beeps at 8 kHz, the picture [offsetMs] late, drifting [driftMsPerMin]. */
fun synth(seconds: Int, offsetMs: Long, driftMsPerMin: Double): Triple<LongArray, FloatArray, FloatArray> {
    val fps = 30
    val frames = seconds * fps
    val times = LongArray(frames) { it * 1000L / fps }
    val luma = FloatArray(frames) { 0.3f }
    for (s in 0 until seconds) {
        val drift = (s / 60.0 * driftMsPerMin).toLong()
        val at = s * 1000L + offsetMs + drift
        val i = (at * fps / 1000).toInt()
        if (i in 0 until frames) luma[i] = 0.95f
    }
    val rate = 8000
    val samples = FloatArray(seconds * rate)
    for (s in 0 until seconds) {
        val start = s * rate
        for (k in 0 until rate / 20) samples[start + k] = (0.8 * sin(2 * PI * 1000 * k / rate)).toFloat()
    }
    // A little room noise, which must not read as beeps.
    val rnd = java.util.Random(3)
    for (k in samples.indices) samples[k] += (rnd.nextGaussian() * 0.005).toFloat()
    return Triple(times, luma, samples)
}

fun main() {
    // In sync: every flash on its beep, to within a frame.
    run {
        val (t, l, a) = synth(60, 0, 0.0)
        val f = SyncAnalysis.flashes(t, l)
        val b = SyncAnalysis.beeps(a, 8000)
        check(f.size == 60, "${f.size} flashes found in 60 s, not 60")
        check(b.size == 60, "${b.size} beeps found in 60 s, not 60")
        val r = SyncAnalysis.report(SyncAnalysis.pair(f, b))
        check(r != null && r.pairs == 60, "not every flash paired: $r")
        check(r != null && abs(r.medianMs) <= 34 && abs(r.driftMs) <= 34, "in-sync footage read as off: $r")
    }
    // The picture 120 ms late, steadily.
    run {
        val (t, l, a) = synth(60, 120, 0.0)
        val r = SyncAnalysis.report(SyncAnalysis.pair(SyncAnalysis.flashes(t, l), SyncAnalysis.beeps(a, 8000)))
        check(r != null && abs(r.medianMs - 120) <= 34, "a 120 ms offset read as ${r?.medianMs}")
        check(r != null && abs(r.driftMs) <= 34, "a steady offset read as drift ${r?.driftMs}")
    }
    // Ten minutes drifting 20 ms a minute: about 180 ms from first tenth to last.
    run {
        val (t, l, a) = synth(600, 0, 20.0)
        val r = SyncAnalysis.report(SyncAnalysis.pair(SyncAnalysis.flashes(t, l), SyncAnalysis.beeps(a, 8000)))
        check(r != null && r.driftMs in 140..220, "20 ms a minute over ten minutes read as drift ${r?.driftMs}")
    }
    // Nothing there: no flashes in a flat picture, no beeps in silence.
    check(SyncAnalysis.flashes(LongArray(90) { it * 33L }, FloatArray(90) { 0.4f }).isEmpty(), "a flat picture had flashes")
    check(SyncAnalysis.beeps(FloatArray(80_000), 8000).isEmpty(), "silence had beeps")
    check(SyncAnalysis.report(emptyList()) == null, "no pairs made a report")

    if (problems.isEmpty()) println("SyncAnalysisChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
