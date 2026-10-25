import com.squish.app.media.video.CleanUp
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
 * EDGE_PLAN F9: Remove flicker and Reduce noise, on sequences whose truth is
 * known. Flicker taken down and a fade, a cut and a steady shot left as they
 * are; noise on a still picture taken down and a moving edge not smeared.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val frameUs = 33_333L
    // Mains flicker on a steady room: every other frame 10% darker.
    run {
        val d = CleanUp.Deflicker(1f)
        val out = (0 until 120).map { i -> val m = 0.5f * (if (i % 2 == 0) 1f else 0.9f); m * d.gain(m, i * frameUs) }
        val tail = out.drop(30)
        val swing = tail.zipWithNext { a, b -> abs(a - b) }.average()
        check(swing < 0.05 * 0.5 * 0.3, "flicker of 10% left a swing of ${swing / 0.5 * 100}% frame to frame")
    }
    // A two-second fade to dark: followed, not fought.
    run {
        val d = CleanUp.Deflicker(1f)
        var worst = 0f
        for (i in 0 until 90) {
            val m = if (i < 15) 0.8f else (0.8f - 0.6f * ((i - 15) / 60f)).coerceAtLeast(0.2f)
            val o = m * d.gain(m, i * frameUs)
            if (i > 25) worst = maxOf(worst, abs(o - m) / m)
        }
        check(worst < 0.06f, "a fade was bent ${worst * 100}% out of its way")
    }
    // A cut to a darker shot is a cut.
    run {
        val d = CleanUp.Deflicker(1f)
        repeat(30) { d.gain(0.7f, it * frameUs) }
        val g = d.gain(0.25f, 30 * frameUs)
        check(abs(g - 1f) < 0.02f, "a cut to a darker shot was lifted by $g")
    }
    // Off is off.
    run {
        val d = CleanUp.Deflicker(0f)
        check((0 until 20).all { abs(d.gain(if (it % 2 == 0) 0.5f else 0.4f, it * frameUs) - 1f) < 1e-6f }, "a strength of nothing changed the picture")
    }

    // Noise on a still pixel: sd 0.02 about 0.5.
    run {
        var seed = 7
        fun noise(): Float { seed = seed * 1103515245 + 12345; return (((seed ushr 16) and 0x7FFF) / 32767f - 0.5f) * 0.069f }
        var last = 0.5f
        val raw = ArrayList<Float>(); val out = ArrayList<Float>()
        for (i in 0 until 400) { val v = 0.5f + noise(); raw += v; last = if (i == 0) v else CleanUp.denoise(v, last, 1f); out += last }
        fun sd(x: List<Float>): Double { val m = x.average(); return sqrt(x.map { (it - m) * (it - m) }.average()) }
        check(sd(out.drop(20)) < sd(raw) * 0.55, "noise on a still picture went from ${sd(raw)} to ${sd(out.drop(20))}")
    }
    // A pixel a hand passes over: dark to light in one frame, and the output follows at once.
    run {
        var last = 0.2f
        val seq = List(10) { if (it < 5) 0.2f else 0.8f }
        val out = seq.mapIndexed { i, v -> (if (i == 0) v else CleanUp.denoise(v, last, 1f)).also { last = it } }
        check(abs(out[5] - 0.8f) < 0.01f, "a moving edge left a ghost: ${out[5]} where the picture is 0.8")
    }
    check(CleanUp.denoiseWeight(0f, 0f) == 0f, "a strength of nothing still kept the last frame")

    if (problems.isEmpty()) println("CleanUpChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
