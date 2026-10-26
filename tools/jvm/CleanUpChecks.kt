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

    // ---- Whole frames, as the shader runs it (27 Oct, measured off exported
    // files on the moto: grain of sd 5 levels taken down only 23% at strength
    // 0.8, because each pixel judged "still or moved" from its own difference,
    // which the grain alone makes as large as QUIET). Judged over the 3 x 3
    // round each pixel, the grain averages out and motion does not.
    // Frames are RGB, three floats a pixel, as the shader sees them.
    run {
        val w = 48; val h = 48
        var seed = 11
        fun noise(): Float { seed = seed * 1103515245 + 12345; return (((seed ushr 16) and 0x7FFF) / 32767f - 0.5f) * 0.10f }
        fun sd(x: List<Float>): Double { val m = x.average(); return sqrt(x.map { (it - m) * (it - m) }.average()) }
        fun frameOf(colour: (Int, Int) -> FloatArray) = FloatArray(w * h * 3) { i -> val p = i / 3; colour(p % w, p / w)[i % 3] + noise() }
        // A still picture with grain of sd 0.03 in each channel (7.5 levels, low-light phone footage), at strength 0.8.
        var last: FloatArray? = null
        val rawAt = ArrayList<Float>(); val outAt = ArrayList<Float>()
        val probe = (24 * w + 24) * 3 + 1
        for (f in 0 until 80) {
            val frame = frameOf { _, _ -> floatArrayOf(0.5f, 0.5f, 0.5f) }
            val out = last?.let { CleanUp.denoiseFrame(frame, it, w, h, 0.8f) } ?: frame
            if (f >= 20) { rawAt += frame[probe]; outAt += out[probe] }
            last = out
        }
        check(sd(outAt) < sd(rawAt) * 0.6, "grain on a still frame at 0.8 went only from ${sd(rawAt)} to ${sd(outAt)}")
        // A square moving 4 px a frame over a ground, with the same grain: where it
        // arrives the output is the square, and where it left, the ground. Once
        // bright over dark, and once red over a teal of the same luma - the trail
        // a luma-only judgement left behind a moving test card on the moto.
        val red = floatArrayOf(0.85f, 0.15f, 0.15f)
        val teal = run { val l = 0.2126f * red[0] + 0.7152f * red[1] + 0.0722f * red[2]; floatArrayOf(0.05f, (l - 0.2126f * 0.05f - 0.0722f * 0.6f) / 0.7152f, 0.6f) }
        for ((name, pair) in listOf("bright on dark" to (floatArrayOf(0.8f, 0.8f, 0.8f) to floatArrayOf(0.2f, 0.2f, 0.2f)), "red on teal of its luma" to (red to teal))) {
            val (fg, bg) = pair
            last = null
            var worstArrive = 0f; var worstLeave = 0f
            for (f in 0 until 8) {
                val x0 = 4 + 4 * f
                val frame = frameOf { x, y -> if (x in x0 until x0 + 12 && y in 18 until 30) fg else bg }
                val out = last?.let { CleanUp.denoiseFrame(frame, it, w, h, 1f) } ?: frame
                if (f > 0) {
                    for (y in 20 until 28) for (c in 0 until 3) {
                        worstArrive = maxOf(worstArrive, abs(out[(y * w + x0 + 11) * 3 + c] - fg[c]))
                        worstLeave = maxOf(worstLeave, abs(out[(y * w + x0 - 2) * 3 + c] - bg[c]))
                    }
                }
                last = out
            }
            check(worstArrive < 0.09f, "$name: a moving square arrived as a ghost, $worstArrive off the square")
            check(worstLeave < 0.09f, "$name: a moving square left a trail, $worstLeave off the ground")
        }
        // Strength nothing: the frame as it came.
        val f0 = frameOf { _, _ -> floatArrayOf(0.5f, 0.5f, 0.5f) }; val f1 = frameOf { _, _ -> floatArrayOf(0.5f, 0.5f, 0.5f) }
        check(CleanUp.denoiseFrame(f1, f0, w, h, 0f).contentEquals(f1), "a strength of nothing changed a frame")
    }

    if (problems.isEmpty()) println("CleanUpChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
